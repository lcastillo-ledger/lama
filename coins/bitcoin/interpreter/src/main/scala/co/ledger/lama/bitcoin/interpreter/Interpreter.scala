package co.ledger.lama.bitcoin.interpreter

import cats.effect.{ContextShift, IO}
import co.ledger.lama.bitcoin.common.models.explorer._
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.interpreter.services._
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models._
import io.circe.syntax._
import fs2.Stream
import doobie.Transactor
import java.time.Instant
import java.util.UUID

import co.ledger.lama.bitcoin.interpreter.models.OperationToSave

class Interpreter(
    publish: Notification => IO[Unit],
    db: Transactor[IO],
    maxConcurrent: Int
)(implicit cs: ContextShift[IO])
    extends IOLogging {

  val transactionService = new TransactionService(db, maxConcurrent)
  val operationService   = new OperationService(db, maxConcurrent)
  val flaggingService    = new FlaggingService(db)
  val balanceService     = new BalanceService(db)

  def saveTransactions(
      accountId: UUID,
      transactions: List[ConfirmedTransaction]
  ): IO[Int] =
    transactionService.saveTransactions(accountId, transactions)

  def saveUnconfirmedTransactions(
      accountId: UUID,
      transactions: List[UnconfirmedTransaction]
  ): IO[Int] =
    transactionService.saveUnconfirmedTransactions(accountId, transactions)

  def getLastBlocks(
      accountId: UUID
  ): IO[List[Block]] =
    transactionService
      .getLastBlocks(accountId)
      .compile
      .toList

  def getOperations(
      accountId: UUID,
      blockHeight: Long,
      requestLimit: Int,
      requestOffset: Int,
      sort: Sort
  ): IO[GetOperationsResult] = {
    val limit  = if (requestLimit <= 0) 20 else requestLimit
    val offset = if (requestOffset < 0) 0 else requestOffset
    operationService.getOperations(accountId, blockHeight, limit, offset, sort)
  }

  def getUtxos(
      accountId: UUID,
      requestLimit: Int,
      requestOffset: Int,
      sort: Sort
  ): IO[GetUtxosResult] = {
    val limit  = if (requestLimit <= 0) 20 else requestLimit
    val offset = if (requestOffset < 0) 0 else requestOffset
    operationService.getUtxos(accountId, sort, limit, offset)
  }

  def getUnconfirmedUtxos(
      accountId: UUID
  ): IO[List[Utxo]] =
    operationService.getUnconfirmedUtxos(accountId)

  def removeDataFromCursor(
      accountId: UUID,
      blockHeight: Long
  ): IO[Int] = {
    for {
      txRes <- transactionService.removeFromCursor(accountId, blockHeight)
      _     <- transactionService.deleteUnconfirmedTransaction(accountId)
      _     <- operationService.removeFromCursor(accountId, blockHeight)
      _     <- operationService.deleteUnconfirmedTransactionView(accountId)
      _     <- log.info(s"Deleted $txRes transactions")
      balancesRes <- balanceService.removeBalanceHistoryFromCursor(
        accountId,
        blockHeight
      )
      _ <- log.info(s"Deleted $balancesRes balances history")
    } yield txRes
  }

  def compute(
      accountId: UUID,
      addresses: List[AccountAddress],
      coin: Coin,
      lastBlockHeight: Long
  ): IO[Int] =
    for {
      balanceHistoryCount <- balanceService.getBalanceHistoryCount(accountId)

      nbSavedOps <- saveOperationsAndNotify(
        accountId,
        operationService.compute(accountId),
        addresses,
        coin,
        balanceHistoryCount > 0
      )

      unconfirmedTransactions <- computeAndSaveUnconfirmedTxs(accountId, addresses, lastBlockHeight)
      unconfirmedOperations = unconfirmedTransactions.flatMap(
        OperationToSave.fromTransactionView(accountId, _)
      )

      _ <- saveOperationsAndNotify(
        accountId,
        Stream.emits(unconfirmedOperations),
        addresses,
        coin,
        balanceHistoryCount > 0
      )

      _              <- log.info("Computing balance history")
      _              <- balanceService.computeNewBalanceHistory(accountId)
      currentBalance <- balanceService.getCurrentBalance(accountId)

      _ <- publish(
        BalanceUpdatedNotification(
          accountId = accountId,
          coinFamily = CoinFamily.Bitcoin,
          coin = coin,
          currentBalance = currentBalance.asJson
        )
      )

    } yield nbSavedOps

  def computeAndSaveUnconfirmedTxs(
      accountId: UUID,
      addresses: List[AccountAddress],
      lastBlockHeight: Long
  ): IO[List[TransactionView]] =
    for {
      unconfirmedTransactions <- transactionService.fetchUnconfirmedTransactions(accountId)
      unconfirmedTransactionsViews = unconfirmedTransactions.map(_.toTransactionView(addresses))

      //remove tx mined in between
      minedTxs <- operationService.getOperations(
        accountId,
        lastBlockHeight,
        1000,
        0,
        Sort.Descending
      )
      dedupTxs = unconfirmedTransactionsViews.filterNot(utx =>
        minedTxs.operations.exists(tx => tx.hash == utx.hash)
      )

      _ <- operationService.deleteUnconfirmedTransactionView(accountId)
      _ <- operationService.saveUnconfirmedTransactionView(accountId, dedupTxs)
      _ <- transactionService.deleteUnconfirmedTransaction(accountId)
    } yield dedupTxs

  def saveOperationsAndNotify(
      accountId: UUID,
      stream: Stream[IO, OperationToSave],
      addresses: List[AccountAddress],
      coin: Coin,
      shouldNotify: Boolean
  ): IO[Int] =
    for {
      _ <- log.info(s"Flagging inputs and outputs belong to account=$accountId")
      _ <- flaggingService.flagInputsAndOutputs(accountId, addresses)

      _ <- operationService.deleteUnconfirmedOperations(accountId)

      _ <- log.info("Computing operations")
      nbSavedOps <- stream
        .through(operationService.saveOperationSink)
        .parEvalMap(maxConcurrent) { op =>
          if (shouldNotify) {
            publish(
              OperationNotification(
                accountId = accountId,
                coinFamily = CoinFamily.Bitcoin,
                coin = coin,
                operation = op.asJson
              )
            )
          } else
            IO.unit
        }
        .compile
        .toList
        .map(_.length)

    } yield nbSavedOps

  def getBalance(
      accountId: UUID
  ): IO[CurrentBalance] =
    balanceService.getCurrentBalance(accountId)

  def getBalanceHistory(
      accountId: UUID,
      startO: Option[Instant],
      endO: Option[Instant],
      interval: Int
  ): IO[List[BalanceHistory]] = {

    if (startO.forall(start => endO.forall(end => start.isBefore(end))) && interval >= 0)
      balanceService.getBalanceHistory(
        accountId,
        startO,
        endO,
        if (interval > 0) Some(interval) else None
      )
    else
      IO.raiseError(
        new Exception(
          "Invalid parameters : 'start' should not be after 'end' and 'interval' should be positive"
        )
      )
  }

}
