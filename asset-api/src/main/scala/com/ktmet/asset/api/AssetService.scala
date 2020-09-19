package com.ktmet.asset.api

import akka.{Done, NotUsed}
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


  override def descriptor: Descriptor = {

    import Service._
    named("asset")
      .withCalls(

        restCall(Method.POST, "/user/login", login),
        restCall(Method.DELETE, "/user/logout", logout),
        restCall(Method.DELETE, "/user", deleteUser),
        restCall(Method.POST, "/user/refresh", refreshToken),
        restCall(Method.GET, "/user", getUser),

      ).withAutoAcl(true)
      .withExceptionSerializer(new ClientExceptionSerializer(Environment.simple()))
  }
}
