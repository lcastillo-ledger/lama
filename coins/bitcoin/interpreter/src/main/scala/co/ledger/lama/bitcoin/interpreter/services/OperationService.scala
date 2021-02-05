package co.ledger.lama.bitcoin.interpreter.services

import cats.data.OptionT

import java.util.UUID
import cats.effect.{ContextShift, IO}
import co.ledger.lama.bitcoin.common.models.interpreter.{
  GetOperationResult,
  GetOperationsResult,
  GetUtxosResult,
  Operation,
  OutputView,
  TransactionView,
  Utxo
}
import co.ledger.lama.bitcoin.interpreter.models.{OperationToSave, TransactionAmounts}
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models.Sort
import doobie._
import doobie.implicits._
import fs2._

class OperationService(
    db: Transactor[IO],
    maxConcurrent: Int
) extends IOLogging {

  def getOperations(
      accountId: UUID,
      blockHeight: Long,
      limit: Int,
      offset: Int,
      sort: Sort
  )(implicit cs: ContextShift[IO]): IO[GetOperationsResult] =
    for {
      opsWithTx <- OperationQueries
        .fetchOperations(accountId, blockHeight, sort, Some(limit + 1), Some(offset))
        .transact(db)
        .parEvalMap(maxConcurrent) { op =>
          OperationQueries
            .fetchTransaction(op.accountId, op.hash)
            .transact(db)
            .map(tx => op.copy(transaction = tx))
        }
        .compile
        .toList

      total <- OperationQueries.countOperations(accountId, blockHeight).transact(db)

      // we get 1 more than necessary to know if there's more, then we return the correct number
      truncated = opsWithTx.size > limit

    } yield {
      val operations = opsWithTx.slice(0, limit)
      GetOperationsResult(operations, total, truncated)
    }

  def getOperation(
      accountId: Operation.AccountId,
      operationId: Operation.UID
  )(implicit cs: ContextShift[IO]): IO[GetOperationResult] = {

    val op = for {
      operation <- OptionT(OperationQueries.findOperation(accountId, operationId))
      tx        <- OptionT(OperationQueries.fetchTransaction(accountId.value, operation.hash))
    } yield operation.copy(transaction = Some(tx))

    op.value.transact(db).map(GetOperationResult)
  }

  def deleteUnconfirmedTransactionView(accountId: UUID): IO[Int] =
    OperationQueries
      .deleteUnconfirmedTransactionsViews(accountId)
      .transact(db)

  def saveUnconfirmedTransactionView(
      accountId: UUID,
      transactions: List[TransactionView]
  ): IO[Int] =
    OperationQueries
      .saveUnconfirmedTransactionView(accountId, transactions)
      .transact(db)

  def deleteUnconfirmedOperations(accountId: UUID): IO[Int] =
    OperationQueries
      .deleteUnconfirmedOperations(accountId)
      .transact(db)

  def getUtxos(
      accountId: UUID,
      sort: Sort,
      limit: Int,
      offset: Int
  ): IO[GetUtxosResult] =
    for {
      confirmedUtxos <- OperationQueries
        .fetchUTXOs(accountId, sort, Some(limit + 1), Some(offset))
        .transact(db)
        .compile
        .toList

      // Flag utxos used in the mempool
      unconfirmedInputs <- OperationQueries
        .fetchUnconfirmedTransactionsViews(accountId)
        .transact(db)
        .map(_.flatMap(_.inputs).filter(_.belongs))

      total <- OperationQueries.countUTXOs(accountId).transact(db)

    } yield {
      val utxos = confirmedUtxos.map(utxo =>
        utxo.copy(
          usedInMempool = unconfirmedInputs.exists(input =>
            input.outputHash == utxo.transactionHash && input.outputIndex == utxo.outputIndex
          )
        )
      )

      // We get 1 more than necessary to know if there's more, then we return the correct number
      GetUtxosResult(utxos.slice(0, limit), total, truncated = utxos.size > limit)
    }

  def getUnconfirmedUtxos(accountId: UUID): IO[List[Utxo]] =
    OperationQueries
      .fetchUnconfirmedTransactionsViews(accountId)
      .transact(db)
      .map { unconfirmedTxs =>
        val usedOutputs = unconfirmedTxs
          .flatMap(_.inputs)
          .filter(_.belongs)
          .map(i => (i.outputHash, i.outputIndex))

        val outputMap = unconfirmedTxs
          .flatMap(tx =>
            tx.outputs
              .collect { case o @ OutputView(_, _, _, _, _, Some(derivation)) =>
                (tx.hash, o.outputIndex) -> (tx.hash, o, tx.receivedAt, derivation)
              }
          )
          .toMap

        val unusedOutputs = outputMap.keys.toList
          .diff(usedOutputs)
          .map(outputMap)

        unusedOutputs
          .map { case (hash, output, time, derivation) =>
            Utxo(
              hash,
              output.outputIndex,
              output.value,
              output.address,
              output.scriptHex,
              output.changeType,
              derivation,
              time
            )
          }
      }

  def removeFromCursor(accountId: UUID, blockHeight: Long): IO[Int] =
    OperationQueries.removeFromCursor(accountId, blockHeight).transact(db)

  def compute(accountId: UUID): Stream[IO, OperationToSave] =
    operationSource(accountId)
      .flatMap(op => Stream.chunk(op.computeOperations))

  private def operationSource(accountId: UUID): Stream[IO, TransactionAmounts] =
    OperationQueries
      .fetchTransactionAmounts(accountId)
      .transact(db)

  def saveOperationSink(implicit cs: ContextShift[IO]): Pipe[IO, OperationToSave, OperationToSave] =
    in =>
      in.chunkN(1000) // TODO : in conf
        .prefetch
        .parEvalMapUnordered(maxConcurrent) { batch =>
          OperationQueries.saveOperations(batch).transact(db).map(_ => batch)
        }
        .flatMap(Stream.chunk)

}
