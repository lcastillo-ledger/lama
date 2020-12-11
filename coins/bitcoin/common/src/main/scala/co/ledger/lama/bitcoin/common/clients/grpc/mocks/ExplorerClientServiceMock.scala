package co.ledger.lama.bitcoin.common.clients.grpc.mocks

import cats.effect.{ContextShift, IO, Timer}
import co.ledger.lama.bitcoin.common.clients.ExplorerClientService
import co.ledger.lama.bitcoin.common.models.transactor.FeeInfo
import co.ledger.lama.bitcoin.common.models.explorer._

class ExplorerClientServiceMock extends ExplorerClientService {

  def getCurrentBlock: IO[Block] = ???

  def getBlock(hash: String): IO[Option[Block]] = ???

  def getBlock(height: Long): IO[Block] = ???

  def getConfirmedTransactions(addresses: Seq[String], blockHash: Option[String])(implicit
      cs: ContextShift[IO],
      t: Timer[IO]
  ): fs2.Stream[IO, ConfirmedTransaction] = ???

  def getSmartFees: IO[FeeInfo] = {
    IO(FeeInfo(500, 1000, 1500))
  }

  override def broadcastTransaction(tx: String): IO[String] = ???

}