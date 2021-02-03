package co.ledger.lama.bitcoin.common.models.interpreter

import co.ledger.lama.bitcoin.interpreter.protobuf
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.common.utils.{TimestampProtoUtils, UuidUtils}
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.{Decoder, Encoder}

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

case class Operation(
    uid: Operation.UID,
    accountId: UUID,
    hash: String,
    transaction: Option[TransactionView],
    operationType: OperationType,
    value: BigInt,
    fees: BigInt,
    time: Instant,
    blockHeight: Option[Long]
) {
  def toProto: protobuf.Operation = {
    protobuf.Operation(
      UuidUtils.uuidToBytes(accountId),
      hash,
      transaction.map(_.toProto),
      operationType.toProto,
      value.toString,
      fees.toString,
      Some(TimestampProtoUtils.serialize(time)),
      blockHeight.getOrElse(-1L),
      uid = uid.hex
    )
  }
}

object Operation {
  case class UID(hex: String)       extends AnyVal
  case class AccountId(value: UUID) extends AnyVal
  case class TxId(value: String)    extends AnyVal

  object UID {
    implicit val encoder: Encoder[UID] = Encoder[String].contramap(_.hex)
    implicit val decoder: Decoder[UID] = Decoder[String].map(UID(_))
  }

  implicit val encoder: Encoder[Operation] = deriveConfiguredEncoder[Operation]
  implicit val decoder: Decoder[Operation] = deriveConfiguredDecoder[Operation]

  def fromProto(proto: protobuf.Operation): Operation = {
    Operation(
      UID(proto.uid),
      UuidUtils.unsafeBytesToUuid(proto.accountId),
      proto.hash,
      proto.transaction.map(TransactionView.fromProto),
      OperationType.fromProto(proto.operationType),
      BigInt(proto.value),
      BigInt(proto.fees),
      proto.time.map(TimestampProtoUtils.deserialize).getOrElse(Instant.now),
      if (proto.blockHeight >= 0) Some(proto.blockHeight) else None
    )
  }

  def uid(accountId: AccountId, txId: TxId, operationType: OperationType): UID = {

    val libcoreType = operationType match {
      case OperationType.Sent     => "SEND"
      case OperationType.Received => "RECEIVE"
    }

    val rawUid = s"uid:${accountId.value.toString.toLowerCase}+${txId.value}+$libcoreType"

    UID(
      MessageDigest
        .getInstance("SHA-256")
        .digest(rawUid.getBytes("UTF-8"))
        .map("%02x".format(_))
        .mkString
    )
  }

}
