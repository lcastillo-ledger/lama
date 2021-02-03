package co.ledger.lama.bitcoin.interpreter

import co.ledger.lama.bitcoin.common.models.interpreter.{Operation, OperationType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class OperationTest extends AnyFlatSpec with Matchers {

  "Operation.uid" should "be a SHA-256 digest of (accountId,txId,operationType )" in {

    val accountId = Operation.AccountId(UUID.fromString("3bd0b597-3638-4167-9ed3-aa4267efbe0c"))
    val txId      = Operation.TxId("169e1e83e930853391bc6f35f605c6754cfead57cf8387639d3b4096c54f18f4")
    val uid       = Operation.uid(accountId, txId, OperationType.Received)

    uid should be(
      Operation.UID("f8e341fb691ba8e4f611635e46637aa9bdfed84d01f1040d180ed3a166386cd1")
    )

    uid should not be Operation.uid(accountId, txId, OperationType.Sent)
    uid should not be Operation.uid(
      Operation.AccountId(UUID.randomUUID()),
      txId,
      OperationType.Received
    )
    uid should not be Operation.uid(
      accountId,
      Operation.TxId("another tx id"),
      OperationType.Received
    )
  }

}
