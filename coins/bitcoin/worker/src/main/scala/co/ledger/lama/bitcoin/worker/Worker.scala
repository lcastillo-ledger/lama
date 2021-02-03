package co.ledger.lama.bitcoin.worker

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import co.ledger.lama.bitcoin.common.clients.grpc.{InterpreterClient, KeychainClient}
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient
import co.ledger.lama.bitcoin.common.models.explorer.{Block, ConfirmedTransaction, DefaultInput}
import co.ledger.lama.bitcoin.worker.config.Config
import co.ledger.lama.bitcoin.worker.models.BatchResult
import co.ledger.lama.bitcoin.worker.services._
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models.Status.{Registered, Unregistered}
import co.ledger.lama.common.models.messages.{ReportMessage, WorkerMessage}
import co.ledger.lama.common.models.{Coin, ReportError, ReportableEvent}
import fs2.{Chunk, Pull, Stream}
import io.circe.syntax._

import java.util.UUID
import scala.util.Try

class Worker(
    syncEventService: SyncEventService,
    keychainClient: KeychainClient,
    explorerClient: Coin => ExplorerClient,
    interpreterClient: InterpreterClient,
    cursorStateService: Coin => CursorStateService,
    conf: Config
) extends IOLogging {

  val bookkeeper = new Bookkeeper(
    keychainClient,
    explorerClient,
    interpreterClient,
    conf.maxTxsToSavePerBatch,
    conf.maxConcurrent
  )

  def run(implicit cs: ContextShift[IO], t: Timer[IO]): Stream[IO, Unit] =
    syncEventService.consumeWorkerMessages
      .evalMap { message =>
        val reportableEvent =
          message.event.status match {
            case Registered   => synchronizeAccount(message)
            case Unregistered => deleteAccount(message)
          }

        // In case of error, fallback to a reportable failed event.
        log.info(s"Received message: ${message.asJson.toString}") *>
          reportableEvent
            .handleErrorWith { error =>
              val failedEvent = message.event.asReportableFailureEvent(
                ReportError.fromThrowable(error)
              )

              log.error(s"Failed event: $failedEvent", error) *>
                IO.pure(failedEvent)
            }
            // Always report the event within a message at the end.
            .flatMap { reportableEvent =>
              syncEventService.reportMessage(
                ReportMessage(
                  account = message.account,
                  event = reportableEvent
                )
              )
            }
      }

  def synchronizeAccount(
      workerMessage: WorkerMessage[Block]
  )(implicit cs: ContextShift[IO], t: Timer[IO]): IO[ReportableEvent[Block]] = {
    val account       = workerMessage.account
    val workableEvent = workerMessage.event

    val previousBlockState = workableEvent.cursor

    // sync the whole account per streamed batch
    for {
      _          <- log.info(s"Syncing from cursor state: $previousBlockState")
      keychainId <- IO.fromTry(Try(UUID.fromString(account.key)))

      keychainInfo <- keychainClient.getKeychainInfo(keychainId)

      // REORG
      lastValidBlock <- previousBlockState.map { block =>
        for {
          lvb <- cursorStateService(account.coin).getLastValidState(account.id, block)
          _   <- log.info(s"Last valid block : $lvb")
          _ <-
            if (lvb.hash.endsWith(block.hash))
              // If the previous block is still valid, do not reorg
              IO.unit
            else {
              // remove all transactions and operations up until last valid block
              log.info(
                s"${block.hash} is different than ${lvb.hash}, reorg is happening"
              ) *> interpreterClient.removeDataFromCursor(account.id, Some(lvb.height))
            }
        } yield lvb
      }.sequence

      addressesUsedByMempool <- bookkeeper
        .recordUnconfirmedTransactions(
          account.coin,
          account.id,
          keychainId,
          keychainInfo.lookaheadSize,
          0,
          keychainInfo.lookaheadSize
        )
        .stream
        .flatMap(r => Stream.emits(r.addresses))
        .compile
        .toList

      batchResults <- syncAccountBatch(
        account.coin,
        account.id,
        keychainId,
        keychainInfo.lookaheadSize,
        lastValidBlock.map(_.hash),
        0,
        keychainInfo.lookaheadSize
      ).stream.compile.toList

      addresses = batchResults.flatMap(_.addresses)

      txs = batchResults.flatMap(_.transactions).distinctBy(_.hash)

      lastBlock =
        if (txs.isEmpty) previousBlockState
        else Some(txs.maxBy(_.block.time).block)

      _ <- log.info(s"New cursor state: $lastBlock")

      opsCount <- interpreterClient.compute(
        account.id,
        account.coin,
        (addresses ++ addressesUsedByMempool).distinct,
        lastBlock.map(_.height)
      )

      _ <- log.info(s"$opsCount operations computed")

    } yield {
      // Create the reportable successful event.
      workableEvent.asReportableSuccessEvent(lastBlock)
    }
  }

  /** Sync account algorithm:
    *   - 1) Get addresses per batch from the keychain
    *   - 2) Get transactions per batch from the explorer
    *   - 3a) If there are transactions for this batch of addresses:
    *       - mark addresses as used
    *       - repeat 1)
    *   - 3b) Otherwise, stop sync because we consider this batch as fresh addresses
    */
  private def syncAccountBatch(
      coin: Coin,
      accountId: UUID,
      keychainId: UUID,
      lookaheadSize: Int,
      blockHashCursor: Option[String],
      fromAddrIndex: Int,
      toAddrIndex: Int
  )(implicit
      cs: ContextShift[IO],
      t: Timer[IO]
  ): Pull[IO, BatchResult[ConfirmedTransaction], Unit] =
    Pull
      .eval {
        for {
          // Get batch of addresses from the keychain
          _            <- log.info("Calling keychain to get addresses")
          addressInfos <- keychainClient.getAddresses(keychainId, fromAddrIndex, toAddrIndex)

          // For this batch of addresses, fetch transactions from the explorer.
          _ <- log.info("Fetching transactions from explorer")
          transactions <- explorerClient(coin)
            .getConfirmedTransactions(addressInfos.map(_.accountAddress), blockHashCursor)
            .prefetch
            .chunkN(conf.maxTxsToSavePerBatch)
            .map(_.toList)
            .parEvalMapUnordered(conf.maxConcurrent) { txs =>
              // Ask to interpreter to save transactions.
              for {
                _             <- log.info(s"Sending ${txs.size} transactions to interpreter")
                savedTxsCount <- interpreterClient.saveTransactions(accountId, txs)
                _             <- log.info(s"$savedTxsCount new transactions saved")
              } yield txs
            }
            .flatMap(Stream.emits(_))
            .compile
            .toList

          // Filter only used addresses.
          usedAddressesInfos = addressInfos.filter { a =>
            transactions.exists { t =>
              val isInputAddress = t.inputs.collectFirst {
                case i: DefaultInput if i.address == a.accountAddress => i
              }.isDefined

              isInputAddress || t.outputs.exists(_.address == a.accountAddress)
            }
          }

          _ <-
            if (transactions.nonEmpty) {
              // Mark addresses as used.
              log.info(s"Marking addresses as used") *>
                keychainClient.markAddressesAsUsed(
                  keychainId,
                  usedAddressesInfos.map(_.accountAddress)
                )
            } else IO.unit

          // Flag to know if we continue or not to discover addresses
          continue <- keychainClient
            .getAddresses(keychainId, toAddrIndex, toAddrIndex + lookaheadSize)
            .map(_.nonEmpty)

        } yield BatchResult(usedAddressesInfos, transactions, continue)
      }
      .flatMap { batchResult =>
        if (batchResult.continue)
          Pull.output(Chunk(batchResult)) >>
            syncAccountBatch(
              coin,
              accountId,
              keychainId,
              lookaheadSize,
              blockHashCursor,
              toAddrIndex,
              toAddrIndex + lookaheadSize
            )
        else
          Pull.output(Chunk(batchResult))
      }

  def deleteAccount(message: WorkerMessage[Block]): IO[ReportableEvent[Block]] =
    interpreterClient
      .removeDataFromCursor(message.account.id, None)
      .map(_ => message.event.asReportableSuccessEvent(None))
}
