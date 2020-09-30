package com.ktmet.asset.impl.entity

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.ktmet.asset.api.{AssetSettings, UserId, UserState}
import com.ktmet.asset.common.api.{ClientException, Timestamp}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventShards, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.libs.json.{Format, Json}

import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration

object UserEntity {

  trait CommandSerializable
  sealed trait Command extends CommandSerializable
  case class LogIn(userId:UserId, replyTo:ActorRef[TokenResponse]) extends Command
  case class LogOut(accessToken:String, replyTo:ActorRef[Response]) extends Command
  case class DeleteUser(replyTo:ActorRef[Response]) extends Command
  case class ContainToken(accessToken:String, replyTo:ActorRef[Response]) extends Command
  case class RefreshToken(accessToken:String, replyTo:ActorRef[Response]) extends Command
  case class GetUser(replyTo:ActorRef[Response]) extends Command

  trait ResponseSerializable
  sealed trait Response extends ResponseSerializable

  case object Yes extends Response
  case object No extends Response
  case class TokenResponse(accessToken:Option[String]) extends Response
  case class UserResponse(userState:UserState) extends Response

  case object NoUserException extends ClientException(404, "NoUserException", "User does not exist") with Response
  case object TokenException extends ClientException(401, "TokenException", "Check your token") with Response



  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventShards[Event] = AggregateEventTag.sharded[Event](numShards = 5)
  }

  case class UserCreated(userId:UserId, accessToken:String) extends Event
  object UserCreated{
    implicit val format:Format[UserCreated] = Json.format
  }
  case class LoggedIn(userId:UserId, accessToken:String) extends Event
  object LoggedIn{
    implicit val format:Format[LoggedIn] = Json.format
  }
  case class LoggedOut(userId:UserId, accessToken:String) extends Event
  object LoggedOut{
    implicit val format:Format[LoggedOut] = Json.format
  }
  case class UserDeleted(userId:UserId) extends Event
  object UserDeleted{
    implicit val format:Format[UserDeleted] = Json.format
  }
  case class TokenRefreshed(lastAccessToken:String, newAccessToken:String) extends Event
  object TokenRefreshed{
    implicit val format:Format[TokenRefreshed] = Json.format
  }

  val empty:UserEntity = UserEntity(None)
  val typeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("User")


  def apply(persistenceId: PersistenceId): EventSourcedBehavior[Command, Event, UserEntity] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, UserEntity](
        persistenceId = persistenceId,
        emptyState = UserEntity.empty,
        commandHandler = (user, cmd) => user.applyCommand(cmd),
        eventHandler = (user, evt) => user.applyEvent(evt)
      ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  def apply(entityContext: EntityContext[Command]): Behavior[Command] =
    apply(PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      .withTagger(AkkaTaggerAdapter.fromLagom(entityContext, Event.Tag))

  implicit val userFormat: Format[UserEntity] = Json.format
  val serializers : Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[UserCreated],
    JsonSerializer[LoggedIn],
    JsonSerializer[LoggedOut],
    JsonSerializer[UserDeleted],
    JsonSerializer[TokenRefreshed],
    JsonSerializer[UserId],
    JsonSerializer[UserState],
    JsonSerializer[UserEntity]
  )
}


