package co.ledger.lama.bitcoin.transactor

import java.util.UUID
import cats.effect.IO
import co.ledger.lama.bitcoin.common.utils.CoinImplicits._
import co.ledger.lama.bitcoin.common.models.interpreter.{ChangeType, Utxo}
import co.ledger.lama.bitcoin.common.models.transactor.{
  BroadcastTransaction,
  CoinSelectionStrategy,
  FeeLevel,
  PrepareTxOutput,
  RawTransaction,
  RawTransactionAndUtxos
}
import co.ledger.lama.bitcoin.common.clients.grpc.{InterpreterClient, KeychainClient}
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient
import co.ledger.lama.bitcoin.common.models.BitcoinNetwork
import co.ledger.lama.bitcoin.transactor.clients.grpc.BitcoinLibClient
import co.ledger.lama.bitcoin.transactor.models.bitcoinLib.SignatureMetadata
import co.ledger.lama.bitcoin.transactor.services.CoinSelectionService
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models.{Coin, Sort}
import fs2.{Chunk, Stream}
import io.circe.syntax._

class Transactor(
    bitcoinLibClient: BitcoinLibClient,
    explorerClient: Coin => ExplorerClient,
    keychainClient: KeychainClient,
    interpreterClient: InterpreterClient
) extends IOLogging {

  def createTransaction(
      accountId: UUID,
      keychainId: UUID,
      outputs: List[PrepareTxOutput],
      coin: Coin,
      coinSelection: CoinSelectionStrategy,
      feeLevel: FeeLevel,
      customFee: Option[Long]
  ): IO[RawTransactionAndUtxos] = {

    for {

      utxos <- getUTXOs(accountId, 100, Sort.Ascending).filter(!_.usedInMempool).compile.toList
      _ <- log.info(
        s"""Utxos found for account $accountId:
            - number of utxos: ${utxos.size}
            - sum: ${utxos.map(_.value).sum}
         """
      )

      estimatedFeeSatPerKb <-
        customFee match {
          case Some(custom) => log.info(s"Custom fee: $custom") *> IO.pure(custom)
          case _            =>
            // TODO: testnet smart fees is buggy on explorer v3
            for {
              smartFee <- coin match {
                case Coin.BtcTestnet => IO.pure(25642L)
                case Coin.BtcRegtest => IO.pure(25642L)
                case c               => explorerClient(c).getSmartFees.map(_.getValue(feeLevel))
              }
              _ <- log.info(s"GetSmartFees feeLevel: $feeLevel - feeSatPerKb: $smartFee ")
            } yield smartFee
        }

      changeAddress <- keychainClient
        .getFreshAddresses(keychainId, ChangeType.Internal, 1)
        .flatMap { addresses =>
          IO.fromOption(addresses.headOption)(
            new NoSuchElementException(
              s"Could not get fresh change address from keychain with id : $keychainId"
            )
          )
        }

      rawTransaction <- createRawTransactionRec(
        coin.toNetwork,
        coinSelection,
        utxos,
        outputs,
        changeAddress.accountAddress,
        estimatedFeeSatPerKb,
        outputs.map(_.value).sum
      )

    } yield rawTransaction

  }

  def generateSignatures(rawTransaction: RawTransaction, privKey: String): IO[List[Array[Byte]]] =
    bitcoinLibClient.generateSignatures(
      rawTransaction,
      privKey
    )

  def broadcastTransaction(
      rawTransaction: RawTransaction,
      keychainId: UUID,
      signatures: List[Array[Byte]],
      coin: Coin
  ): IO[BroadcastTransaction] = {
    for {
      pubKeys <- keychainClient.getAddressesPublicKeys(
        keychainId,
        rawTransaction.utxos.map(_.derivation)
      )

      _ <- log.info(s"Get pub keys $pubKeys")

      signedRawTx <- bitcoinLibClient.signTransaction(
        rawTransaction,
        coin.toNetwork,
        signatures
          .zip(pubKeys)
          .map { case (signature, pubKey) =>
            SignatureMetadata(
              signature,
              pubKey
            )
          }
      )

      _ <- log.info(
        s"""Signed transaction:
            - coin: ${coin.name}
            - signed hex: ${signedRawTx.hex}
            - tx hash: ${signedRawTx.hash}
         """
      )

      broadcastTxHash <- explorerClient(coin).broadcastTransaction(signedRawTx.hex)

      _ <- log.info(s"Broadcasted tx hash: $broadcastTxHash")

      _ <-
        if (signedRawTx.hash != broadcastTxHash)
          IO.raiseError(
            new Exception(
              s"Signed tx hash is not equal to broadcast tx hash: ${signedRawTx.hash} != $broadcastTxHash"
            )
          )
        else IO.unit

    } yield {
      BroadcastTransaction(
        signedRawTx.hex,
        broadcastTxHash,
        signedRawTx.witnessHash
      )
    }
  }

  private def createRawTransactionRec(
      network: BitcoinNetwork,
      strategy: CoinSelectionStrategy,
      utxos: List[Utxo],
      outputs: List[PrepareTxOutput],
      changeAddress: String,
      estimatedFeeSatPerKb: Long,
      amount: BigInt,
      retryCount: Int = 5
  ): IO[RawTransactionAndUtxos] = {

    for {

      _ <-
        if (retryCount <= 0)
          IO.raiseError(
            new Exception(s"""Impossible to create raw transaction satisfying criterias :
                utxos : ${utxos.asJson}
                outputs: ${outputs.asJson}
                estimatedFeeSatPerKb: $estimatedFeeSatPerKb
              """)
          )
        else IO.unit

      selectedUtxos <- CoinSelectionService.coinSelection(
        strategy,
        utxos,
        amount
      )
      _ <- log.info(
        s"""Picked Utxos :
            - number of utxos : ${selectedUtxos.size}
            - sum : ${selectedUtxos.map(_.value).sum}
         """
      )

      _ <- validateTransaction(selectedUtxos, outputs)

      rawTransaction <- bitcoinLibClient.createTransaction(
        network,
        selectedUtxos,
        outputs,
        changeAddress,
        estimatedFeeSatPerKb,
        0L
      )

      rawTransactionAndUtxos <- rawTransaction.notEnoughUtxo.fold(
        IO(
          RawTransactionAndUtxos(
            rawTransaction.hex,
            rawTransaction.hash,
            rawTransaction.witnessHash,
            selectedUtxos
          )
        )
      )(notEnoughUtxo =>
        createRawTransactionRec(
          network,
          strategy,
          utxos,
          outputs,
          changeAddress,
          estimatedFeeSatPerKb,
          outputs.map(_.value).sum + notEnoughUtxo.missingAmount,
          retryCount - 1
        )
      )

    } yield rawTransactionAndUtxos

  }

  private def getUTXOs(accountId: UUID, limit: Int, sort: Sort): Stream[IO, Utxo] = {
    def getUtxosRec(accountId: UUID, limit: Int, offset: Int, sort: Sort): Stream[IO, Utxo] = {
      Stream
        .eval(interpreterClient.getUtxos(accountId, limit + offset, offset, Some(sort)))
        .flatMap { result =>
          val head = Stream.chunk(Chunk.seq(result.utxos)).covary[IO]

          val tail =
            if (result.truncated)
              getUtxosRec(accountId, limit, offset + limit, sort)
            else
              Stream.empty

          head ++ tail
        }
    }

    getUtxosRec(accountId, limit, 0, sort)
  }

  private def validateTransaction(utxos: List[Utxo], recipients: List[PrepareTxOutput]): IO[Unit] =
    if (utxos.map(_.value).sum < recipients.map(_.value).sum)
      IO.raiseError(new Exception("Not enough coins in Utxos to cover for coins sent."))
    else
      IO.unit

}
