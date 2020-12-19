package com.asset.collector.impl

import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.{Calendar, Date}

import akka.actor.ActorSystem
import akka.actor.typed.SupervisorStrategy
import akka.{Done, NotUsed}
import akka.util.{ByteString, Timeout}
import com.asset.collector.api.{ClosePrice, CollectorService, CollectorSettings, Country, FinnHubStock, KrwUsd, Market, NaverEtfListResponse, NowPrice, Price, Stock, Test}
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
import com.asset.collector.impl.actor.{BatchActor, NowPriceActor}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import cats.data.OptionT
import com.asset.collector.api.message.GettingClosePricesAfterDate
import com.asset.collector.impl.Fcm.FcmMessage

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success}


class CollectorServiceImpl(val system: ActorSystem
                           , val wsClient: WSClient
                           , val stockDb : StockRepo
                           , val cassandraSession: CassandraSession)
      (implicit ec: ExecutionContext, timeout:Timeout,  materializer: Materializer) extends CollectorService {

  implicit val wsC = wsClient
  implicit val typedSystem = system.toTyped


  val batchActor = system.spawn(BatchActor(stockDb), "batchActor")
  val nowPriceActor = ClusterSingleton(typedSystem).init(SingletonActor(NowPriceActor(stockDb), "nowPriceActor"))



  override def requestBatchKoreaStock: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Reply.type](reply => BatchActor.CollectKoreaStocks(Some(reply)))
      .map(_ => (ResponseHeader.Ok.withStatus(200), Done))
  }

  override def requestBatchUsaStock: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Reply.type](reply => BatchActor.CollectUsaStocks(Some(reply)))
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

  override def insertKoreaStockPrice(code:String): ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Response](reply => BatchActor.CollectKoreaStock(code, reply))
      .collect{
        case BatchActor.Complete => (ResponseHeader.Ok.withStatus(204), Done)
        case BatchActor.NotComplete(exception) => throw exception
      }
  }

  override def insertUsaStockPrice(code:String): ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Response](reply => BatchActor.CollectUsaStock(code, reply))
      .collect{
        case BatchActor.Complete => (ResponseHeader.Ok.withStatus(204), Done)
        case BatchActor.NotComplete(exception) => throw exception
      }
  }

  override def getKoreaNowPrices: ServiceCall[NotUsed, Map[String, NowPrice]] = ServerServiceCall { (_, _) =>
    nowPriceActor.ask[NowPriceActor.Response](reply => NowPriceActor.GetPrices(Country.KOREA, reply))
      .flatMap{
        case NowPriceActor.PricesResponse(prices) => Future.successful(ResponseHeader.Ok.withStatus(200), prices)
        case NowPriceActor.Initializing =>
          StockRepoAccessor.selectNowPrices(Country.KOREA).run(stockDb)
            .map(prices => prices.foldLeft(mutable.Map.empty[String,NowPrice])((map, price) => map + (price.code -> price)))
            .map(prices => (ResponseHeader.Ok.withStatus(200), prices.toMap))
        case _ => ???
      }
  }

  override def getUsaNowPrices: ServiceCall[NotUsed, Map[String, NowPrice]] = ServerServiceCall { (_, _) =>
    nowPriceActor.ask[NowPriceActor.Response](reply => NowPriceActor.GetPrices(Country.USA, reply))
      .flatMap{
        case NowPriceActor.PricesResponse(prices) => Future.successful(ResponseHeader.Ok.withStatus(200), prices)
        case NowPriceActor.Initializing =>
          StockRepoAccessor.selectNowPrices(Country.USA).run(stockDb)
            .map(prices => prices.foldLeft(mutable.Map.empty[String,NowPrice])((map, price) => map + (price.code -> price)))
            .map(prices => (ResponseHeader.Ok.withStatus(200), prices.toMap))
        case _ => ???
      }
  }

  override def getNowKrwUsd: ServiceCall[NotUsed, KrwUsd] = ServerServiceCall{ (_, _) =>
    nowPriceActor.ask[NowPriceActor.Response](reply => NowPriceActor.GetKrwUsd(reply))
      .collect{
        case NowPriceActor.KrwUsdResponse(krwUsd) => (ResponseHeader.Ok.withStatus(200), krwUsd)
        case NowPriceActor.Initializing => (ResponseHeader.Ok.withStatus(200), KrwUsd.empty)
      }
  }

  override def requestBatchKrwUsd: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Reply.type](reply => BatchActor.CollectKrwUsd(Some(reply)))
      .map(_ => (ResponseHeader.Ok.withStatus(200), Done))
  }

  override def getClosePricesAfterDate1: ServiceCall[GettingClosePricesAfterDate, Seq[ClosePrice]] =
    ServerServiceCall { (_, gettingClosePricesAfterDate) =>
      StockRepoAccessor.selectClosePricesAfterDate1(gettingClosePricesAfterDate.stock
        , gettingClosePricesAfterDate.date).run(stockDb)
        .map(prices => (ResponseHeader.Ok.withStatus(200), prices))
    }

  override def getClosePricesAfterDate: ServiceCall[GettingClosePricesAfterDate, Source[ClosePrice, NotUsed]] =
    ServerServiceCall { (_, gettingClosePricesAfterDate) =>
      Future.successful(ResponseHeader.Ok.withStatus(200)
        , StockRepoAccessor.selectClosePricesAfterDate(stockDb, gettingClosePricesAfterDate.stock
        , gettingClosePricesAfterDate.date))
    }


  override def getKrwUsdsAfterDate(date: String): ServiceCall[NotUsed, Seq[KrwUsd]] =
    ServerServiceCall { (_, _) =>
      StockRepoAccessor.selectKrwUsdsAfterDate(date).run(stockDb)
        .map(krwUsds => (ResponseHeader.Ok.withStatus(200), krwUsds))
    }
}