final case class UserEntity(userState:Option[UserState]) {
  import UserEntity._


  private def foldUser(funcIfUser: UserState =>ReplyEffect[Event, UserEntity])(funcIfNotUser: =>ReplyEffect[Event, UserEntity]) = userState match {
    case Some(userState) => funcIfUser(userState)
    case None => funcIfNotUser
  }

  private def funcWithUser(replyTo:ActorRef[Response])(func: UserState =>ReplyEffect[Event, UserEntity]): ReplyEffect[Event, UserEntity] =
    foldUser(func)(Effect.reply(replyTo)(NoUserException))


  private def createAccessToken(userId:UserId):String = JwtJson.encode(
    Json.obj("userId"-> userId.toString, "exp"-> Timestamp.afterDuration(AssetSettings.accessTokenExpiredSecond.seconds)),
    AssetSettings.jwtSecretKey,
    JwtAlgorithm.HS256)

  def applyCommand(cmd: Command): ReplyEffect[Event, UserEntity] = cmd match {
    case LogIn(userId, replyTo) => onLogIn(userId, replyTo)
    case LogOut(accessToken, replyTo) => onLogOut(accessToken, replyTo)
    case DeleteUser(replyTo) => onDeleteUser(replyTo)
    case ContainToken(accessToken, replyTo) => onContainToken(accessToken, replyTo)
    case RefreshToken(accessToken, replyTo) => onRefreshToken(accessToken, replyTo)
    case GetUser(replyTo) => onGetUser(replyTo)
  }


  def onLogIn(userId: UserId, replyTo: ActorRef[TokenResponse]): ReplyEffect[Event, UserEntity] = {
    val token = createAccessToken(userId)
    foldUser{ _ =>
      Effect.persist(LoggedIn(userId, token)).thenReply(replyTo)(_=>TokenResponse(Some(token)))
    }{
      Effect.persist(UserCreated(userId, token)).thenReply(replyTo)(_=>TokenResponse(Some(token)))
    }
  }
  private def onLogOut(accessToken:String, replyTo:ActorRef[Response]): ReplyEffect[Event, UserEntity] =
    funcWithUser(replyTo)( state =>
      Effect.persist(LoggedOut(state.userId, accessToken)).thenReply(replyTo)(_=>Yes)
    )
  private def onDeleteUser(replyTo: ActorRef[Response]): ReplyEffect[Event, UserEntity] =
    funcWithUser(replyTo)( state =>
      Effect.persist(UserDeleted(state.userId)).thenReply(replyTo)(_=>Yes)
    )
  private def onContainToken(accessToken: String, replyTo: ActorRef[Response]): ReplyEffect[Event, UserEntity] =
    funcWithUser(replyTo)( state =>
      state.containToken(accessToken) match {
        case true => Effect.reply(replyTo)(Yes)
        case false => Effect.reply(replyTo)(No)
      }
    )
  private def onRefreshToken(accessToken: String, replyTo: ActorRef[Response]): ReplyEffect[Event, UserEntity] =
    funcWithUser(replyTo){ state =>
      state.containToken(accessToken) match {
        case true =>
          val newAccessToken = createAccessToken(state.userId)
          Effect.persist(TokenRefreshed(accessToken, newAccessToken)).thenReply(replyTo)(_=>TokenResponse(Some(newAccessToken)))
        case false => Effect.reply(replyTo)(TokenException)
      }
    }
  private def onGetUser(replyTo: ActorRef[Response]): ReplyEffect[Event, UserEntity] =
    funcWithUser(replyTo){state =>
      println(state.accessTokens.size)
      Effect.reply(replyTo)(UserResponse(state))
    }

  def applyEvent(evt: Event): UserEntity = evt match {
    case UserCreated(userId, accessToken) => onUserCreated(userId, accessToken)
    case LoggedIn(_, accessToken) => onLoggedIn(accessToken)
    case LoggedOut(_, accessToken) => onLoggedOut(accessToken)
    case UserDeleted(_) => onUserDeleted
    case TokenRefreshed(lastAccessToken, newAccessToken) => onTokenRefreshed(lastAccessToken, newAccessToken)
  }

  private def onUserCreated(userId: UserId, accessToken: String): UserEntity = copy(userState = Some(UserState(userId, List(accessToken))))
  private def onLoggedIn(accessToken: String): UserEntity = copy(userState.map(_.loggedIn(accessToken)))
  private def onLoggedOut(accessToken: String): UserEntity = copy(userState.map(_.loggedOut(accessToken)))
  private def onUserDeleted: UserEntity = copy(None)
  private def onTokenRefreshed(lastAccessToken:String, newAccessToken: String): UserEntity =
    copy(userState.map(_.loggedOut(lastAccessToken).loggedIn(newAccessToken)))
}
