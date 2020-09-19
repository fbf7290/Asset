package com.ktmet.asset.impl

import akka.{Done, NotUsed}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.ktmet.asset.api.{AssetSettings, LoginMessage, RefreshingTokenMessage, SocialLoggingInMessage, TokenMessage, UserId, UserState}
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
                                        serviceCall: String => ServerServiceCall[Request, Response]
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


  override def login: ServiceCall[SocialLoggingInMessage, LoginMessage] =
    ServerServiceCall { (_, socialLoggingInMessage) =>
      val (socialType, socialToken) = (socialLoggingInMessage.socialType, socialLoggingInMessage.socialToken)
      this.getSocialId(socialType, socialToken).flatMap{
        case Some(socialId) =>
          val userId = UserId(socialType, socialId)
          userEntityRef(userId.toString)
            .ask[UserEntity.TokenResponse](reply => UserEntity.LogIn(userId, reply))
            .map(token => (ResponseHeader.Ok.withStatus(201),LoginMessage(userId.toString, token.accessToken.get)))
        case None => throw new NotFoundException
      }
    }

  override def logout: ServiceCall[NotUsed, Done] = authenticate{ userId =>
    ServerServiceCall{ (_,_) =>
      userEntityRef(userId)
        .ask[UserEntity.Response](reply => UserEntity.LogOut(reply))
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

  override def refreshToken: ServiceCall[RefreshingTokenMessage, TokenMessage] =
    ServerServiceCall{ (_, refreshingTokenMessage) =>
      userEntityRef(refreshingTokenMessage.userId)
        .ask[UserEntity.Response](reply => UserEntity.RefreshToken(refreshingTokenMessage.accessToken, reply))
        .collect{
          case m:UserEntity.TokenResponse => (ResponseHeader.Ok.withStatus(201), TokenMessage(m.accessToken.get))
          case UserEntity.TokenException => throw UserEntity.TokenException
          case UserEntity.NoUserException => throw UserEntity.NoUserException
        }
    }

  override def getUser: ServiceCall[NotUsed, UserState] = authenticate{ userId =>
    ServerServiceCall{ (_,_) =>
      userEntityRef(userId)
        .ask[UserEntity.Response](reply => UserEntity.GetUser(reply))
        .collect{
          case m:UserEntity.UserResponse => (ResponseHeader.Ok.withStatus(200), m.userState)
          case UserEntity.NoUserException => throw UserEntity.NoUserException
        }
    }
  }
}
