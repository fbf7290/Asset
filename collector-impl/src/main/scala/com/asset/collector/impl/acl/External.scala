package com.asset.collector.impl.acl

import java.text.SimpleDateFormat
import java.util.Calendar

import com.asset.collector.api.Exception.ExternalResourceException
import com.asset.collector.api.Market.Market
import com.asset.collector.api.{DumbStock, FinnHubStock, Market, NaverEtfListResponse, NowPrice, Price, Stock}
import org.jsoup.Jsoup
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import yahoofinance.YahooFinance
import yahoofinance.histquotes.Interval

import collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

object External {
  def requestKoreaEtfStockList(implicit wsClient: WSClient, ec: ExecutionContext):Future[Seq[Stock]] = {
    var stockList = ListBuffer.empty[Stock]
    wsClient.url("https://finance.naver.com/api/sise/etfItemList.nhn").get().map{
      response =>
        val naverEtfListResponse = Json.parse(response.body).as[NaverEtfListResponse]
        (naverEtfListResponse.resultCode=="success") match {
          case true =>
            stockList ++= naverEtfListResponse.result.etfItemList.map(etf => Stock(Market.ETF, etf.itemname, etf.itemcode))
            stockList.toSeq
          case false => throw ExternalResourceException()
        }
    }
  }


  def requestKoreaMarketStockList(market: Market)(implicit wsClient: WSClient, ec: ExecutionContext):Future[Seq[Stock]]= {
    var stockList = ListBuffer.empty[Stock]
    val marketParam = if(market == Market.KOSDAQ) "kosdaqMkt" else if(market == Market.KOSPI) "stockMkt"
    wsClient.url(s"http://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=${marketParam}").get().map{
      response =>
        val stocks = Jsoup.parseBodyFragment(response.body).body().getElementsByTag("tr")
        for(stock <- stocks.asScala){
          val stockAttrs = stock.getElementsByTag("td").asScala
          if(stockAttrs.size != 0) stockList += Stock(market, stockAttrs(0).text, stockAttrs(1).text)
        }
        stockList.toList
    }
  }

  def requestUsaMarketStockList(market:Market)(implicit wsClient: WSClient, ec: ExecutionContext):Future[Seq[Stock]] = {
    var stockList = ListBuffer.empty[Stock]
    val marketParam = market match {
      case Market.NASDAQ => "NASDAQ"
      case Market.NYSE => "NYSE"
      case Market.AMEX => "AMEX"
    }
    wsClient.url(s"https://dumbstockapi.com/stock?exchanges=${marketParam}").get.map{
      response =>
        Json.parse(response.body).as[Seq[DumbStock]].foreach(dumbStock => stockList += Stock(market, dumbStock.name, dumbStock.ticker.replace("^", "-P").replace(".", "-")))
        stockList.toSeq
    }
  }

  def requestUsaMarketStockListByFinnHub(implicit wsClient: WSClient, ec: ExecutionContext):Future[Seq[Stock]] = {
    var stockList = ListBuffer.empty[Stock]
    wsClient.url("https://finnhub.io/api/v1/stock/symbol?exchange=US&token=btq8hef48v6t9hdd4bn0").get().map{
      response =>
        Json.parse(response.body).as[Seq[FinnHubStock]].foreach(stock =>
         stockList += Stock(Market.NONE, stock.description, stock.symbol))
        stockList.toSeq
    }
  }

  def requestUsaEtfStockList(implicit wsClient: WSClient, ec: ExecutionContext):Future[Seq[Stock]] = {
    var stockList = ListBuffer.empty[Stock]
    wsClient.url("https://finnhub.io/api/v1/stock/symbol?exchange=US&token=btq8hef48v6t9hdd4bn0").get().map{
      response =>
        Json.parse(response.body).as[Seq[FinnHubStock]].foreach(stock =>
          if(stock.`type`.equals("ETF")) stockList += Stock(Market.ETF, stock.description, stock.symbol))
        stockList.toSeq
    }
  }

  def requestKoreaStockPrice(code:String, count:Int=Int.MaxValue)(implicit wsClient: WSClient, ec: ExecutionContext):Future[Seq[Price]] =
    wsClient.url(s"https://fchart.stock.naver.com/sise.nhn?timeframe=day&count=${count}&requestType=0&symbol=${code}").get().map{
      response =>
        val pattern = new scala.util.matching.Regex("<item data=\\\"(.*)\\\" />")
        pattern.findAllIn(response.body).matchData.map(_.group(1).split('|')).toList.filter(_.size==6)
          .map(arr => Price(code, arr(0), arr(4), arr(1), arr(2), arr(3), arr(5))).toSeq
    }

  def requestUsaStockPrice(code:String, year:Int=100)(implicit ec: ExecutionContext):Future[Seq[Price]] =
    Future{
      val from = Calendar.getInstance()
      from.add(Calendar.YEAR, -1*year)
      val format = new SimpleDateFormat("yyyyMMdd")
      YahooFinance.get(code, from, Interval.DAILY).getHistory.asScala.map{
        stock =>
          Price(code, format.format(stock.getDate.getTime()), stock.getClose.toString, stock.getOpen.toString, stock.getHigh.toString, stock.getLow.toString, stock.getVolume.toString)
      }
    }


  def requestKoreaStocksNowPrice(stocks:Seq[Stock])(implicit ec: ExecutionContext):Future[Seq[NowPrice]] =
    Future{
        val prices = YahooFinance.get(stocks.map(stock =>
          if(stock.market == Market.KOSPI || stock.market == Market.ETF) stock.code+".KS"
          else stock.code+".KQ").toArray)
        prices.asScala.values.map{stock =>
      NowPrice(stock.getSymbol.split("\\.")(0), stock.getQuote.getPrice.toString)}.toSeq
    }

  def requestUsaStocksNowPrice(stocks:Seq[Stock])(implicit ec: ExecutionContext):Future[Seq[NowPrice]] =
    Future{
      println(stocks.size)
      val prices = YahooFinance.get(stocks.map(stock =>stock.code).toArray)
      prices.asScala.values.map(stock => NowPrice(stock.getSymbol, stock.getQuote.getPrice.toString)).toSeq
    }
}
