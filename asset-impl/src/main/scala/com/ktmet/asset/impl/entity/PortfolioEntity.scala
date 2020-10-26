package com.ktmet.asset.impl.entity

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.asset.collector.api.Country.Country
import com.ktmet.asset.api.{AssetCategory, CashFlowHistory, CashHolding, CashRatio, Category, CategorySet, GoalAssetRatio, Holdings, PortfolioId, PortfolioState, StockHolding, StockRatio, TradeHistory, UserId}
import com.ktmet.asset.common.api.{ClientException, Timestamp}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventShards, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import play.api.libs.json.{Format, Json}

object PortfolioEntity {

  sealed trait Command
  case class CreatePortfolio(portfolioId: PortfolioId, owner: UserId, name: String, replyTo: ActorRef[Response]) extends Command
  case class DeletePortfolio(owner: UserId, replyTo: ActorRef[Response]) extends Command
  case class GetPortfolio(replyTo: ActorRef[Response]) extends Command
  case class AddCategory(owner: UserId, category: Category, replyTo: ActorRef[Response]) extends Command
  case class UpdateGoalAssetRatio(owner: UserId, goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory, replyTo: ActorRef[Response]) extends Command
  case class Deposit(owner: UserId, cashFlowHistory: CashFlowHistory, replyTo: ActorRef[Response]) extends Command


  sealed trait Response
  trait ExceptionResponse extends Response

  case object Yes extends Response
  case class TimestampResponse(updateTimestamp: Long) extends Response
  case class CreateResponse(portfolioId: PortfolioId, name: String, updateTimestamp: Long) extends Response
  case class PortfolioResponse(portfolioState: PortfolioState) extends Response

  case object NoPortfolioException extends ClientException(404, "NoPortfolioException", "Portfolio does not exist") with Response
  case object AlreadyPortfolioException extends ClientException(404, "AlreadyPortfolioException", "Portfolio already exist") with Response
  case object PortfolioUnauthorizedException extends ClientException(409, "PortfolioUnauthorizedException", "PortfolioUnauthorizedException") with Response
  case object AlreadyCategoryException extends ClientException(404, "AlreadyCategoryException", "Category already exist") with Response
  case object InvalidCategoryException extends ClientException(404, "InvalidCategoryException", "InvalidCategoryException") with Response
  case object InvalidParameterException extends ClientException(409, "InvalidParameterException", "InvalidParameterException") with Response
  case object AlreadyHistoryException extends ClientException(404, "AlreadyHistoryException", "AlreadyHistoryException") with Response

  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventShards[Event] = AggregateEventTag.sharded[Event](numShards = 64)
  }

  case class PortfolioCreated(portfolioId: PortfolioId, owner: UserId, name: String, updateTimestamp: Long) extends Event
  object PortfolioCreated{
    implicit val format:Format[PortfolioCreated] = Json.format
  }
  case class PortfolioDeleted(portfolioId: PortfolioId) extends Event
  object PortfolioDeleted{
    implicit val format:Format[PortfolioDeleted] = Json.format
  }
  case class CategoryAdded(category: Category, updateTimestamp: Long) extends Event
  object CategoryAdded{
    implicit val format:Format[CategoryAdded] = Json.format
  }
  case class GoalAssetRatioUpdated(goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory, updateTimestamp: Long) extends Event
  object GoalAssetRatioUpdated{
    implicit val format:Format[GoalAssetRatioUpdated] = Json.format
  }
  case class Deposited(cashFlowHistory: CashFlowHistory, updateTimestamp: Long) extends Event
  object Deposited{
    implicit val format:Format[Deposited] = Json.format
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
    JsonSerializer[PortfolioCreated],
    JsonSerializer[PortfolioDeleted],
    JsonSerializer[CategoryAdded],
    JsonSerializer[GoalAssetRatioUpdated],
    JsonSerializer[Deposited],
    JsonSerializer[CategorySet],
    JsonSerializer[StockRatio],
    JsonSerializer[CashRatio],
    JsonSerializer[GoalAssetRatio],
    JsonSerializer[TradeHistory],
    JsonSerializer[CashFlowHistory],
    JsonSerializer[StockHolding],
    JsonSerializer[CashHolding],
    JsonSerializer[Holdings],
    JsonSerializer[PortfolioState],
    JsonSerializer[PortfolioEntity],
  )
}


