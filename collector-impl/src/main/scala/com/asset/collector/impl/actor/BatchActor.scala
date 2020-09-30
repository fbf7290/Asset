package com.asset.collector.impl.actor

import java.time.temporal.ChronoUnit
import java.time.{ZoneId, ZonedDateTime}

import akka.Done
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import com.asset.collector.api.{CollectorSettings, Country, Market, Price, Stock}
import com.asset.collector.impl.repo.stock.{StockRepoAccessor, StockRepoTrait}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import com.asset.collector.api.Country.Country
import com.asset.collector.impl.Fcm
import com.asset.collector.impl.Fcm.FcmMessage
import com.asset.collector.impl.acl.External
import play.api.libs.json.JsNull

import scala.collection.mutable.ListBuffer
//import cats.implicits._
import scala.util.{Failure, Success}
import cats.syntax.traverse._
import cats.instances.future._
import cats.instances.list._

object BatchActor {
  sealed trait Command

  case class CollectKoreaStock(code:String, replyTo:ActorRef[Response]) extends Command
  case class CollectUsaStock(code:String, replyTo:ActorRef[Response]) extends Command
  case class CollectKoreaStocks(replyTo:Option[ActorRef[Reply.type]]) extends Command
  case class CollectUsaStocks(replyTo:Option[ActorRef[Reply.type]]) extends Command
  case class SuccessStockListBatch(country: Country) extends Command

  sealed trait Response
  case object Reply extends Response
  case object Complete extends Response
  case class NotComplete(exception:Throwable) extends Response

  def apply(stockDb:StockRepoTrait[Future])
           (implicit wsClient: WSClient, ec: ExecutionContext, materializer: Materializer):Behavior[Command] = Behaviors.setup{ context =>
    Behaviors.supervise[Command]{
      Behaviors.withStash(20){ buffer =>
        Behaviors.withTimers[Command] { timers =>

          def setDailyTimer(msg: Command, plusHours: Int) = {
            val zoneId = ZoneId.of("UTC+09:00")
            val now = ZonedDateTime.now(zoneId)
            val after = now.plusDays(1).truncatedTo(ChronoUnit.DAYS).plusHours(plusHours)
            timers.startSingleTimer(msg, msg, (after.toEpochSecond - now.toEpochSecond).seconds)
          }

          def refreshStockList(country: Country, nowStocks:Set[Stock]) =
            for {
              stocks <- StockRepoAccessor.selectStocks[Future](country).map(_.toSet)
              _ <- StockRepoAccessor.insertBatchStock[Future](country, (nowStocks -- stocks).toSeq)
              _ <-  (stocks -- nowStocks).map(stock => StockRepoAccessor.deleteStock[Future](country, stock)).toList.sequence
            } yield {}


          setDailyTimer(CollectKoreaStocks(None), 0)
          setDailyTimer(CollectUsaStocks(None), 8)


          def ing:Behavior[Command] = Behaviors.receiveMessage {
            case CollectKoreaStock(code, replyTo) =>
              External.requestKoreaStockPrice(code)
                .flatMap{ prices =>
                  StockRepoAccessor.insertBatchPrice[Future](Country.KOREA, prices).run(stockDb)
                }.recover{case e => replyTo ! NotComplete(e)}
                .foreach(_ => replyTo ! Complete)

              Behaviors.same

            case CollectUsaStock(code, replyTo) =>
              External.requestUsaStockPrice(code).flatMap{ prices =>
                StockRepoAccessor.insertBatchPrice[Future](Country.USA, prices).run(stockDb)
              }.recover{case e => replyTo ! NotComplete(e)}
                .foreach(_ => replyTo ! Complete)

              Behaviors.same

            case CollectKoreaStocks(replyTo) =>
              replyTo.map(_.tell(Reply))
              context.pipeToSelf {
                for{
                  nowStocksList <- Future.sequence(Set(External.requestKoreaEtfStockList,
                    External.requestKoreaMarketStockList(Market.KOSPI),
                    External.requestKoreaMarketStockList(Market.KOSDAQ)))
                  nowStocks = nowStocksList.foldLeft(Set.empty[Stock])((r, stocks) => r ++ stocks)
                  _ <- refreshStockList(Country.KOREA, nowStocks).run(stockDb)
                } yield {
                  val failList = ListBuffer.empty[String]
                  Source(nowStocks).zipWithIndex.mapAsync(8) { case (stock, index) =>
                    println(s"${index} ${stock}")
                    External.requestKoreaStockPrice(stock.code).map(prices=>(index, prices)).recover{case _ =>
                      failList += stock.code
                      (index, Seq.empty[Price])
                    }
                  }.buffer(24, OverflowStrategy.backpressure).mapAsync(2){ case (index, prices) =>
                    println(s"${index} db")
                    StockRepoAccessor.insertBatchPrice[Future](Country.KOREA, prices).run(stockDb).recover{case _ =>
                      prices.headOption.foreach(price => failList += price.code)
                    }
                  }.runWith(Sink.ignore).onComplete{
                    case Success(_) => Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("한국 batch 성공", failList.toString, JsNull))
                    case Failure(exception) => Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("한국 batch 실패", exception.getMessage, JsNull))
                  }
                }
              }{
                case Success(_) => SuccessStockListBatch(Country.KOREA)
                case Failure(exception) => throw exception
              }
              stash
            case CollectUsaStocks(replyTo) =>
              replyTo.map(_.tell(Reply))
              context.pipeToSelf {
                for{
                  nowStocksList <- Future.sequence(Set(External.requestUsaMarketStockList(Market.NASDAQ),
                    External.requestUsaMarketStockList(Market.NYSE),
                    External.requestUsaMarketStockList(Market.AMEX),
                    External.requestUsaEtfStockList))
                  nowStocks = nowStocksList.foldLeft(Set.empty[Stock])((r, stocks) => r ++ stocks)
                  _ <- refreshStockList(Country.USA, nowStocks).run(stockDb)
                } yield {
                  val failList = ListBuffer.empty[String]
                  Source(nowStocks).zipWithIndex.mapAsync(8) { case (stock, index) =>
                    println(s"${index} ${stock}")
                    External.requestUsaStockPrice(stock.code).map(prices=>(index, prices)).recover{case _ =>
                      failList += stock.code
                      (index, Seq.empty[Price])
                    }
                  }.buffer(24, OverflowStrategy.backpressure).mapAsync(2){ case (index, prices) =>
                    println(s"${index} db")
                    StockRepoAccessor.insertBatchPrice[Future](Country.USA, prices).run(stockDb).recover{case _ =>
                      prices.headOption.foreach(price => failList += price.code)
                    }
                  }.runWith(Sink.ignore).onComplete{
                    case Success(_) => Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("미국 batch 성공", failList.toString, JsNull))
                    case Failure(exception) =>
                      println(exception)
                      Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("미국 batch 실패", exception.getMessage, JsNull))
                  }
                }
              }{
                case Success(_) => SuccessStockListBatch(Country.USA)
                case Failure(exception) => throw exception
              }
              stash
            case _ =>
              Behaviors.same
          }

          def stash:Behavior[Command] = Behaviors.receiveMessage {
            case SuccessStockListBatch(country) =>
              country match {
                case Country.KOREA => setDailyTimer(CollectKoreaStocks(None), 0)
                case Country.USA => setDailyTimer(CollectUsaStocks(None), 8)
              }
              buffer.unstashAll(ing)
            case other =>
              buffer.stash(other)
              Behaviors.same
          }
          ing
        }
      }
    }.onFailure[Exception](SupervisorStrategy.restart)
  }

}
