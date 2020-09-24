package com.asset.collector.api

import akka.{Done, NotUsed}
import com.asset.collector.api.Market.Market
import com.lightbend.lagom.scaladsl.api.Service.restCall
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}
import play.api.Environment

trait CollectorService extends Service{


  def getKoreaStockList: ServiceCall[NotUsed, Seq[Stock]]
  def getUsaStockList: ServiceCall[NotUsed, Seq[Stock]]


  def requestBatchKoreaStock: ServiceCall[NotUsed, Done]
  def requestBatchUsaStock: ServiceCall[NotUsed, Done]


  override def descriptor: Descriptor ={
    import Service._


    named("Collector")
      .withCalls(

        restCall(Method.GET, "/stock/korea", getKoreaStockList),
        restCall(Method.GET, "/stock/usa", getUsaStockList),

        restCall(Method.POST, "/stock/korea/batch", requestBatchKoreaStock),
        restCall(Method.POST, "/stock/usa/batch", requestBatchUsaStock),
      ).withAutoAcl(true)
      .withExceptionSerializer(new ClientExceptionSerializer(Environment.simple()))

  }
}