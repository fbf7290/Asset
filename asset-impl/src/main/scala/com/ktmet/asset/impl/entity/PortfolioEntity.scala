package com.ktmet.asset.impl.entity

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.ktmet.asset.api.{CacheHolding, CashFlowHistory, CashRatio, CategorySet, GoalAssetRatio, Holdings, PortfolioState, StockHolding, StockRatio, TradeHistory}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventShards, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import play.api.libs.json.{Format, Json}

object PortfolioEntity {
  sealed trait Command

  sealed trait Response
  trait ExceptionResponse extends Response

  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventShards[Event] = AggregateEventTag.sharded[Event](numShards = 64)
  }

  def empty: PortfolioEntity = PortfolioEntity(None)
  val typeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Portfolio")

  def apply(persistenceId: PersistenceId): EventSourcedBehavior[Command, Event, PortfolioEntity] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, PortfolioEntity](
        persistenceId = persistenceId,
        emptyState = PortfolioEntity.empty,
        commandHandler = (user, cmd) => user.applyCommand(cmd),
        eventHandler = (user, evt) => user.applyEvent(evt)
      ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  def apply(entityContext: EntityContext[Command]): Behavior[Command] =
    apply(PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      .withTagger(AkkaTaggerAdapter.fromLagom(entityContext, Event.Tag))


  implicit val format: Format[PortfolioEntity] = Json.format
  val serializers : Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[CategorySet],
    JsonSerializer[StockRatio],
    JsonSerializer[CashRatio],
    JsonSerializer[GoalAssetRatio],
    JsonSerializer[TradeHistory],
    JsonSerializer[CashFlowHistory],
    JsonSerializer[StockHolding],
    JsonSerializer[CacheHolding],
    JsonSerializer[Holdings],
    JsonSerializer[PortfolioState],
    JsonSerializer[PortfolioEntity],
  )
}


case class PortfolioEntity(state: Option[PortfolioState]) {
  import PortfolioEntity._

  private def foldState(funcIfState: PortfolioState =>
          ReplyEffect[Event, PortfolioEntity])
                      (funcIfNotState: =>ReplyEffect[Event, PortfolioEntity])
                        :ReplyEffect[Event, PortfolioEntity] =
    state match {
      case Some(state) => funcIfState(state)
      case None => funcIfNotState
    }

  def applyCommand(cmd: Command): ReplyEffect[Event, PortfolioEntity] = ???

  def applyEvent(evt: Event): PortfolioEntity = ???
}
