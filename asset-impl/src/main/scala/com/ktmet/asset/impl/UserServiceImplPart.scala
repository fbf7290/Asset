package com.ktmet.asset.impl

import akka.{Done, NotUsed}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.ktmet.asset.api.message.{CreatingPortfolioMessage, LoginMessage, PortfolioCreatedMessage, RefreshingToken, SocialLoggingIn, TokenMessage, UserMessage}
import com.ktmet.asset.api.{AssetSettings, Token, UserId, UserState}
import com.ktmet.asset.common.api.Exception.NotFoundException
import com.ktmet.asset.impl.entity.UserEntity
import com.ktmet.asset.impl.internal.{Authenticator, ImplBase}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.transport.ResponseHeader
import com.lightbend.lagom.scaladsl.server.ServerServiceCall
import play.api.libs.json.Json

import scala.concurrent.Future

trait UserServiceImplPart extends ImplBase with Authenticator {


  def authenticate[Request, Response](
                                        serviceCall: UserId => ServerServiceCall[Request, Response]
                                      ) = ServerServiceCall.composeAsync { requestHeader =>
    authenticated[Request, Response](requestHeader,  serviceCall)
  }



  private def getSocialId(socialType: String, socialToken: String): Future[Option[String]] = {
    socialType match {
      case "kakao" =>
        wsClient.url(AssetSettings.kakao + "/v2/user/me")
            .addHttpHeaders("Content-Type" -> "application/x-www-form-urlencoded;charset=utf-8")
            .addHttpHeaders("Authorization" -> ("Bearer " + socialToken)).get.map{ response =>
                response.status match {
                  case 200 =>
                    val json = Json.parse(response.body)
                    val socialId = (json \ "id").get.as[Long].toString
                    Some(socialId)
                  case _ => None
                }
              }
      case "test" => Future.successful(Some(socialToken))
      case _ => Future.successful(None)
    }
  }


  override def login: ServiceCall[SocialLoggingIn, LoginMessage] =
    ServerServiceCall { (_, socialLoggingIn) =>
      val (socialType, socialToken) = (socialLoggingIn.socialType, socialLoggingIn.socialToken)
      this.getSocialId(socialType, socialToken).flatMap{
        case Some(socialId) =>
          val userId = UserId(UserId.userIdFormat(socialType, socialId))
          userEntityRef(userId.toString)
            .ask[UserEntity.TokenResponse](reply => UserEntity.LogIn(userId, reply))
            .map(token => (ResponseHeader.Ok.withStatus(201),LoginMessage(userId.value, token.accessToken, token.refreshToken.get)))
        case None => throw new NotFoundException
      }
    }

  override def logout: ServiceCall[NotUsed, Done] = authenticate{ userId =>
    ServerServiceCall{ (requestHeader ,_) =>
      userEntityRef(userId)
        .ask[UserEntity.Response](reply => UserEntity.LogOut(extractJwtToken(requestHeader).get, reply))
        .collect{
          case UserEntity.Yes => (ResponseHeader.Ok.withStatus(204), Done)
          case UserEntity.NoUserException => throw UserEntity.NoUserException
        }
    }
  }

  override def deleteUser: ServiceCall[NotUsed, Done] = authenticate{ userId =>
    ServerServiceCall{ (_,_) =>
      userEntityRef(userId)
        .ask[UserEntity.Response](reply => UserEntity.DeleteUser(reply))
        .collect{
          case UserEntity.Yes => (ResponseHeader.Ok.withStatus(204), Done)
          case UserEntity.NoUserException => throw UserEntity.NoUserException
        }
    }
  }

  override def refreshToken: ServiceCall[RefreshingToken, TokenMessage] =
    ServerServiceCall{ (_, refreshingToken) =>
      userEntityRef(refreshingToken.userId)
        .ask[UserEntity.Response](reply => UserEntity.RefreshToken(Token(refreshingToken.accessToken, refreshingToken.refreshToken), reply))
        .collect{
          case m:UserEntity.TokenResponse => (ResponseHeader.Ok.withStatus(201), TokenMessage(m.accessToken, m.refreshToken))
          case UserEntity.TokenException => throw UserEntity.TokenException
          case UserEntity.NoUserException => throw UserEntity.NoUserException
        }
    }

  override def getUser: ServiceCall[NotUsed, UserMessage] = authenticate{ userId =>
    ServerServiceCall{ (_,_) =>
      userEntityRef(userId)
        .ask[UserEntity.Response](reply => UserEntity.GetUser(reply))
        .collect{
          case UserEntity.UserResponse(userState) => (ResponseHeader.Ok.withStatus(200)
            , UserMessage(userState.userId.value, userState.portfolios.map(_.value)))
          case UserEntity.NoUserException => throw UserEntity.NoUserException
        }
    }
  }


}
