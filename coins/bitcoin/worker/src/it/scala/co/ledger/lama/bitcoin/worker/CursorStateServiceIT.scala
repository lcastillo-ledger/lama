package co.ledger.lama.bitcoin.worker

import java.time.Instant
import java.util.UUID

import cats.effect.{ContextShift, IO, Resource, Timer}
import co.ledger.lama.bitcoin.common.models.explorer.{Block, ConfirmedTransaction}
import co.ledger.lama.bitcoin.common.clients.grpc.mocks.InterpreterClientMock
import co.ledger.lama.bitcoin.common.clients.http.ExplorerHttpClient
import co.ledger.lama.bitcoin.worker.config.Config
import co.ledger.lama.bitcoin.worker.services.CursorStateService
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.services.Clients
import co.ledger.lama.common.models.Coin.Btc
import co.ledger.lama.common.utils.IOAssertion
import org.http4s.client.Client
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

class CursorStateServiceIT extends AnyFlatSpecLike with Matchers with IOLogging {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val t: Timer[IO]         = IO.timer(ExecutionContext.global)

  val conf: Config = ConfigSource.default.loadOrThrow[Config]

  val resources: Resource[IO, Client[IO]] = Clients.htt4s

  it should "get the last valid cursor state" in IOAssertion {
    resources.use { httpClient =>
      val explorerClient     = new ExplorerHttpClient(httpClient, conf.explorer, Btc)
      val interpreterClient  = new InterpreterClientMock
      val cursorStateService = new CursorStateService(explorerClient, interpreterClient)

      val accountId = UUID.randomUUID()

      val lastValidHash   = "0x00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608379"
      val lastValidHeight = 559033L
      val invalidHash     = "0x00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608376"

      for {
        // save transactions to create "blocks" in the interpreter
        _ <- interpreterClient.saveTransactions(
          accountId,
          List(
            createTx(
              "0x00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608371", //invalid
              559035L
            ),
            createTx(
              "0x00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608372", //invalid
              559034L
            ),
            createTx(
              "0x00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608379", //last valid
              559033L
            ),
            createTx(
              "0x00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608373", //invalid
              559032L
            ),
            createTx(
              "0000000000000000000bf68b57eacbff287ceafecb54a30dc3fd19630c9a3883", //valid but not last
              559031L
            )
          )
        )

        block <- cursorStateService.getLastValidState(
          accountId,
          Block(invalidHash, 0L, Instant.now())
        )
      } yield {
        block.hash shouldBe lastValidHash
        block.height shouldBe lastValidHeight
      }

    }
  }

  private def createTx(blockHash: String, height: Long) =
    ConfirmedTransaction(
      "id",
      "hash",
      Instant.now(),
      0L,
      1,
      Nil,
      Nil,
      Block(blockHash, height, Instant.now()),
      0
    )

}
