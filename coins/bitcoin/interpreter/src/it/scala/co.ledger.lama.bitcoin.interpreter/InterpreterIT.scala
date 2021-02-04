package co.ledger.lama.bitcoin.interpreter

import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import co.ledger.lama.bitcoin.common.models.explorer._
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.common.models.{Coin, Sort}
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class InterpreterIT extends AnyFlatSpecLike with Matchers with TestResources {

  val accountId: UUID = UUID.fromString("b723c553-3a9a-4130-8883-ee2f6c2f9202")

  private val outputAddress1 =
    AccountAddress("1DtwACvd338XtHBFYJRVKRLxviD7YtYADa", ChangeType.External, NonEmptyList.of(1, 0))
  private val outputAddress2 =
    AccountAddress("1LK8UbiRwUzC8KFEbMKvgbvriM9zLMce3C", ChangeType.Internal, NonEmptyList.of(0, 0))
  private val inputAddress =
    AccountAddress("1LD1pARePgXXyZA1J3EyvRtB82vxENs5wQ", ChangeType.External, NonEmptyList.of(1, 1))

  private val time: Instant = Instant.parse("2019-04-04T10:03:22Z")

  val block: Block = Block(
    "00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608379",
    570153,
    time
  )

  val outputs = List(
    Output(0, 50000, outputAddress1.accountAddress, "script"),
    Output(1, 9434, outputAddress2.accountAddress, "script")
  )
  val inputs = List(
    DefaultInput(
      "0f38e5f1b12078495a9e80c6e0d77af3d674cfe6096bb6e7909993a53b6e8386",
      0,
      0,
      80000,
      inputAddress.accountAddress,
      "script",
      List(),
      4294967295L
    )
  )
  val insertTx: ConfirmedTransaction =
    ConfirmedTransaction(
      "txId",
      "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
      time,
      0,
      20566,
      inputs,
      outputs,
      block,
      1
    )

  "a transaction" should "have a full lifecycle" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter = new Interpreter(_ => IO.unit, db, 1)

        val block2 = Block(
          "0000000000000000000cc9cc204cf3b314d106e69afbea68f2ae7f9e5047ba74",
          block.height + 1,
          time
        )
        val block3 = Block(
          "0000000000000000000bf68b57eacbff287ceafecb54a30dc3fd19630c9a3883",
          block.height + 2,
          time
        )

        // intentionally disordered
        val blocksToSave = List(block2, block, block3)

        for {
          _ <- interpreter.saveTransactions(accountId, List(insertTx))
          _ <- interpreter.saveTransactions(
            accountId,
            List(
              insertTx.copy(hash = "toto", block = block2),
              insertTx.copy(hash = "tata", block = block3)
            )
          )

          blocks <- interpreter.getLastBlocks(accountId)

          _ <- interpreter.compute(
            accountId,
            List(inputAddress, outputAddress2),
            Coin.Btc,
            block.height
          )

          resOpsBeforeDeletion <- interpreter.getOperations(
            accountId,
            blockHeight = 0L,
            20,
            0,
            Sort.Ascending
          )

          GetOperationsResult(opsBeforeDeletion, opsBeforeDeletionTotal, opsBeforeDeletionTrunc) =
            resOpsBeforeDeletion

          resUtxoBeforeDeletion <- interpreter.getUtxos(
            accountId,
            20,
            0,
            Sort.Ascending
          )

          GetUtxosResult(utxosBeforeDeletion, utxosBeforeDeletionTotal, utxosBeforeDeletionTrunc) =
            resUtxoBeforeDeletion

          start = time.minusSeconds(86400)
          end   = time.plusSeconds(86400)
          balancesBeforeDeletion <- interpreter.getBalanceHistory(
            accountId,
            Some(start),
            Some(end),
            0
          )

          _ <- interpreter.removeDataFromCursor(accountId, block.height)

          resOpsAfterDeletion <- interpreter.getOperations(
            accountId,
            blockHeight = 0L,
            20,
            0,
            Sort.Ascending
          )

          GetOperationsResult(opsAfterDeletion, opsAfterDeletionTotal, opsAfterDeletionTrunc) =
            resOpsAfterDeletion

          balancesAfterDeletion <- interpreter.getBalanceHistory(
            accountId,
            Some(start),
            Some(end),
            0
          )

          blocksAfterDelete <- interpreter.getLastBlocks(accountId)
        } yield {
          blocks should be(blocksToSave.sortBy(_.height)(Ordering[Long].reverse))

          opsBeforeDeletion should have size 3
          opsBeforeDeletionTotal shouldBe 3
          opsBeforeDeletionTrunc shouldBe false

          utxosBeforeDeletion should have size 3
          utxosBeforeDeletionTotal shouldBe 3
          utxosBeforeDeletionTrunc shouldBe false

          balancesBeforeDeletion should have size 3

          opsAfterDeletion shouldBe empty
          opsAfterDeletionTotal shouldBe 0
          opsAfterDeletionTrunc shouldBe false

          balancesAfterDeletion shouldBe empty

          blocksAfterDelete shouldBe empty
        }
      }
  }

  "an unconfirmed transaction" should "have a full lifecycle" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter = new Interpreter(_ => IO.unit, db, 1)

        val uTx = UnconfirmedTransaction(
          "txId",
          "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
          time,
          0,
          20566,
          inputs,
          outputs,
          1
        )
        val uTx2 = uTx.copy(
          id = "txId2",
          hash = "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1e"
        )

        for {
          _ <- interpreter.saveUnconfirmedTransactions(accountId, List(uTx))
          _ <- interpreter.saveUnconfirmedTransactions(accountId, List(uTx2))
          _ <- interpreter.compute(
            accountId,
            List(outputAddress1),
            Coin.Btc,
            block.height
          )
          res <- interpreter.getOperations(accountId, 0L, 20, 0, Sort.Descending)
          GetOperationsResult(operations, _, _) = res
          currentBalance <- interpreter.getBalance(accountId)
          balanceHistory <- interpreter.getBalanceHistory(accountId, None, None, 0)
        } yield {
          currentBalance.balance should be(0)
          currentBalance.unconfirmedBalance should be(100000)

          operations should have size 2
          operations.head.blockHeight should be(None)

          balanceHistory.last.balance should be(currentBalance.unconfirmedBalance)
        }
      }

  }

  "an unconfirmed transaction" should "not be saved if it's been mined" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter = new Interpreter(_ => IO.unit, db, 1)

        val uTx = UnconfirmedTransaction(
          "txId",
          "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
          time,
          0,
          20566,
          inputs,
          outputs,
          1
        )

        val tx = ConfirmedTransaction(
          "txId",
          "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
          time,
          0,
          20566,
          inputs,
          outputs,
          block,
          1
        )

        for {
          _ <- interpreter.saveUnconfirmedTransactions(accountId, List(uTx))
          _ <- interpreter.saveTransactions(accountId, List(tx))
          _ <- interpreter.compute(
            accountId,
            List(outputAddress1),
            Coin.Btc,
            block.height
          )
          res <- interpreter.getOperations(accountId, 0L, 20, 0, Sort.Descending)
          GetOperationsResult(operations, _, _) = res
        } yield {
          operations should have size 1
        }
      }

  }
}
