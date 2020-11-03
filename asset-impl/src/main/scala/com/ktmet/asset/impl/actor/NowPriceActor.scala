package com.ktmet.asset.impl.actor

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.asset.collector.api.Country.Country
import com.asset.collector.api.{CollectorService, Country, KrwUsd, NowPrice, Stock}
import com.ktmet.asset.common.api.Timestamp

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object NowPriceActor {

  sealed trait Command
  case class NowPrices(country: Country, prices:Map[String, NowPrice]) extends Command
  case class NowKrwUsd(krwUsd:KrwUsd) extends Command
  case object CollectTimer extends Command
  case class GetPrice(code:String, replyTo:ActorRef[Response]) extends Command
  case class GetKrwUsd(replyTo:ActorRef[Response]) extends Command
  case class GetPrices(stocks: Seq[Stock], replyTo: ActorRef[PricesResponse]) extends Command
  case class GetPricesAndKrwUsd(stocks: Seq[Stock], replyTo: ActorRef[PricesAndKrwUsdResponse]) extends Command

  sealed trait Response
  case class PriceResponse(price:NowPrice) extends Response
  case class PricesResponse(prices: Map[Stock, Option[NowPrice]]) extends Response
  case class KrwUsdResponse(krwUsd: KrwUsd) extends Response
  case class PricesAndKrwUsdResponse(prices: Map[Stock, Option[NowPrice]], krwUsd: KrwUsd) extends Response
  case object NotFoundStock extends Response

  def apply()(implicit collectorService: CollectorService
              , ec: ExecutionContext):Behavior[Command] = Behaviors.setup { context =>
    Behaviors.supervise[Command] {
      Behaviors.withStash(100){ buffer =>
        Behaviors.withTimers[Command] { timers =>

          def collectKoreaPrices = collectorService.getKoreaNowPrices.invoke(NotUsed)
            .foreach(response => context.self ! NowPrices(Country.KOREA, response))

          def collectUsaPrices = collectorService.getUsaNowPrices.invoke(NotUsed)
            .foreach(response => context.self ! NowPrices(Country.USA, response))

          def collectKrwUsd = collectorService.getNowKrwUsd.invoke(NotUsed)
              .foreach(response => context.self ! NowKrwUsd(response))

          collectKoreaPrices
          collectUsaPrices
          collectKrwUsd

          timers.startTimerWithFixedDelay(CollectTimer, 1.minutes)

          def init(koreaPrices:Map[String, NowPrice]
                   , usaPrices:Map[String, NowPrice]
                  , krwUsd: KrwUsd):Behavior[Command] =
            Behaviors.receiveMessage{
              case NowPrices(country, prices) =>
                country match {
                  case Country.KOREA =>
                    if(usaPrices.isEmpty) init(prices, usaPrices, krwUsd)
                    else {
                      buffer.unstashAll(ing(prices, usaPrices, krwUsd))
                    }
                  case Country.USA =>
                    if(koreaPrices.isEmpty) init(koreaPrices, prices, krwUsd)
                    else {
                      buffer.unstashAll(ing(koreaPrices, prices, krwUsd))
                    }
                }
              case NowKrwUsd(krwUsd) =>
                init(koreaPrices, usaPrices, krwUsd)
              case other =>
                buffer.stash(other)
                Behaviors.same
            }

          def ing(koreaPrices:Map[String, NowPrice]
                   , usaPrices:Map[String, NowPrice]
                    , krwUsd: KrwUsd):Behavior[Command] =
            Behaviors.receiveMessage {
              case NowPrices(country , prices) =>
                country match {
                  case Country.KOREA => ing(prices, usaPrices, krwUsd)
                  case Country.USA => ing(koreaPrices, prices, krwUsd)
                }
              case NowKrwUsd(krwUsd) =>
                ing(koreaPrices, usaPrices, krwUsd)
              case GetPrice(code, replyTo) =>
                koreaPrices.get(code.toUpperCase) match {
                  case Some(price) => replyTo ! PriceResponse(price)
                  case None =>
                    usaPrices.get(code.toUpperCase) match {
                      case Some(price) => replyTo ! PriceResponse(price)
                      case None => replyTo ! NotFoundStock
                    }
                }
                Behaviors.same
              case GetPrices(stocks, replyTo) =>
                replyTo ! PricesResponse(stocks.foldLeft(Map.empty[Stock, Option[NowPrice]])((res, stock) =>
                  res + (stock -> koreaPrices.get(stock.code.toUpperCase))))
                Behaviors.same
              case GetKrwUsd(replyTo) =>
                replyTo ! KrwUsdResponse(krwUsd)
                Behaviors.same

              case GetPricesAndKrwUsd(stocks, replyTo) =>
                replyTo ! PricesAndKrwUsdResponse(
                  stocks.foldLeft(Map.empty[Stock, Option[NowPrice]])((res, stock) =>
                    res + (stock -> koreaPrices.get(stock.code.toUpperCase)))
                  , krwUsd
                )
                Behaviors.same

              case CollectTimer =>
                Timestamp.nowHour match {
                  case hour if hour < 7 =>
                    collectUsaPrices
                  case hour if 9 <= hour && hour < 16 =>
                    collectKoreaPrices
                  case hour if 22 <= hour =>
                    collectUsaPrices
                  case _ => ()
                }
                Behaviors.same
            }
          init(Map.empty, Map.empty, KrwUsd.empty)
        }
      }
    }.onFailure[Exception](SupervisorStrategy.restart)
  }
}