case class PortfolioEntity(state: Option[PortfolioState]) {
  import PortfolioEntity._

  private def foldState(funcIfState: PortfolioState => ReplyEffect[Event, PortfolioEntity])
                      (funcIfNotState: =>ReplyEffect[Event, PortfolioEntity])
                        :ReplyEffect[Event, PortfolioEntity] =
    state match {
      case Some(state) => funcIfState(state)
      case None => funcIfNotState
    }

  private def funcWithState(replyTo:ActorRef[Response])
                           (func: PortfolioState =>ReplyEffect[Event, PortfolioEntity])
                            :ReplyEffect[Event, PortfolioEntity] =
    foldState(func)(Effect.reply(replyTo)(NoPortfolioException))

  private def funcWithOwner(owner: UserId, replyTo:ActorRef[Response])
                           (func: PortfolioState =>ReplyEffect[Event, PortfolioEntity])
                            :ReplyEffect[Event, PortfolioEntity] =
    funcWithState(replyTo){ state =>
      if(state.owner == owner) func(state)
      else Effect.reply(replyTo)(PortfolioUnauthorizedException)
    }

  def applyCommand(cmd: Command): ReplyEffect[Event, PortfolioEntity] = cmd match {
    case CreatePortfolio(portfolioId, owner, name, replyTo) => onCreatePortfolio(portfolioId, owner, name, replyTo)
    case DeletePortfolio(owner, replyTo) => onDeletePortfolio(owner, replyTo)
    case GetPortfolio(replyTo) => onGetPortfolio(replyTo)
    case AddCategory(owner, category, replyTo) => onAddCategory(owner, category, replyTo)
    case UpdateGoalAssetRatio(owner, goalAssetRatio, assetCategory, replyTo) => onUpdateGoalAssetRatio(owner, goalAssetRatio, assetCategory, replyTo)
    case Deposit(owner, cashFlowHistory, replyTo) => onDeposit(owner, cashFlowHistory, replyTo)
  }

  private def onCreatePortfolio(portfolioId: PortfolioId, owner: UserId, name: String
                                , replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    foldState(_ => Effect.reply(replyTo)(AlreadyPortfolioException))(
      Effect.persist(PortfolioCreated(portfolioId, owner, name, Timestamp.now))
        .thenReply(replyTo)(s=>CreateResponse(portfolioId, name, s.state.get.updateTimestamp)))

  private def onDeletePortfolio(owner: UserId, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo)(state => Effect.persist(PortfolioDeleted(state.portfolioId)).thenReply(replyTo)(_ => Yes))
  private def onGetPortfolio(replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithState(replyTo)(state => Effect.reply(replyTo)(PortfolioResponse(state)))
  private def onAddCategory(owner: UserId, category: Category, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){state =>
      if(state.containCategory(category)) Effect.reply(replyTo)(AlreadyCategoryException)
      else Effect.persist(CategoryAdded(category, Timestamp.now)).thenReply(replyTo)(e => TimestampResponse(e.state.get.updateTimestamp))
    }
  private def onUpdateGoalAssetRatio(owner: UserId, goalAssetRatio: GoalAssetRatio
                                     , assetCategory: AssetCategory, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){state =>
      goalAssetRatio.isValid match {
        case true => (assetCategory.getCategories -- goalAssetRatio.getCategories).size >= 0 match {
          case true =>
//              true match {
            assetCategory.getAssets == state.getHoldingAssets match {
              case true =>  Effect.persist(GoalAssetRatioUpdated(goalAssetRatio, assetCategory, Timestamp.now))
                              .thenReply(replyTo)(e => TimestampResponse(e.state.get.updateTimestamp))
              case false => Effect.reply(replyTo)(InvalidParameterException)
           }
          case false => Effect.reply(replyTo)(InvalidParameterException)
        }
        case false => Effect.reply(replyTo)(InvalidParameterException)
      }
    }
  private def onDeposit(owner: UserId, cashFlowHistory: CashFlowHistory
                        , replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.getHoldingCash(cashFlowHistory.country) match {
        case None => Effect.reply(replyTo)(InvalidParameterException)
        case Some(cash) =>
          cash.containHistory(cashFlowHistory) match {
            case true =>  Effect.reply(replyTo)(InvalidParameterException)
            case false => Effect.persist(Deposited(cashFlowHistory, Timestamp.now))
              .thenReply(replyTo)(e => TimestampResponse(e.state.get.updateTimestamp))
          }
      }
    }


  def applyEvent(evt: Event): PortfolioEntity = evt match {
    case PortfolioCreated(portfolioId, owner, name, updateTimestamp) => onPortfolioCreated(portfolioId, owner, name, updateTimestamp)
    case PortfolioDeleted(portfolioId) => onPortfolioDeleted(portfolioId)
    case CategoryAdded(category, updateTimestamp) => onCategoryAdded(category, updateTimestamp)
    case GoalAssetRatioUpdated(goalAssetRatio, assetCategory, updateTimestamp) => onGoalAssetRatioUpdated(goalAssetRatio, assetCategory, updateTimestamp)
    case Deposited(cashFlowHistory, updateTimestamp) => onDeposited(cashFlowHistory, updateTimestamp)
  }

  private def onPortfolioCreated(portfolioId: PortfolioId, owner: UserId, name: String, updateTimestamp: Long): PortfolioEntity =
    copy(Some(PortfolioState(portfolioId, name, updateTimestamp, owner, GoalAssetRatio.empty, AssetCategory.empty, Holdings.empty)))
  private def onPortfolioDeleted(portfolioId: PortfolioId): PortfolioEntity = copy(None)
  private def onCategoryAdded(category: Category, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.addCategory(category).updateTimestamp(updateTimestamp)))
  private def onGoalAssetRatioUpdated(goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory, updateTimestamp: Long): PortfolioEntity =
    copy(Some(state.get.copy(goalAssetRatio = goalAssetRatio, assetCategory = assetCategory, updateTimestamp = updateTimestamp)))
  private def onDeposited(cashFlowHistory: CashFlowHistory, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.deposit(cashFlowHistory).updateTimestamp(updateTimestamp)))







}
