package com.asset.collector.impl

import java.nio.file.Paths
import java.util.Calendar

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.util.{ByteString, Timeout}
import com.asset.collector.api.{CollectorService, CollectorSettings, Country, Market, NaverEtfListResponse, NaverStockIndex09, Price, Stock, Test}
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
      StockRepoAccessor.createStockIndex09Table.run(stockDb) zip
      StockRepoAccessor.createPriceTable(Country.USA).run(stockDb)).map(_ => Done)
  }, 60.seconds, None, 3.seconds, 30.seconds, 0.2)

  override def test: ServiceCall[Test, Done] = ServerServiceCall {
    (_, a) =>
      println(a)
      Future.successful(ResponseHeader.Ok.withStatus(200), Done)
  }


  override def getUsaPrice: ServiceCall[NotUsed, Done] =
    ServerServiceCall { (requestHeader, _) =>
      val to = Calendar.getInstance();
      val from = Calendar.getInstance()
      from.add(Calendar.YEAR, -10)
      // ^KS11(코스피), ^KQ11(코스닥), ^IXIC(나스닥), ^DJI(다우존스), ^GSPC(S&P500),
      //      println(YahooFinance.get("USDKRW=X", from, to, Interval.DAILY))
      //      println(YahooFinance.get("QQQ", from, to, Interval.DAILY).getDividendHistory)
      //      println(YahooFinance.get("005930.KS", from, Interval.DAILY).getHistory.get(0).getAdjClose)

      YahooFinance.get("005930.KS", from, Interval.DAILY).getHistory.asScala.foreach {
        st => println(s"${st.getDate}_${st.getClose}_${st.getAdjClose}")
      }

      Future.successful(ResponseHeader.Ok.withStatus(200), Done)
    }

  override def getKoreaEtfList: ServiceCall[NotUsed, Done] =
    ServerServiceCall { (requestHeader, _) =>
      wsClient.url("https://finance.naver.com/api/sise/etfItemList.nhn").get().map {
        response =>
          println(response.body)
          (ResponseHeader.Ok.withStatus(200), Done)
      }
    }

  override def getKoreaStockList: ServiceCall[NotUsed, Done] =
    ServerServiceCall { (requestHeader, _) =>
      wsClient.url("http://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=kospiMkt").get().map {
        response =>
          val a = Jsoup.parseBodyFragment(response.body).body().getElementsByTag("tr")
          for (d <- a.asScala) {
            val c = d.getElementsByTag("td").asScala
            //            if(c.size != 0) println(s"${c(0).text} ${c(1).text}")
            for (b <- c) {
              println(b.text())
            }
          }
          (ResponseHeader.Ok.withStatus(200), Done)
      }
    }


  override def getUsaStockList: ServiceCall[NotUsed, Done] =
    ServerServiceCall { (requestHeader, _) =>
      wsClient.url("https://dumbstockapi.com/stock?exchanges=NYSE").get.map {
        response =>
          println(response.body)
          (ResponseHeader.Ok.withStatus(200), Done)
      }
    }

  override def getKoreaEtfStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    External.requestKoreaEtfStockList.map { stockList =>
      (ResponseHeader.Ok.withStatus(200), stockList)
    }
  }

  override def getKospiStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    External.requestKoreaMarketStockList(Market.KOSPI).map { stockList =>
      (ResponseHeader.Ok.withStatus(200), stockList)
    }
  }

  override def getKosdaqStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    External.requestKoreaMarketStockList(Market.KOSDAQ).map { stockList =>
      (ResponseHeader.Ok.withStatus(200), stockList)
    }
  }

  override def getKoreaStockPrices(code: String): ServiceCall[NotUsed, Seq[Price]] = ServerServiceCall { (_, _) =>
    External.requestKoreaStockPrice(code).map { priceList =>
      (ResponseHeader.Ok.withStatus(200), priceList)
    }

  }


  override def requestBatchKoreaStock: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Reply.type](reply => BatchActor.CollectKoreaStock(Some(reply)))
      .map(_ => (ResponseHeader.Ok.withStatus(200), Done))
  }

  override def getNasdaqStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    External.requestUsaMarketStockList(Market.NASDAQ).map { stockList =>
      (ResponseHeader.Ok.withStatus(200), stockList)
    }
  }

  override def getNyseStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    External.requestUsaMarketStockList(Market.NYSE).map { stockList =>
      (ResponseHeader.Ok.withStatus(200), stockList)
    }
  }

  override def getAmexStockList: ServiceCall[NotUsed, Seq[Stock]] = ServerServiceCall { (_, _) =>
    External.requestUsaMarketStockList(Market.AMEX).map { stockList =>
      (ResponseHeader.Ok.withStatus(200), stockList)
    }
  }

  override def getUsaStockPrices(code: String): ServiceCall[NotUsed, Seq[Price]] = ServerServiceCall { (_, _) =>
    External.requestUsaStockPrice(code).map { stockList =>
      (ResponseHeader.Ok.withStatus(200), stockList)
    }
  }

  override def requestBatchUsaStock: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>
    batchActor.ask[BatchActor.Reply.type](reply => BatchActor.CollectUsaStock(Some(reply)))
      .map(_ => (ResponseHeader.Ok.withStatus(200), Done))
  }

  override def collectNaverStockIndexes: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>

    for {
      stockList <- (External.requestKoreaMarketStockList(Market.KOSPI) zip External.requestKoreaMarketStockList(Market.KOSDAQ)).map(r => r._2 ++ r._1)
    } yield {
      val failList = ListBuffer.empty[String]
      Source(stockList.toList.zipWithIndex).mapAsync(4) { case (stock, index) =>
        println(index)
        println(stock.code)
        wsClient.url(s"https://finance.naver.com/item/main.nhn?code=${stock.code}").get()
          .flatMap { response =>
            var datas = ListBuffer.empty[NaverStockIndex09]
            for (i <- List(2, 6, 7, 8, 12, 13, 14, 15)) {
              val category = Jsoup.parseBodyFragment(response.body).body().select(s"table.tb_type1_ifrs > tbody > tr:eq(${i}) > th").text.trim
              val indexes = Jsoup.parseBodyFragment(response.body).body().select(s"table.tb_type1_ifrs > tbody > tr:eq(${i}) > td").asScala.toList.map(_.text.trim)
              datas += NaverStockIndex09(stock.code, category, indexes: _*)
            }
            StockRepoAccessor.insertBatchStockIndex09(datas.toList).run(stockDb)
          }.recover { case e =>
          println(e)
          failList += stock.code
        }
      }.runWith(Sink.ignore).onComplete {
        case Success(_) =>
          println(failList)
          Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 batch 성공", failList.toString, JsNull))
        case Failure(exception) => Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 batch 실패", exception.getMessage, JsNull))
      }
    }

    Future.successful((ResponseHeader.Ok.withStatus(200), Done))
  }

  // 3년 연간 당기 수익율 > 0 - 2018<2018<2019<2020
  // 1분기 당기 순이익 모두 +
  // 부채비율 100프로 이하
  // 당좌 비율 100 이상
  override def filterStock: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>

    def filterIndex(index: String, filterFunc: Float => Boolean): Boolean = {
      try{
        filterFunc(index.filterNot(_ == ',').toFloat)
      }catch{ case _:Exception =>
        true
      }
    }

    def compareProfit(yIndex201712:String,yIndex201812:String,yIndex201912:String,yIndex202012E:String):Boolean ={
      try{
        if(yIndex201712.filterNot(_ == ',').toFloat<=yIndex201812.filterNot(_ == ',').toFloat
          && yIndex201812.filterNot(_ == ',').toFloat<=yIndex201912.filterNot(_ == ',').toFloat
          && yIndex201912.filterNot(_ == ',').toFloat<=yIndex202012E.filterNot(_ == ',').toFloat
        ) true else false
      }catch{ case _:Exception =>
        true
      }
    }

    for {
      stockList <- (External.requestKoreaMarketStockList(Market.KOSPI) zip External.requestKoreaMarketStockList(Market.KOSDAQ)).map(r => r._2 ++ r._1)
    } yield {
      val file = Paths.get("success.txt")
      val failList = ListBuffer.empty[String]
      val successList = ListBuffer.empty[String]
      val filterList = ListBuffer.empty[String]
      Source(stockList.toList.zipWithIndex).mapAsync(20) { case (stock, index) =>
        println(index)

        StockRepoAccessor.selectStockIndex09(stock.code, "당기순이익").run(stockDb).flatMap {
          case Some(stockIndex) =>
            if (filterIndex(stockIndex.yIndex201712, _ > 0)
              && filterIndex(stockIndex.yIndex201812, _ > 0)
              && filterIndex(stockIndex.yIndex201912, _ > 0)
              && filterIndex(stockIndex.yIndex202012E, _ > 0)
              && compareProfit(stockIndex.yIndex201712, stockIndex.yIndex201812, stockIndex.yIndex201912, stockIndex.yIndex202012E)
            ) {
              var cnt = 0
              if(filterIndex(stockIndex.qIndex201906, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex201909, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex201912, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex202003, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex202006, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex202009E, _ < 0)) cnt = cnt+1

              if(cnt>0) {
                filterList += stockIndex.code
                Future.successful(None)
              }
              else{
                StockRepoAccessor.selectStockIndex09(stock.code, "부채비율").run(stockDb).flatMap {
                  case Some(stockIndex) =>
                    if(filterIndex(stockIndex.qIndex202006, _ > 100)) {
                      filterList += stockIndex.code
                      Future.successful(None)
                    }
                    else{
                      StockRepoAccessor.selectStockIndex09(stock.code, "당좌비율").run(stockDb).flatMap {
                        case Some(stockIndex) =>
                          if(filterIndex(stockIndex.qIndex202006, _ < 100)) {
                            filterList += stockIndex.code
                            Future.successful(None)
                          }
                          else{
                            successList += stockIndex.code
                            Future.successful(None)
                          }
                        case None =>
                          failList += stockIndex.code
                          Future.successful(None)
                      }
                    }
                  case None =>
                    failList += stockIndex.code
                    Future.successful(None)
                }
              }
            }
            else{
              filterList += stockIndex.code
              Future.successful(None)
            }

          case None =>
            failList += stock.code
            Future.successful(None)
        }
      }.runWith(Sink.ignore).onComplete {
        case Success(_) =>
          println(successList)
          println(successList.size)
          println(filterList)
          println(filterList.size)
          println(failList)
          println(failList.size)
          Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 필터  성공", successList.toString, JsNull))
        case Failure(exception) => Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 필터 실패", exception.getMessage, JsNull))

      }
    }

    Future.successful((ResponseHeader.Ok.withStatus(200), Done))
  }


  // 3년 연간 당기 수익율 > 0
  // 1분기 당기 순이익 모두 +
  // 부채비율 100프로 이하
  // 당좌 비율 100 이상
  // 3년 배당 상승
  override def filterStock1: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>

    def filterIndex(index: String, filterFunc: Float => Boolean): Boolean = {
      try{
        filterFunc(index.filterNot(_ == ',').toFloat)
      }catch{ case _:Exception =>
        true
      }
    }

    def compareProfit(yIndex201712:String,yIndex201812:String,yIndex201912:String,yIndex202012E:String):Boolean ={
      try{
        if(yIndex201712.filterNot(_ == ',').toFloat<=yIndex201812.filterNot(_ == ',').toFloat
          && yIndex201812.filterNot(_ == ',').toFloat<=yIndex201912.filterNot(_ == ',').toFloat
        ) true else false
      }catch{ case _:Exception =>
        false
      }
    }

    for {
      stockList <- (External.requestKoreaMarketStockList(Market.KOSPI) zip External.requestKoreaMarketStockList(Market.KOSDAQ)).map(r => r._2 ++ r._1)
    } yield {
      val file = Paths.get("success.txt")
      val failList = ListBuffer.empty[String]
      val successList = ListBuffer.empty[String]
      val filterList = ListBuffer.empty[String]
      Source(stockList.toList.zipWithIndex).mapAsync(20) { case (stock, index) =>
        println(index)

        StockRepoAccessor.selectStockIndex09(stock.code, "당기순이익").run(stockDb).flatMap {
          case Some(stockIndex) =>
            if (filterIndex(stockIndex.yIndex201712, _ > 0)
              && filterIndex(stockIndex.yIndex201812, _ > 0)
              && filterIndex(stockIndex.yIndex201912, _ > 0)
              && filterIndex(stockIndex.yIndex202012E, _ > 0)
            ) {
              var cnt = 0
              if(filterIndex(stockIndex.qIndex201906, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex201909, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex201912, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex202003, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex202006, _ < 0)) cnt = cnt+1
              if(filterIndex(stockIndex.qIndex202009E, _ < 0)) cnt = cnt+1

              if(cnt>0) {
                filterList += stockIndex.code
                Future.successful(None)
              }
              else{
                StockRepoAccessor.selectStockIndex09(stock.code, "부채비율").run(stockDb).flatMap {
                  case Some(stockIndex) =>
                    if(filterIndex(stockIndex.qIndex202006, _ > 100)) {
                      filterList += stockIndex.code
                      Future.successful(None)
                    }
                    else{
                      StockRepoAccessor.selectStockIndex09(stock.code, "당좌비율").run(stockDb).flatMap {
                        case Some(stockIndex) =>
                          if(filterIndex(stockIndex.qIndex202006, _ < 100)) {
                            filterList += stockIndex.code
                            Future.successful(None)
                          }
                          else{
                            StockRepoAccessor.selectStockIndex09(stock.code, "시가배당률(%)").run(stockDb).flatMap{
                              case Some(stockIndex) =>
                                if(compareProfit(stockIndex.yIndex201712, stockIndex.yIndex201812, stockIndex.yIndex201912, stockIndex.yIndex202012E)){
                                  successList += stockIndex.code
                                  Future.successful(None)
                                }else{
                                  filterList += stockIndex.code
                                  Future.successful(None)
                                }
                              case None =>
                                failList += stockIndex.code
                                Future.successful(None)
                            }
                          }
                        case None =>
                          failList += stockIndex.code
                          Future.successful(None)
                      }
                    }
                  case None =>
                    failList += stockIndex.code
                    Future.successful(None)
                }
              }
            }
            else{
              filterList += stockIndex.code
              Future.successful(None)
            }

          case None =>
            failList += stock.code
            Future.successful(None)
        }
      }.runWith(Sink.ignore).onComplete {
        case Success(_) =>
          println(successList)
          println(successList.size)
          println(filterList)
          println(filterList.size)
          println(failList)
          println(failList.size)
          Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 필터  성공", successList.toString, JsNull))
        case Failure(exception) => Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 필터 실패", exception.getMessage, JsNull))

      }
    }

    Future.successful((ResponseHeader.Ok.withStatus(200), Done))
  }


  // 3년 연간 당기 수익율 > 0
  // 부채비율 100프로 이하
  // 당좌 비율 100 이상
  // 3년 배당 상승
  override def filterStock2: ServiceCall[NotUsed, Done] = ServerServiceCall { (_, _) =>

    def filterIndex(index: String, filterFunc: Float => Boolean): Boolean = {
      try{
        filterFunc(index.filterNot(_ == ',').toFloat)
      }catch{ case _:Exception =>
        true
      }
    }

    for {
      stockList <- (External.requestKoreaMarketStockList(Market.KOSPI) zip External.requestKoreaMarketStockList(Market.KOSDAQ)).map(r => r._2 ++ r._1)
    } yield {
      val file = Paths.get("success.txt")
      val failList = ListBuffer.empty[String]
      val successList = ListBuffer.empty[String]
      val filterList = ListBuffer.empty[String]
      Source(stockList.toList.zipWithIndex).mapAsync(20) { case (stock, index) =>
        println(index)

        StockRepoAccessor.selectStockIndex09(stock.code, "당기순이익").run(stockDb).flatMap {
          case Some(stockIndex) =>
            if (filterIndex(stockIndex.yIndex201712, _ > 0)
              && filterIndex(stockIndex.yIndex201812, _ > 0)
              && filterIndex(stockIndex.yIndex201912, _ > 0)
              && filterIndex(stockIndex.yIndex202012E, _ > 0)
            ) {
              var cnt = -1

              if(cnt>=0) {
                filterList += stockIndex.code
                Future.successful(None)
              }
              else{
                StockRepoAccessor.selectStockIndex09(stock.code, "부채비율").run(stockDb).flatMap {
                  case Some(stockIndex) =>
                    if(filterIndex(stockIndex.qIndex202006, _ > 100)) {
                      filterList += stockIndex.code
                      Future.successful(None)
                    }
                    else{
                      StockRepoAccessor.selectStockIndex09(stock.code, "당좌비율").run(stockDb).flatMap {
                        case Some(stockIndex) =>
                          if(filterIndex(stockIndex.qIndex202006, _ < 100)) {
                            filterList += stockIndex.code
                            Future.successful(None)
                          }
                          else{
                            StockRepoAccessor.selectStockIndex09(stock.code, "시가배당률(%)").run(stockDb).flatMap{
                              case Some(stockIndex) =>
                                try{
                                  if(stockIndex.yIndex201912.toFloat>=5) {
                                    successList += stockIndex.code
                                    Future.successful(None)
                                  }else{
                                    filterList += stockIndex.code
                                    Future.successful(None)
                                  }
                                }catch{ case _:Exception =>
                                  failList += stockIndex.code
                                  Future.successful(None)
                                }
                              case None =>
                                failList += stockIndex.code
                                Future.successful(None)
                            }
                          }
                        case None =>
                          failList += stockIndex.code
                          Future.successful(None)
                      }
                    }
                  case None =>
                    failList += stockIndex.code
                    Future.successful(None)
                }
              }
            }
            else{
              filterList += stockIndex.code
              Future.successful(None)
            }

          case None =>
            failList += stock.code
            Future.successful(None)
        }
      }.runWith(Sink.ignore).onComplete {
        case Success(_) =>
          println(successList)
          println(successList.size)
          println(filterList)
          println(filterList.size)
          println(failList)
          println(failList.size)
          Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 필터  성공", successList.toString, JsNull))
        case Failure(exception) => Fcm.sendFcmMsg(List(CollectorSettings.adminFcmRegistrationId), FcmMessage("지표 필터 실패", exception.getMessage, JsNull))

      }
    }

    Future.successful((ResponseHeader.Ok.withStatus(200), Done))
  }
}