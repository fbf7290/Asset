package com.ktmet.asset.api

import akka.{Done, NotUsed}
import com.asset.collector.api.{KrwUsd, NowPrice, Stock}
import com.ktmet.asset.common.api.ClientExceptionSerializer
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceAcl, ServiceCall}
import play.api.Environment


trait AssetService extends Service{



  def login: ServiceCall[SocialLoggingInMessage, LoginMessage]
  def logout: ServiceCall[NotUsed, Done]
  def deleteUser: ServiceCall[NotUsed, Done]
  def refreshToken: ServiceCall[RefreshingTokenMessage, TokenMessage]
  def getUser: ServiceCall[NotUsed, UserState]


  def autoCompleteStock(prefix:String): ServiceCall[NotUsed, AutoCompleteMessage]
  def getNowPrice(code:String): ServiceCall[NotUsed, NowPrice]
  def getNowKrwUsd: ServiceCall[NotUsed, KrwUsd]


  override def descriptor: Descriptor = {

    import Service._
    named("asset")
      .withCalls(

        restCall(Method.POST, "/user/login", login),
        restCall(Method.DELETE, "/user/logout", logout),
        restCall(Method.DELETE, "/user", deleteUser),
        restCall(Method.POST, "/user/refresh", refreshToken),
        restCall(Method.GET, "/user", getUser),

        restCall(Method.GET, "/search/prefix/:prefix", autoCompleteStock _),
        restCall(Method.GET, "/stock/now/:code", getNowPrice _),
        restCall(Method.GET, "/krwusd1", getNowKrwUsd _),

      ).withAutoAcl(true)
      .withExceptionSerializer(new ClientExceptionSerializer(Environment.simple()))
  }
}
