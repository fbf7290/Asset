package com.ktmet.asset.impl.entity

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.ktmet.asset.api.{UserId, UserState}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventShards, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

object UserEntity {

  trait CommandSerializable
  sealed trait Command extends CommandSerializable
  case class LogIn(userId:UserId) extends Command
  case object LogOut extends Command

  trait ResponseSerializable
  sealed trait Response extends ResponseSerializable


  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventShards[Event] = AggregateEventTag.sharded[Event](numShards = 5)
  }

  case class UserCreated(userId:UserId, accessToken:String) extends Command
  object UserCreated{
    implicit val format:Format[UserCreated] = Json.format
  }
  case class LoggedIn(userId:UserId, accessToken:String) extends Command
  object LoggedIn{
    implicit val format:Format[LoggedIn] = Json.format
  }
  case class LoggedOut(userId:UserId) extends Command
  object LoggedOut{
    implicit val format:Format[LoggedOut] = Json.format
  }



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


  val empty:UserEntity = UserEntity(None)
  val typeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("User")
  implicit val userFormat: Format[UserEntity] = Json.format
  val serializers : Seq[JsonSerializer[_]] = Seq.empty
}


final case class UserEntity(userState:Option[UserState]) {
  import UserEntity._

  private def createToken(duration: Duration):String = JwtJson.encode(
    Json.obj("userId"-> userId.get, "exp"-> Timestamp.afterDuration(duration)),
    MarketAreaSettings.jwtSecretKey,
    JwtAlgorithm.HS256)

  def applyCommand(cmd: Command): ReplyEffect[Event, UserEntity] = ???
  def applyEvent(evt: Event): UserEntity = ???
}
