package co.ledger.lama.bitcoin.transactor

import java.time.Instant
import java.util.UUID
import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.common.models.interpreter.{AccountAddress, ChangeType}
import co.ledger.lama.bitcoin.common.models.transactor.{
  CoinSelectionStrategy,
  FeeLevel,
  PrepareTxOutput
}
import co.ledger.lama.bitcoin.common.models.explorer.{Block, ConfirmedTransaction, Output}
import co.ledger.lama.bitcoin.common.clients.grpc.mocks.{InterpreterClientMock, KeychainClientMock}
import co.ledger.lama.bitcoin.common.clients.http.mocks.ExplorerClientMock
import co.ledger.lama.bitcoin.transactor.services.BitcoinLibClientServiceMock
import co.ledger.lama.common.models.Coin
import co.ledger.lama.common.models.Coin.Btc
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class TransactorIT extends AnyFlatSpecLike with Matchers {

  "Transactor" should "create hex transaction" in IOAssertion {

    val interpreterService = new InterpreterClientMock
    val bitcoinLibService  = new BitcoinLibClientServiceMock
    val keychainService    = new KeychainClientMock
    val explorerService    = new ExplorerClientMock()
    val transactor =
      new Transactor(
        bitcoinLibService,
        _ => explorerService,
        keychainService,
        interpreterService
      )

    val accountId = UUID.randomUUID()

    val transactionHash = "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f"

    val outputAddress1 = AccountAddress(
      "1DtwACvd338XtHBFYJRVKRLxviD7YtYADa",
      ChangeType.External,
      NonEmptyList.of(1, 0)
    )
    val outputAddress2 = AccountAddress(
      "1LK8UbiRwUzC8KFEbMKvgbvriM9zLMce3C",
      ChangeType.External,
      NonEmptyList.of(1, 1)
    )
    val outputAddress3 = AccountAddress(
      "1LD1pARePgXXyZA1J3EyvRtB82vxENs5wQ",
      ChangeType.External,
      NonEmptyList.of(1, 2)
    )

    val outputs = List(
      Output(0, 10000, outputAddress1.accountAddress, "script"),
      Output(1, 5000, outputAddress2.accountAddress, "script"),
      Output(2, 5000, outputAddress3.accountAddress, "script")
    )

    val block = Block(
      "blockHash",
      1L,
      Instant.parse("2019-04-04T10:03:22Z")
    )

    // We need to create some utxos
    val transactions = List(
      ConfirmedTransaction(
        transactionHash,
        transactionHash,
        Instant.parse("2019-04-04T10:03:22Z"),
        0,
        20566,
        Nil,
        outputs,
        block,
        1
      )
    )

    val recipients: List[PrepareTxOutput] = List(
      PrepareTxOutput("recipientAddress", 17000)
    )

    for {
      // save the transactions with the futures utxos
      _ <- interpreterService.saveTransactions(
        accountId,
        transactions
      )

      // compute to flag utxos as belonging
      _ <- interpreterService.compute(
        accountId,
        Btc,
        List(outputAddress1, outputAddress2, outputAddress3),
        Some(block.height)
      )

      // create a transaction using prevously saved utxoq
      response <- transactor.createTransaction(
        accountId,
        UUID.randomUUID(),
        recipients,
        Coin.Btc,
        CoinSelectionStrategy.DepthFirst,
        FeeLevel.Normal,
        None
      )

    } yield {
      response.hex should have size 3
      response.hex should be("hex")
    }
  }

}
