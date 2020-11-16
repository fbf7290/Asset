package com.asset.collector.api

import akka.{Done, NotUsed}
import com.asset.collector.api.Market.Market
import com.asset.collector.api.message.GettingClosePricesAfterDate
import com.lightbend.lagom.scaladsl.api.Service.restCall
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}
import play.api.Environment

trait CollectorService extends Service{


  def getKoreaStockList: ServiceCall[NotUsed, Seq[Stock]]
  def getUsaStockList: ServiceCall[NotUsed, Seq[Stock]]

  def getKoreaNowPrices: ServiceCall[NotUsed, Map[String, NowPrice]]
  def getUsaNowPrices: ServiceCall[NotUsed, Map[String, NowPrice]]
  def getNowKrwUsd: ServiceCall[NotUsed, KrwUsd]

  def getClosePricesAfterDate: ServiceCall[GettingClosePricesAfterDate, Seq[ClosePrice]]
  def getKrwUsdsAfterDate(date: String): ServiceCall[NotUsed, Seq[KrwUsd]]

  def insertKoreaStockPrice(code:String): ServiceCall[NotUsed, Done]
  def insertUsaStockPrice(code:String): ServiceCall[NotUsed, Done]

  def requestBatchKoreaStock: ServiceCall[NotUsed, Done]
  def requestBatchUsaStock: ServiceCall[NotUsed, Done]
  def requestBatchKrwUsd: ServiceCall[NotUsed, Done]



  override def descriptor: Descriptor ={
    import Service._


    named("Collector")
      .withCalls(

        restCall(Method.GET, "/stock/korea", getKoreaStockList),
        restCall(Method.GET, "/stock/usa", getUsaStockList),

        restCall(Method.GET, "/stock/korea/prices", getKoreaNowPrices),
        restCall(Method.GET, "/stock/usa/prices", getUsaNowPrices),
        restCall(Method.GET, "/collect/krwusd", getNowKrwUsd),

        restCall(Method.GET, "/stock/prices/close", getClosePricesAfterDate),
        restCall(Method.GET, "/krwusds/:date", getKrwUsdsAfterDate _),

        restCall(Method.POST, "/stock/korea/batch", requestBatchKoreaStock),
        restCall(Method.POST, "/stock/usa/batch", requestBatchUsaStock),

        restCall(Method.POST, "/stock/korea/insert/price/:code", insertKoreaStockPrice _),
        restCall(Method.POST, "/stock/usa/insert/price/:code", insertUsaStockPrice _),
        restCall(Method.POST, "/krwusd/batch", requestBatchKrwUsd _),
      ).withAutoAcl(true)
      .withExceptionSerializer(new ClientExceptionSerializer(Environment.simple()))

  }
}
