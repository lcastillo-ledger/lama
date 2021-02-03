package co.ledger.lama.bitcoin.interpreter

import java.time.Instant
import java.util.UUID

import co.ledger.lama.bitcoin.common.models.explorer._
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.interpreter.models.OperationToSave
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class QueriesIT extends AnyFlatSpecLike with Matchers with TestResources {

  val block: Block = Block(
    "00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608379",
    570153,
    Instant.parse("2019-04-04T10:03:22Z")
  )

  val accountId: UUID = UUID.fromString("b723c553-3a9a-4130-8883-ee2f6c2f9201")

  val outputs = List(
    Output(0, 50000, "1DtwACvd338XtHBFYJRVKRLxviD7YtYADa", "script"),
    Output(1, 9434, "1LK8UbiRwUzC8KFEbMKvgbvriM9zLMce3C", "script")
  )
  val inputs = List(
    DefaultInput(
      "0f38e5f1b12078495a9e80c6e0d77af3d674cfe6096bb6e7909993a53b6e8386",
      0,
      0,
      80000,
      "1LD1pARePgXXyZA1J3EyvRtB82vxENs5wQ",
      "script",
      List(),
      4294967295L
    )
  )
  val transactionToInsert: ConfirmedTransaction =
    ConfirmedTransaction(
      "txId",
      "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
      Instant.parse("2019-04-04T10:03:22Z"),
      0,
      20566,
      inputs,
      outputs,
      block,
      1
    )

  "transaction saved in db" should "be returned and populated by fetch" in IOAssertion {
    setup() *>
      appResources.use { db =>
        for {
          _   <- QueryUtils.saveTx(db, transactionToInsert, accountId)
          _   <- QueryUtils.saveTx(db, transactionToInsert, accountId) // check upsert
          txO <- QueryUtils.fetchTx(db, accountId, transactionToInsert.hash)
        } yield {

          val tx = txO.get

          tx.id shouldBe transactionToInsert.id
          tx.hash shouldBe transactionToInsert.hash

          tx.inputs should have size 1
          tx.inputs.head.value shouldBe 80000

          tx.outputs should have size 2
          tx.outputs.filter(_.outputIndex == 0).head.value shouldBe 50000

        }
      }
  }

  val opToSave: OperationToSave = OperationToSave(
    Operation.uid(
      Operation.AccountId(accountId),
      Operation.TxId(transactionToInsert.id),
      OperationType.Sent
    ),
    accountId,
    transactionToInsert.hash,
    OperationType.Sent,
    transactionToInsert.inputs.collect {
      case i: DefaultInput =>
        i.value
    }.sum,
    transactionToInsert.fees,
    block.time,
    Some(block.hash),
    Some(block.height)
  )

  "operation saved in db" should "be fetched" in IOAssertion {
    setup() *>
      appResources.use { db =>
        for {
          _  <- QueryUtils.saveTx(db, transactionToInsert, accountId)
          _  <- QueryUtils.saveOp(db, opToSave)
          op <- QueryUtils.fetchOps(db, accountId)
        } yield {
          op should contain only
            Operation(
              opToSave.uid,
              opToSave.accountId,
              opToSave.hash,
              None,
              opToSave.operationType,
              opToSave.value,
              opToSave.fees,
              opToSave.time,
              opToSave.blockHeight
            )
        }
      }
  }

}
