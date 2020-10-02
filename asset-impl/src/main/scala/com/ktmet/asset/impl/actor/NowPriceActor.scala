package com.ktmet.asset.impl.actor

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.asset.collector.api.Country.Country
import com.asset.collector.api.{CollectorService, Country, NowPrice}
import com.ktmet.asset.common.api.Timestamp

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object NowPriceActor {

  sealed trait Command
  case class NowPrices(country: Country, prices:Map[String, NowPrice]) extends Command
  case object CollectTimer extends Command
  case class GetPrice(code:String, replyTo:ActorRef[Response]) extends Command

  sealed trait Response
  case class PriceResponse(price:NowPrice) extends Response
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

          collectKoreaPrices
          collectUsaPrices

          timers.startTimerWithFixedDelay(CollectTimer, 1.minutes)

          def init(koreaPrices:Map[String, NowPrice]
                   , usaPrices:Map[String, NowPrice]):Behavior[Command] =
            Behaviors.receiveMessage{
              case NowPrices(country, prices) =>
                println(country)
                country match {
                  case Country.KOREA =>
                    if(usaPrices.isEmpty) init(prices, usaPrices)
                    else {
                      buffer.unstashAll(ing(prices, usaPrices))
                    }
                  case Country.USA =>
                    if(koreaPrices.isEmpty) init(koreaPrices, prices)
                    else {
                      buffer.unstashAll(ing(koreaPrices, prices))
                    }
                }
              case other =>
                buffer.stash(other)
                Behaviors.same
            }

          def ing(koreaPrices:Map[String, NowPrice]
                   , usaPrices:Map[String, NowPrice]):Behavior[Command] =
            Behaviors.receiveMessage {
              case NowPrices(country , prices) =>
                country match {
                  case Country.KOREA => ing(prices, usaPrices)
                  case Country.USA => ing(koreaPrices, prices)
                }
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
          init(Map.empty, Map.empty)
        }
      }
    }.onFailure[Exception](SupervisorStrategy.restart)
  }
}
