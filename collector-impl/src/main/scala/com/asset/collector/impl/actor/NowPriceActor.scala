package com.asset.collector.impl.actor

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.asset.collector.api.{Country, NowPrice, Stock}
import com.asset.collector.impl.actor.BatchActor.Command
import com.asset.collector.impl.repo.stock.{StockRepoAccessor, StockRepoTrait}
import play.api.libs.ws.WSClient
import cats.instances.future._
import com.asset.collector.api.Country.Country
import com.asset.collector.impl.acl.External
import com.ktmet.asset.common.api.Timestamp

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object NowPriceActor {
  sealed trait Command
  case class NowPrices(country: Country, prices:Seq[NowPrice]) extends Command
  case object CollectTimer extends Command
  case class GetPrices(country: Country, replyTo:ActorRef[Response]) extends Command
  case object InitKorea extends Command
  case object InitUsa extends Command

  sealed trait Response
  case class PricesResponse(prices:Map[String, NowPrice]) extends Response
  case object Initializing extends Response

  def apply(stockDb:StockRepoTrait[Future])
           (implicit wsClient: WSClient, ec: ExecutionContext
            , materializer: Materializer):Behavior[Command] = Behaviors.setup{ context =>
    Behaviors.supervise[Command]{
      Behaviors.withTimers[Command]{ timers =>

        def collectKoreaNowPrices = Source.future(StockRepoAccessor.selectStocks(Country.KOREA).run(stockDb))
          .flatMapConcat(iter => Source.fromIterator(() => iter.toIterator))
          .grouped(500)
          .mapAsync(1)(External.requestKoreaStocksNowPrice)
          .map(prices => context.self.tell(NowPrices(Country.KOREA, prices)))
          .runWith(Sink.ignore)




        def collectUsaNowPrices = Source.future(StockRepoAccessor.selectStocks(Country.USA).run(stockDb))
          .flatMapConcat(iter => Source.fromIterator(() => iter.toIterator))
          .grouped(500)
          .mapAsync(1)(External.requestUsaStocksNowPrice)
          .map(prices => context.self.tell(NowPrices(Country.USA, prices)))
          .runWith(Sink.ignore)

        collectKoreaNowPrices.onComplete{
          case Success(_) => context.self ! InitKorea
          case Failure(exception) => throw exception
        }
        collectUsaNowPrices.onComplete{
          case Success(_) => context.self ! InitUsa
          case Failure(exception) => throw exception
        }

        timers.startTimerWithFixedDelay(CollectTimer, 1.minutes)

        def init(koreaPrices:mutable.Map[String, NowPrice]
                , usaPrices:mutable.Map[String, NowPrice]
                , isFinishKoreaInit:Boolean
                , isFinishUsaInit:Boolean):Behavior[Command] = Behaviors.receiveMessage{
          case NowPrices(country, prices) =>
            StockRepoAccessor.insertBatchNowPrice(country, prices).run(stockDb)
            if(country == Country.KOREA) prices.map(price => koreaPrices += (price.code -> price))
            else if(country == Country.USA) prices.map(price => usaPrices += (price.code -> price))
            init(koreaPrices, usaPrices, isFinishKoreaInit, isFinishUsaInit)
          case InitKorea =>
            if(isFinishUsaInit) ing(koreaPrices, usaPrices, false, false)
            else init(koreaPrices, usaPrices, true, isFinishUsaInit)
          case InitUsa =>
            if(isFinishKoreaInit) ing(koreaPrices, usaPrices, false, false)
            else init(koreaPrices, usaPrices, isFinishKoreaInit, true)
          case GetPrices(_, replyTo) =>
            replyTo ! Initializing
            Behaviors.same
          case _ => Behaviors.same
        }

        def ing(koreaPrices:mutable.Map[String, NowPrice]
                 , usaPrices:mutable.Map[String, NowPrice]
               , isFinishKoreaDailyBatch:Boolean
               , isFinishUsaDailyBatch:Boolean):Behavior[Command] = Behaviors.receiveMessage{
          case NowPrices(country, prices) =>
            if(country == Country.KOREA) prices.map(price => koreaPrices += (price.code -> price))
            else if(country == Country.USA) prices.map(price => usaPrices += (price.code -> price))
            ing(koreaPrices, usaPrices, false, false)

          case CollectTimer =>
            Timestamp.nowHour match {
              case hour if hour < 7 =>
                collectUsaNowPrices
                Behaviors.same
              case hour if hour < 9 =>
                if(!isFinishUsaDailyBatch) {
                  StockRepoAccessor.insertBatchNowPrice(Country.USA, usaPrices.values.toSeq).run(stockDb)
                  ing(koreaPrices, usaPrices, isFinishKoreaDailyBatch, true)
                } else Behaviors.same
              case hour if hour < 16 =>
                collectKoreaNowPrices
                if(isFinishKoreaDailyBatch) ing(koreaPrices, usaPrices, false, isFinishUsaDailyBatch)
                else Behaviors.same
              case hour if hour < 22 =>
                if(!isFinishKoreaDailyBatch){
                  StockRepoAccessor.insertBatchNowPrice(Country.KOREA, usaPrices.values.toSeq).run(stockDb)
                  ing(koreaPrices, usaPrices, true, isFinishUsaDailyBatch)
                } else Behaviors.same
              case _ =>
                collectUsaNowPrices
                if(isFinishUsaDailyBatch) ing(koreaPrices, usaPrices, false, isFinishUsaDailyBatch)
                else Behaviors.same
            }

          case GetPrices(country, replyTo) =>
            country match {
              case Country.KOREA => replyTo ! PricesResponse(koreaPrices.toMap)
              case Country.USA => replyTo ! PricesResponse(usaPrices.toMap)
            }
            Behaviors.same

          case _ => Behaviors.same
        }

        init(mutable.Map.empty, mutable.Map.empty, false, false)
      }
    }.onFailure[Exception](SupervisorStrategy.restart)
  }
}
