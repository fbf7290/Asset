package com.ktmet.asset.impl.actor

import akka.NotUsed
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import com.asset.collector.api.{CollectorService, Stock}
import com.rklaehn.radixtree.RadixTree

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}

object StockAutoCompleter {

  sealed trait Command
  case class InitState(koreaStocks:Seq[Stock], usaStocks:Seq[Stock]) extends Command
  case class Search(prefix:String, replyTo:ActorRef[Response]) extends Command

  sealed trait Response
  case object Initializing extends Response
  case class SearchResponse(koreaStocks:Traversable[Stock], usaStocks:Traversable[Stock]) extends Response

  def apply()(implicit collectorService: CollectorService):Behavior[Command] =
    Behaviors.setup[Command]{ context =>
      Behaviors.supervise[Command]{
        def init:Behavior[Command] = {
          context.pipeToSelf{
            collectorService.getKoreaStockList.invoke(NotUsed) zip
              collectorService.getUsaStockList.invoke(NotUsed)
          }{
            case Success(value) =>  InitState(value._1, value._2)
            case Failure(exception) => throw exception
          }

          Behaviors.receiveMessage{
            case InitState(koreaStocks, usaStocks) =>
              def stocksToRadix(stocks:Seq[Stock]) = {
                val buffer = ListBuffer.empty[(String, Stock)]
                stocks.map{ stock =>
                  buffer += (stock.code -> stock)
                  buffer += (stock.name -> stock)
                }
                RadixTree(buffer: _*)
              }
              ing(stocksToRadix(koreaStocks), stocksToRadix(usaStocks))
            case Search(_, replyTo) =>
              replyTo ! Initializing
              Behaviors.same
            case _ => Behaviors.same
          }
        }

        def ing(koreaStocks: RadixTree[String, Stock], usaStocks:RadixTree[String, Stock]):Behavior[Command] = Behaviors.receiveMessage{
          case Search(prefix, replyTo) =>
            replyTo ! SearchResponse(koreaStocks.filterPrefix(prefix.toUpperCase).values.take(100), usaStocks.filterPrefix(prefix.toUpperCase).values.take(100))
            Behaviors.same
          case _ => Behaviors.same
        }

        init
      }.onFailure[Exception](SupervisorStrategy.restart)
    }
}
