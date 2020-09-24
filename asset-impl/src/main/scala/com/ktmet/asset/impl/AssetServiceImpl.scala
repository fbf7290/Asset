package com.ktmet.asset.impl

import java.net.URLDecoder

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.asset.collector.api.{CollectorService, Stock}
import com.ktmet.asset.api.{AssetService, AutoCompleteMessage}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext
import akka.actor.typed.scaladsl.adapter._
import com.ktmet.asset.impl.actor.StockAutoCompleter
import akka.actor.typed.scaladsl.AskPattern._
import com.ktmet.asset.impl.actor.StockAutoCompleter.SearchResponse
import com.lightbend.lagom.scaladsl.api.transport.ResponseHeader
import com.lightbend.lagom.scaladsl.server.ServerServiceCall






class AssetServiceImpl(protected val clusterSharding: ClusterSharding,
                       protected val system: ActorSystem,
                       protected val wsClient: WSClient)
                      (implicit protected val  ec: ExecutionContext
                       , implicit protected val timeout:Timeout
                       , implicit val collectorService: CollectorService) extends UserServiceImplPart {

  val stockAutoCompleter = system.spawn(StockAutoCompleter(), "stockAutoCompleter")

  override def autoCompleteStock(prefix:String): ServiceCall[NotUsed, AutoCompleteMessage] = authenticate{ userId =>
    ServerServiceCall{ (_,_) =>
      stockAutoCompleter.ask[StockAutoCompleter.Response](reply => StockAutoCompleter.Search(URLDecoder.decode(prefix, "UTF-8"), reply))
        .collect{
          case StockAutoCompleter.SearchResponse(koreaStocks, usaStocks) =>
            (ResponseHeader.Ok.withStatus(200), AutoCompleteMessage(koreaStocks.toSeq, usaStocks.toSeq))
          case StockAutoCompleter.Initializing =>
            (ResponseHeader.Ok.withStatus(204), AutoCompleteMessage.empty)
        }
    }
  }

}
