package co.ledger.lama.bitcoin.common.models.interpreter

import co.ledger.lama.bitcoin.interpreter.protobuf

case class GetOperationResult(
    operation: Option[Operation]
) {
  def toProto: protobuf.GetOperationResult =
    protobuf.GetOperationResult(
      operation.map(_.toProto)
    )
}
