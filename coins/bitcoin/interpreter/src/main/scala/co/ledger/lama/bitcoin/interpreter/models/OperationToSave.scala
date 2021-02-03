package co.ledger.lama.bitcoin.interpreter.models

import java.time.Instant
import java.util.UUID
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.bitcoin.common.models.interpreter.{
  ChangeType,
  Operation,
  OperationType,
  TransactionView
}
import co.ledger.lama.common.logging.IOLogging
import fs2.Chunk
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto._

case class OperationToSave(
    uid: Operation.UID,
    accountId: UUID,
    hash: String,
    operationType: OperationType,
    value: BigInt,
    fees: BigInt,
    time: Instant,
    blockHash: Option[String],
    blockHeight: Option[Long]
)

object OperationToSave {
  implicit val encoder: Encoder[OperationToSave] =
    deriveConfiguredEncoder[OperationToSave]
  implicit val decoder: Decoder[OperationToSave] =
    deriveConfiguredDecoder[OperationToSave]

  def fromTransactionView(accountId: UUID, tx: TransactionView): List[OperationToSave] =
    TransactionAmounts(
      accountId,
      tx.hash,
      None,
      None,
      None,
      tx.fees,
      tx.inputs.filter(_.belongs).map(_.value).sum,
      tx.outputs
        .filter(o => o.belongs && o.changeType.contains(ChangeType.Internal))
        .map(_.value)
        .sum,
      tx.outputs
        .filter(o => o.belongs && o.changeType.contains(ChangeType.External))
        .map(_.value)
        .sum
    ).computeOperations.toList
}

case class TransactionAmounts(
    accountId: UUID,
    hash: String,
    blockHash: Option[String],
    blockHeight: Option[Long],
    blockTime: Option[Instant],
    fees: BigInt,
    inputAmount: BigInt,
    outputAmount: BigInt,
    changeAmount: BigInt
) extends IOLogging {

  def computeOperations: Chunk[OperationToSave] = {
    TransactionType.fromAmounts(inputAmount, outputAmount, changeAmount) match {
      case SendType =>
        Chunk(makeOperationToSave(inputAmount - changeAmount, OperationType.Sent))
      case ReceiveType =>
        Chunk(makeOperationToSave(outputAmount + changeAmount, OperationType.Received))
      case ChangeOnlyType =>
        Chunk(makeOperationToSave(changeAmount, OperationType.Received))
      case BothType =>
        Chunk(
          makeOperationToSave(inputAmount - changeAmount, OperationType.Sent),
          makeOperationToSave(outputAmount, OperationType.Received)
        )
      case NoneType =>
        log.error(
          s"Error on tx : $hash, no transaction type found for amounts : input: $inputAmount, output: $outputAmount, change: $changeAmount"
        )
        Chunk.empty
    }
  }

  private def makeOperationToSave(amount: BigInt, operationType: OperationType) = {
    OperationToSave(
      Operation.uid(Operation.AccountId(accountId), Operation.TxId(hash), operationType),
      accountId = accountId,
      hash = hash,
      operationType = operationType,
      value = amount,
      time = blockTime.getOrElse(Instant.now()),
      blockHash = blockHash,
      blockHeight = blockHeight,
      fees = fees
    )
  }
}
