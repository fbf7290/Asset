package com.ktmet.asset.impl.internal

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.ktmet.asset.api.{AssetSettings, UserId}
import com.ktmet.asset.common.api.AuthorizationException
import com.ktmet.asset.common.api.Exception.InternalServerError
import com.ktmet.asset.impl.entity.UserEntity
import com.lightbend.lagom.scaladsl.api.transport.RequestHeader
import com.lightbend.lagom.scaladsl.server.ServerServiceCall
import pdi.jwt.{JwtAlgorithm, JwtJson}
import pdi.jwt.exceptions.JwtExpirationException

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait Authenticator {

  protected val clusterSharding: ClusterSharding
  protected implicit val timeout:Timeout
  protected implicit val ec: ExecutionContext

  case class Claim(userId:String)

  def extractJwtToken(requestHeader: RequestHeader):Option[String] =
    requestHeader.getHeader("Authorization")
      .filter(authorization => authorization.startsWith("Bearer ") && authorization.size > "Bearer ".size)
      .map(_.substring("Bearer ".size))


  def extractJwtClaim(token:Option[String]):Either[AuthorizationException, Claim] =
    token.fold[Either[AuthorizationException,Claim]](Left(new AuthorizationException("TokenEmpty", "Empty Authorization Header"))){
      token =>
        JwtJson.decodeJson(token, AssetSettings.jwtSecretKey, Seq(JwtAlgorithm.HS256)) match {
          case Success(claim) =>
            Right(Claim((claim \ "userId").as[String]))
          case Failure(exception) => exception match {
            case e: JwtExpirationException =>
              Left(new AuthorizationException("TokenExpired","Token Expired"))
            case e =>
              Left(new AuthorizationException("AuthorizationError", e.getMessage))
          }
        }
    }


  def funcIfTokenMatch[Result](userId:String, token:String, func: =>Future[Result]) =
    clusterSharding.entityRefFor(UserEntity.typeKey, userId)
      .ask[UserEntity.Response](reply => UserEntity.ContainToken(token, reply))
      .flatMap {
        case UserEntity.Yes => func
        case UserEntity.No => throw new AuthorizationException("AuthorizationError", "User does not exist accessToken or User does not exist or logout")
        case UserEntity.NoUserException => throw UserEntity.NoUserException
        case _ => throw new InternalServerError
      }


  def authenticated[Request, Response](requestHeader: RequestHeader, serviceCall: UserId => ServerServiceCall[Request, Response]) ={
    val token = extractJwtToken(requestHeader)
    val userId = extractJwtClaim(token) match {
      case Right(claim) => claim.userId
      case Left(exception) => throw exception
    }
    funcIfTokenMatch[ServerServiceCall[Request, Response]](userId, token.get, {Future.successful(serviceCall(UserId(userId)))})
  }

}