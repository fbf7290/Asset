package com.asset.collector.impl

import java.nio.file.Paths
import java.util.Calendar

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.util.{ByteString, Timeout}
import com.asset.collector.api.{CollectorService, CollectorSettings, Country, Market, NaverEtfListResponse, Price, Stock, Test}
import com.asset.collector.impl.repo.stock.{StockRepo, StockRepoAccessor}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.transport.ResponseHeader
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraSession
import com.lightbend.lagom.scaladsl.server.ServerServiceCall
import org.jsoup.Jsoup
import play.api.libs.ws.WSClient
import yahoofinance.YahooFinance
import yahoofinance.histquotes.Interval

import collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import cats.instances.future._
import com.asset.collector.api.Exception.ExternalResourceException
import com.asset.collector.api.Market.Market
import com.asset.collector.impl.acl.External
import com.lightbend.lagom.internal.persistence.cluster.ClusterStartupTask
import play.api.libs.json.{JsNull, Json}
import akka.actor.typed.scaladsl.adapter._
import com.asset.collector.impl.actor.BatchActor
import akka.actor.typed.scaladsl.AskPattern._
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import cats.data.OptionT
import com.asset.collector.impl.Fcm.FcmMessage

import scala.concurrent.duration._
import scala.util.{Failure, Success}


class CollectorServiceImpl(val system: ActorSystem, val wsClient: WSClient, val cassandraSession: CassandraSession)
      (implicit ec: ExecutionContext, timeout:Timeout,  materializer: Materializer) extends CollectorService {

  implicit val wsC = wsClient
  implicit val typedSystem = system.toTyped

  val stockDb = StockRepo(cassandraSession)

  val batchActor = system.spawn(BatchActor(stockDb), "batchActor")

  ClusterStartupTask(system, "Init", () => {
    (StockRepoAccessor.createStockTable(Country.KOREA).run(stockDb) zip
      StockRepoAccessor.createStockTable(Country.USA).run(stockDb) zip
      StockRepoAccessor.createPriceTable(Country.KOREA).run(stockDb) zip
      StockRepoAccessor.createPriceTable(Country.USA).run(stockDb)).map(_ => Done)
  }, 60.seconds, None, 3.seconds, 30.seconds, 0.2)


  override def requestBatchKoreaStock: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Reply.type](reply => BatchActor.CollectKoreaStock(Some(reply)))
      .map(_ => (ResponseHeader.Ok.withStatus(200), Done))
  }

  override def requestBatchUsaStock: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Reply.type](reply => BatchActor.CollectUsaStock(Some(reply)))
      .map(_ => (ResponseHeader.Ok.withStatus(200), Done))
  }

  override def getKoreaStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    StockRepoAccessor.selectStocks(Country.KOREA).run(stockDb)
      .map(stocks => (ResponseHeader.Ok.withStatus(200), stocks))
  }

  override def getUsaStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    StockRepoAccessor.selectStocks(Country.USA).run(stockDb)
      .map(stocks => (ResponseHeader.Ok.withStatus(200), stocks))
  }
}