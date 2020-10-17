package com.asset.collector.impl.actor

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import com.asset.collector.api.{Country, KrwUsd, NowPrice, Stock}
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
  case class NowKrwUsd(krwUsd: KrwUsd) extends Command
  case object CollectTimer extends Command
  case class GetPrices(country: Country, replyTo:ActorRef[Response]) extends Command
  case class GetKrwUsd(replyTo:ActorRef[Response]) extends Command
  case object InitKorea extends Command
  case object InitUsa extends Command

  sealed trait Response
  case class PricesResponse(prices:Map[String, NowPrice]) extends Response
  case class KrwUsdResponse(krwUsd: KrwUsd) extends Response
  case object Initializing extends Response

  def apply(stockDb:StockRepoTrait[Future])
           (implicit wsClient: WSClient, ec: ExecutionContext
            , materializer: Materializer):Behavior[Command] = Behaviors.setup{ context =>
    Behaviors.supervise[Command]{
      Behaviors.withTimers[Command]{ timers =>

        def collectKoreaNowPrices = Source.future(StockRepoAccessor.selectStocks(Country.KOREA).run(stockDb))
          .flatMapConcat(iter => Source.fromIterator(() => iter.toIterator))
          .grouped(500).buffer(2, OverflowStrategy.backpressure)
          .mapAsync(1)(External.requestKoreaStocksNowPrice)
          .map(prices => context.self.tell(NowPrices(Country.KOREA, prices)))
          .runWith(Sink.ignore)

        def collectUsaNowPrices = Source.future(StockRepoAccessor.selectStocks(Country.USA).run(stockDb))
          .flatMapConcat(iter => Source.fromIterator(() => iter.toIterator))
          .grouped(500).buffer(2, OverflowStrategy.backpressure)
          .mapAsync(2)(External.requestUsaStocksNowPrice)
          .map(prices => context.self.tell(NowPrices(Country.USA, prices)))
          .runWith(Sink.ignore)

        def collectNowKrwUsd = External.requestNowKrwUsd.map(krwUsd => context.self.tell(NowKrwUsd(krwUsd)))


        collectKoreaNowPrices.onComplete{
          case Success(_) => context.self ! InitKorea
          case Failure(exception) => throw exception
        }
        collectUsaNowPrices.onComplete{
          case Success(_) => context.self ! InitUsa
          case Failure(exception) => throw exception
        }
        collectNowKrwUsd

        timers.startTimerWithFixedDelay(CollectTimer, 1.minutes)

        def init(koreaPrices:mutable.Map[String, NowPrice]
                , usaPrices:mutable.Map[String, NowPrice]
                , krwUsd: KrwUsd
                , isFinishKoreaInit:Boolean
                , isFinishUsaInit:Boolean):Behavior[Command] = Behaviors.receiveMessage{
          case NowPrices(country, prices) =>
            StockRepoAccessor.insertBatchNowPrice(country, prices).run(stockDb)
            if(country == Country.KOREA) prices.map(price => koreaPrices += (price.code -> price))
            else if(country == Country.USA) prices.map(price => usaPrices += (price.code -> price))
            init(koreaPrices, usaPrices, krwUsd, isFinishKoreaInit, isFinishUsaInit)
          case NowKrwUsd(krwUsd) =>
            init(koreaPrices, usaPrices, krwUsd, isFinishKoreaInit, isFinishUsaInit)
          case InitKorea =>
            if(isFinishUsaInit) ing(koreaPrices, usaPrices, krwUsd, false, false)
            else init(koreaPrices, usaPrices, krwUsd, true, isFinishUsaInit)
          case InitUsa =>
            if(isFinishKoreaInit) ing(koreaPrices, usaPrices, krwUsd, false, false)
            else init(koreaPrices, usaPrices, krwUsd, isFinishKoreaInit, true)
          case GetPrices(_, replyTo) =>
            replyTo ! Initializing
            Behaviors.same
          case GetKrwUsd(replyTo) =>
            replyTo ! Initializing
            Behaviors.same
          case _ => Behaviors.same
        }

        def ing(koreaPrices:mutable.Map[String, NowPrice]
                 , usaPrices:mutable.Map[String, NowPrice]
               , krwUsd: KrwUsd
               , isFinishKoreaDailyBatch:Boolean
               , isFinishUsaDailyBatch:Boolean):Behavior[Command] = Behaviors.receiveMessage{
          case NowPrices(country, prices) =>
            if(country == Country.KOREA) prices.map(price => koreaPrices += (price.code -> price))
            else if(country == Country.USA) prices.map(price => usaPrices += (price.code -> price))
            ing(koreaPrices, usaPrices, krwUsd, isFinishKoreaDailyBatch, isFinishUsaDailyBatch)
          case NowKrwUsd(krwUsd) =>
            ing(koreaPrices, usaPrices, krwUsd, isFinishKoreaDailyBatch, isFinishUsaDailyBatch)
          case CollectTimer =>
            Timestamp.nowHour match {
              case hour if hour < 7 =>
                collectUsaNowPrices
                Behaviors.same
              case hour if hour < 9 =>
                if(hour == 8) collectNowKrwUsd
                if(!isFinishUsaDailyBatch) {
                  StockRepoAccessor.insertBatchNowPrice(Country.USA, usaPrices.values.toSeq).run(stockDb)
                  ing(koreaPrices, usaPrices, krwUsd, isFinishKoreaDailyBatch, true)
                } else Behaviors.same
              case hour if hour < 16 =>
                collectKoreaNowPrices
                collectNowKrwUsd
                if(isFinishKoreaDailyBatch) ing(koreaPrices, usaPrices, krwUsd, false, isFinishUsaDailyBatch)
                else Behaviors.same
              case hour if hour < 22 =>
                collectNowKrwUsd
                if(!isFinishKoreaDailyBatch){
                  StockRepoAccessor.insertBatchNowPrice(Country.KOREA, usaPrices.values.toSeq).run(stockDb)
                  ing(koreaPrices, usaPrices, krwUsd, true, isFinishUsaDailyBatch)
                } else Behaviors.same
              case _ =>
                collectUsaNowPrices
                if(isFinishUsaDailyBatch) ing(koreaPrices, usaPrices, krwUsd, false, isFinishUsaDailyBatch)
                else Behaviors.same
            }

          case GetPrices(country, replyTo) =>
            country match {
              case Country.KOREA => replyTo ! PricesResponse(koreaPrices.toMap)
              case Country.USA => replyTo ! PricesResponse(usaPrices.toMap)
            }
            Behaviors.same

          case GetKrwUsd(replyTo) =>
            replyTo ! KrwUsdResponse(krwUsd)
            Behaviors.same

          case _ => Behaviors.same
        }

        init(mutable.Map.empty, mutable.Map.empty, KrwUsd.empty, false, false)
      }
    }.onFailure[Exception](SupervisorStrategy.restart)
  }
}
