package com.ktmet.asset.impl.entity

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, Stock}
import com.ktmet.asset.api.CashFlowHistory.FlowType
import com.ktmet.asset.api.{AssetCategory, CashFlowHistory, CashHolding, CashRatio, Category, CategorySet, GoalAssetRatio, HistorySet, Holdings, PortfolioId, PortfolioState, StockHolding, StockRatio, TradeHistory, UserId}
import com.ktmet.asset.common.api.{ClientException, Timestamp}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventShards, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import play.api.libs.json.{Format, Json}
import cats.syntax.either._
import io.jvm.uuid._



object PortfolioEntity {

  sealed trait Command
  case class CreatePortfolio(portfolioId: PortfolioId, owner: UserId, name: String, usaCash: BigDecimal, koreaCash: BigDecimal, replyTo: ActorRef[Response]) extends Command
  case class DeletePortfolio(owner: UserId, replyTo: ActorRef[Response]) extends Command
  case class GetPortfolio(replyTo: ActorRef[Response]) extends Command
  case class AddCategory(owner: UserId, category: Category, replyTo: ActorRef[Response]) extends Command
  case class UpdateGoalAssetRatio(owner: UserId, goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory, replyTo: ActorRef[Response]) extends Command
  case class AddCashFlowHistory(owner: UserId, cashFlowHistory: CashFlowHistory, replyTo: ActorRef[Response]) extends Command
  case class UpdateCashFlowHistory(owner: UserId, cashFlowHistory: CashFlowHistory, replyTo: ActorRef[Response]) extends Command
  case class DeleteCashFlowHistory(owner: UserId, country: Country, cashFlowHistoryId: String, replyTo: ActorRef[Response]) extends Command
  case class AddStock(owner: UserId, stock: Stock, category: Category, historySets: Seq[HistorySet], replyTo: ActorRef[Response]) extends Command
  case class AddTradeHistory(owner: UserId, historySet: HistorySet, replyTo: ActorRef[Response]) extends Command
  case class DeleteTradeHistory(owner: UserId, stock: Stock, tradeHistoryId: String, replyTo: ActorRef[Response]) extends Command
  case class UpdateTradeHistory(owner: UserId, historySet: HistorySet, replyTo: ActorRef[Response]) extends Command
  case class DeleteStock(owner: UserId, stock: Stock, category: Category, replyTo: ActorRef[Response]) extends Command
  case class UpdateStockCategory(owner: UserId, stock: Stock, lastCategory: Category, newCategory: Category, replyTo: ActorRef[Response]) extends Command

  sealed trait Response
  trait ExceptionResponse extends Response

  case object Yes extends Response
  case class TimestampResponse(updateTimestamp: Long) extends Response
  case class CreatedResponse(portfolioId: PortfolioId, name: String, usaCashFlowHistory: CashFlowHistory, koreaCashFlowHistory: CashFlowHistory, updateTimestamp: Long) extends Response
  case class StockAddedResponse(stockHolding: StockHolding, cashHolding: CashHolding, updateTimestamp: Long) extends Response
  case class StockDeletedResponse(cashHolding: CashHolding, updateTimestamp: Long) extends Response
  case class PortfolioResponse(portfolioState: PortfolioState) extends Response
  case class TradeHistoryAddedResponse(stockHolding: StockHolding, cashHolding: CashHolding, updateTimestamp: Long) extends Response
  case class TradeHistoryDeletedResponse(stockHolding: StockHolding, cashHolding: CashHolding, updateTimestamp: Long) extends Response
  case class TradeHistoryUpdatedResponse(stockHolding: StockHolding, cashHolding: CashHolding, updateTimestamp: Long) extends Response
  case class CashFlowHistoryAddedResponse(cashHolding: CashHolding, updateTimestamp: Long) extends Response
  case class CashFlowHistoryDeletedResponse(cashHolding: CashHolding, updateTimestamp: Long) extends Response
  case class CashFlowHistoryUpdatedResponse(cashHolding: CashHolding, updateTimestamp: Long) extends Response

  case object NoPortfolioException extends ClientException(404, "NoPortfolioException", "Portfolio does not exist") with Response
  case object AlreadyPortfolioException extends ClientException(404, "AlreadyPortfolioException", "Portfolio already exist") with Response
  case object PortfolioUnauthorizedException extends ClientException(409, "PortfolioUnauthorizedException", "PortfolioUnauthorizedException") with Response
  case object AlreadyCategoryException extends ClientException(404, "AlreadyCategoryException", "Category already exist") with Response
  case object InvalidCategoryException extends ClientException(404, "InvalidCategoryException", "InvalidCategoryException") with Response
  case object InvalidParameterException extends ClientException(409, "InvalidParameterException", "InvalidParameterException") with Response
  case object AlreadyHistoryException extends ClientException(404, "AlreadyHistoryException", "AlreadyHistoryException") with Response
  case object AlreadyStockException extends ClientException(404, "AlreadyStockException", "AlreadyStockException") with Response
  case object NotFoundHistoryException extends ClientException(404, "NotFoundHistoryException", "NotFoundHistoryException") with Response
  case object NotFoundCategoryException extends ClientException(404, "NotFoundCategoryException", "NotFoundCategoryException") with Response
  case object NotFoundStockException extends ClientException(404, "NotFoundStockException", "NotFoundStockException") with Response
  case object TooManyCategoryException extends ClientException(409, "TooManyPortfolioException", "Too many portfolio") with Response
  case object TooManyStockException extends ClientException(409, "TooManyPortfolioException", "Too many portfolio") with Response


  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventShards[Event] = AggregateEventTag.sharded[Event](numShards = 64)
  }

  case class PortfolioCreated(portfolioId: PortfolioId, owner: UserId, name: String, usaCashFlowHistory: CashFlowHistory, koreaCashHistory: CashFlowHistory, updateTimestamp: Long) extends Event
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
  case class CashFlowHistoryAdded(cashFlowHistory: CashFlowHistory, updateTimestamp: Long) extends Event
  object CashFlowHistoryAdded{
    implicit val format:Format[CashFlowHistoryAdded] = Json.format
  }
  case class CashFlowHistoryUpdated(lastCashFlowHistory: CashFlowHistory, newCashFlowHistory: CashFlowHistory, updateTimestamp: Long) extends Event
  object CashFlowHistoryUpdated{
    implicit val format:Format[CashFlowHistoryUpdated] = Json.format
  }

  case class CashFlowHistoryDeleted(cashFlowHistory: CashFlowHistory, updateTimestamp: Long) extends Event
  object CashFlowHistoryDeleted{
    implicit val format:Format[CashFlowHistoryDeleted] = Json.format
  }
  case class StockAdded(stock: Stock, category: Category, stockHolding: StockHolding, cashFlowHistories: Seq[CashFlowHistory], updateTimestamp: Long) extends Event
  object StockAdded{
    implicit val format:Format[StockAdded] = Json.format
  }
  case class TradeHistoryAdded(historySet: HistorySet, updateTimestamp: Long) extends Event
  object TradeHistoryAdded{
    implicit val format:Format[TradeHistoryAdded] = Json.format
  }
  case class TradeHistoryDeleted(tradeHistory: TradeHistory, cashFlowHistory: CashFlowHistory, updateTimestamp: Long) extends Event
  object TradeHistoryDeleted{
    implicit val format:Format[TradeHistoryDeleted] = Json.format
  }
  case class TradeHistoryUpdated(lastHistorySet: HistorySet, newHistorySet: HistorySet, updateTimestamp: Long) extends Event
  object TradeHistoryUpdated{
    implicit val format:Format[TradeHistoryUpdated] = Json.format
  }
  case class StockDeleted(stock: Stock, category: Category, stockHolding: StockHolding, updateTimestamp: Long) extends Event
  object StockDeleted{
    implicit val format:Format[StockDeleted] = Json.format
  }
  case class StockCategoryUpdated(stock: Stock, lastCategory: Category, newCategory: Category, updateTimestamp: Long) extends Event
  object StockCategoryUpdated{
    implicit val format:Format[StockCategoryUpdated] = Json.format
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
    JsonSerializer[CashFlowHistoryAdded],
    JsonSerializer[CashFlowHistoryUpdated],
    JsonSerializer[CashFlowHistoryDeleted],
    JsonSerializer[StockAdded],
    JsonSerializer[TradeHistoryAdded],
    JsonSerializer[TradeHistoryDeleted],
    JsonSerializer[TradeHistoryUpdated],
    JsonSerializer[StockDeleted],
    JsonSerializer[StockCategoryUpdated],
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
    case CreatePortfolio(portfolioId, owner, name, usaCash, koreaCash, replyTo) => onCreatePortfolio(portfolioId, owner, name, usaCash, koreaCash, replyTo)
    case DeletePortfolio(owner, replyTo) => onDeletePortfolio(owner, replyTo)
    case GetPortfolio(replyTo) => onGetPortfolio(replyTo)
    case AddCategory(owner, category, replyTo) => onAddCategory(owner, category, replyTo)
    case UpdateGoalAssetRatio(owner, goalAssetRatio, assetCategory, replyTo) => onUpdateGoalAssetRatio(owner, goalAssetRatio, assetCategory, replyTo)
    case AddCashFlowHistory(owner, cashFlowHistory, replyTo) => onAddCashFlowHistory(owner, cashFlowHistory, replyTo)
    case UpdateCashFlowHistory(owner, cashFlowHistory, replyTo) => onUpdateCashFlowHistory(owner, cashFlowHistory, replyTo)
    case DeleteCashFlowHistory(owner, country, cashFlowHistoryId, replyTo) => onDeleteCashFlowHistory(owner, country, cashFlowHistoryId, replyTo)
    case AddStock(owner, stock, category, historySets, replyTo) => onAddStock(owner, stock, category, historySets, replyTo)
    case AddTradeHistory(owner, historySet, replyTo) => onAddTradeHistory(owner, historySet, replyTo)
    case DeleteTradeHistory(owner, stock, tradeHistoryId, replyTo) => onDeleteTradeHistory(owner, stock, tradeHistoryId, replyTo)
    case UpdateTradeHistory(owner, historySet, replyTo) => onUpdateTradeHistory(owner, historySet, replyTo)
    case DeleteStock(owner, stock, category, replyTo) => onDeleteStock(owner, stock, category, replyTo)
    case UpdateStockCategory(owner, stock, lastCategory, newCategory, replyTo) => onUpdateStockCategory(owner, stock, lastCategory, newCategory, replyTo)
  }

  private def onCreatePortfolio(portfolioId: PortfolioId, owner: UserId, name: String
                                , usaCash: BigDecimal, koreaCash: BigDecimal, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    foldState(_ => Effect.reply(replyTo)(AlreadyPortfolioException)){
      val usaHistory = CashFlowHistory(UUID.randomString, FlowType.DEPOSIT, Country.USA, usaCash, Timestamp.nowDate)
      val koreaHistory = CashFlowHistory(UUID.randomString, FlowType.DEPOSIT, Country.KOREA, koreaCash, Timestamp.nowDate)

      Effect.persist(PortfolioCreated(portfolioId, owner, name, usaHistory, koreaHistory, Timestamp.now))
        .thenReply(replyTo)(s=>CreatedResponse(portfolioId, name, usaHistory, koreaHistory,  s.state.get.updateTimestamp))}

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
      goalAssetRatio.isLimitCategorySize match {
        case false =>
          goalAssetRatio.isValid match {
            case true => ((assetCategory.getCategories -- goalAssetRatio.getCategories).size == 0 &&
              assetCategory.getAssets._1.toSet == state.getHoldingAssets._1 &&
              assetCategory.getAssets._2.toSet == state.getHoldingAssets._2) match {
              case true => Effect.persist(GoalAssetRatioUpdated(goalAssetRatio, assetCategory, Timestamp.now))
                .thenReply(replyTo)(e => TimestampResponse(e.state.get.updateTimestamp))
              case false => Effect.reply(replyTo)(InvalidParameterException)
            }
            case false => Effect.reply(replyTo)(InvalidParameterException)
          }
        case true => Effect.reply(replyTo)(TooManyCategoryException)
      }

    }
  private def onAddCashFlowHistory(owner: UserId, cashFlowHistory: CashFlowHistory
                                   , replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.getHoldingCash(cashFlowHistory.country) match {
        case None => Effect.reply(replyTo)(InvalidParameterException)
        case Some(cash) => cash.containHistory(cashFlowHistory) match {
          case true => Effect.reply(replyTo)(InvalidParameterException)
          case false => Effect.persist(CashFlowHistoryAdded(cashFlowHistory, Timestamp.now))
            .thenReply(replyTo)(e =>
              CashFlowHistoryAddedResponse(e.state.get.getHoldingCash(cashFlowHistory.country).get
                , e.state.get.updateTimestamp))
        }
      }
    }
  private def onUpdateCashFlowHistory(owner: UserId, cashFlowHistory: CashFlowHistory
                                      , replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.getHoldingCash(cashFlowHistory.country) match {
        case None => Effect.reply(replyTo)(InvalidParameterException)
        case Some(cash) => cash.findHistory(cashFlowHistory.id) match {
            case Some(lastHistory) => Effect.persist(CashFlowHistoryUpdated(lastHistory, cashFlowHistory, Timestamp.now))
              .thenReply(replyTo)(e =>
                CashFlowHistoryUpdatedResponse(e.state.get.getHoldingCash(cashFlowHistory.country).get
                  , e.state.get.updateTimestamp))
            case None => Effect.reply(replyTo)(NotFoundHistoryException)
          }
        }
      }
  private def onDeleteCashFlowHistory(owner: UserId, country: Country, cashFlowHistoryId: String
                                      , replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.getHoldingCash(country) match {
        case None => Effect.reply(replyTo)(InvalidParameterException)
        case Some(cash) => cash.findHistory(cashFlowHistoryId) match {
            case Some(cashFlowHistory) => Effect.persist(CashFlowHistoryDeleted(cashFlowHistory, Timestamp.now))
              .thenReply(replyTo)(e =>
                CashFlowHistoryDeletedResponse(e.state.get.getHoldingCash(country).get
                  , e.state.get.updateTimestamp))
            case None => Effect.reply(replyTo)(NotFoundHistoryException)
          }
        }
      }

  private def onAddStock(owner: UserId, stock: Stock, category: Category
                         , historySets: Seq[HistorySet], replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.isLimitStockSize match {
        case false => state.containStock(stock) match {
            case true => Effect.reply(replyTo)(AlreadyStockException)
            case false => state.containCategory(category) match {
              case true =>
                val sortedSets = historySets.sortBy(_.tradeHistory).reverse
                sortedSets.foldLeft(StockHolding.empty(stock).asRight[StockHolding]){
                  (holding, set) => holding match {
                    case Right(value) => value.addHistory(set.tradeHistory)
                    case Left(_) => holding
                  }
                } match {
                  case Right(holding) => Effect.persist(StockAdded(stock ,category, holding
                    , sortedSets.map(_.cashFlowHistory), Timestamp.now))
                    .thenReply(replyTo)(e =>
                      StockAddedResponse(e.state.get.getHoldingStock(stock).get
                        , e.state.get.getHoldingCash(stock.country).get, e.state.get.updateTimestamp))
                  case Left(_) => Effect.reply(replyTo)(InvalidParameterException)
                }
              case false => Effect.reply(replyTo)(NotFoundCategoryException)
            }
          }
        case true => Effect.reply(replyTo)(TooManyStockException)
      }
    }
  private def onAddTradeHistory(owner: UserId, historySet: HistorySet
                                , replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.getHoldingStock(historySet.tradeHistory.stock) match {
        case Some(holding) => holding.addHistory(historySet.tradeHistory) match {
          case Right(_) => Effect.persist(TradeHistoryAdded(historySet, Timestamp.now))
            .thenReply(replyTo)(e =>
              TradeHistoryAddedResponse(e.state.get.getHoldingStock(historySet.tradeHistory.stock).get
                , e.state.get.getHoldingCash(historySet.tradeHistory.stock.country).get, e.state.get.updateTimestamp))
          case Left(_) => Effect.reply(replyTo)(InvalidParameterException)
        }
        case None => Effect.reply(replyTo)(NotFoundStockException)
      }
    }
  def onDeleteTradeHistory(owner: UserId, stock: Stock, tradeHistoryId: String, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.getHoldingStock(stock) match {
        case Some(holding) => holding.findHistory(tradeHistoryId) match {
          case Some(tradeHistory) => holding.removeHistory(tradeHistory) match {
              case Right(_) => state.getHoldingCash(stock.country) match {
                  case Some(cash) => cash.findHistory(tradeHistory.cashHistoryId) match {
                      case Some(cashFlowHistory) =>
                        Effect.persist(TradeHistoryDeleted(tradeHistory, cashFlowHistory, Timestamp.now))
                          .thenReply(replyTo)(e =>
                            TradeHistoryDeletedResponse(e.state.get.getHoldingStock(stock).get
                              , e.state.get.getHoldingCash(stock.country).get, e.state.get.updateTimestamp))
                      case None => Effect.reply(replyTo)(InvalidParameterException)
                    }
                  case None => Effect.reply(replyTo)(InvalidParameterException)
                }
              case Left(_) => Effect.reply(replyTo)(InvalidParameterException)
            }
          case None => Effect.reply(replyTo)(NotFoundHistoryException)
        }
        case None => Effect.reply(replyTo)(NotFoundStockException)
      }
    }
  private def onUpdateTradeHistory(owner: UserId, historySet: HistorySet, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.getHoldingStock(historySet.tradeHistory.stock) match {
        case Some(holding) => holding.findHistory(historySet.tradeHistory.id) match {
          case Some(tradeHistory) => holding.removeHistory(tradeHistory).fold(i=>i, i=>i)
            .addHistory(historySet.tradeHistory) match {
              case Right(_) => Effect.persist(TradeHistoryUpdated(HistorySet.apply(tradeHistory), historySet, Timestamp.now))
                .thenReply(replyTo)(e =>
                  TradeHistoryUpdatedResponse(e.state.get.getHoldingStock(historySet.tradeHistory.stock).get
                    , e.state.get.getHoldingCash(historySet.tradeHistory.stock.country).get, e.state.get.updateTimestamp))
              case Left(_) => Effect.reply(replyTo)(InvalidParameterException)
            }
          case None => Effect.reply(replyTo)(NotFoundHistoryException)
        }
        case None => Effect.reply(replyTo)(NotFoundStockException)
      }
    }

  private def onDeleteStock(owner: UserId, stock: Stock, category: Category, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      (state.containAssetCategory(category, stock) , state.getHoldingStock(stock)) match {
        case (true, Some(holding)) => Effect.persist(StockDeleted(stock, category, holding, Timestamp.now))
          .thenReply(replyTo)(e => StockDeletedResponse(e.state.get.getHoldingCash(stock.country).get, e.state.get.updateTimestamp))
        case _ => Effect.reply(replyTo)(NotFoundStockException)
      }
    }
  private def onUpdateStockCategory(owner: UserId, stock: Stock, lastCategory: Category
                                    , newCategory: Category, replyTo: ActorRef[Response]): ReplyEffect[Event, PortfolioEntity] =
    funcWithOwner(owner, replyTo){ state =>
      state.containCategory(newCategory) match {
        case true => state.containAssetCategory(lastCategory, stock) match {
            case true => Effect.persist(StockCategoryUpdated(stock, lastCategory, newCategory, Timestamp.now))
              .thenReply(replyTo)(e => TimestampResponse(e.state.get.updateTimestamp))
            case false => Effect.reply(replyTo)(NotFoundStockException)
          }
        case false => Effect.reply(replyTo)(NotFoundCategoryException)
      }
    }

  def applyEvent(evt: Event): PortfolioEntity = evt match {
    case PortfolioCreated(portfolioId, owner, name, usaCashFlowHistory, koreaCashHistory, updateTimestamp) => onPortfolioCreated(portfolioId, owner, name, usaCashFlowHistory, koreaCashHistory, updateTimestamp)
    case PortfolioDeleted(portfolioId) => onPortfolioDeleted(portfolioId)
    case CategoryAdded(category, updateTimestamp) => onCategoryAdded(category, updateTimestamp)
    case GoalAssetRatioUpdated(goalAssetRatio, assetCategory, updateTimestamp) => onGoalAssetRatioUpdated(goalAssetRatio, assetCategory, updateTimestamp)
    case CashFlowHistoryAdded(cashFlowHistory, updateTimestamp) => onCashFlowHistoryAdded(cashFlowHistory, updateTimestamp)
    case CashFlowHistoryUpdated(lastCashFlowHistory, newCashFlowHistory, updateTimestamp) => onCashFlowHistoryUpdated(lastCashFlowHistory, newCashFlowHistory, updateTimestamp)
    case CashFlowHistoryDeleted(cashFlowHistory, updateTimestamp) => onCashFlowHistoryDeleted(cashFlowHistory, updateTimestamp)
    case StockAdded(stock, category, stockHolding, cashFlowHistories, updateTimestamp) => onStockAdded(stock, category, stockHolding, cashFlowHistories, updateTimestamp)
    case TradeHistoryAdded(historySet, updateTimestamp) => onTradeHistoryAdded(historySet, updateTimestamp)
    case TradeHistoryDeleted(tradeHistory, cashFlowHistory, updateTimestamp) => onTradeHistoryDeleted(tradeHistory, cashFlowHistory, updateTimestamp)
    case TradeHistoryUpdated(lastHistorySet, newHistorySet, updateTimestamp) => onTradeHistoryUpdated(lastHistorySet, newHistorySet, updateTimestamp)
    case StockDeleted(stock, category, stockHolding, updateTimestamp) => onStockDeleted(stock, category, stockHolding, updateTimestamp)
    case StockCategoryUpdated(stock, lastCategory, newCategory, updateTimestamp) => onStockCategoryUpdated(stock, lastCategory, newCategory, updateTimestamp)
  }

  private def onPortfolioCreated(portfolioId: PortfolioId, owner: UserId, name: String, usaCashFlowHistory: CashFlowHistory, koreaCashHistory: CashFlowHistory, updateTimestamp: Long): PortfolioEntity =
    copy(Some(PortfolioState(portfolioId, name, updateTimestamp, owner, GoalAssetRatio.empty, AssetCategory.empty, Holdings.empty.addCashHistory(usaCashFlowHistory).addCashHistory(koreaCashHistory))))
  private def onPortfolioDeleted(portfolioId: PortfolioId): PortfolioEntity = copy(None)
  private def onCategoryAdded(category: Category, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.addCategory(category).updateTimestamp(updateTimestamp)))
  private def onGoalAssetRatioUpdated(goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory, updateTimestamp: Long): PortfolioEntity =
    copy(Some(state.get.copy(goalAssetRatio = goalAssetRatio, assetCategory = assetCategory, updateTimestamp = updateTimestamp)))
  private def onCashFlowHistoryAdded(cashFlowHistory: CashFlowHistory, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.addCashHistory(cashFlowHistory).updateTimestamp(updateTimestamp)))
  private def onCashFlowHistoryUpdated(lastCashFlowHistory: CashFlowHistory, newCashFlowHistory: CashFlowHistory, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.removeCashHistory(lastCashFlowHistory).addCashHistory(newCashFlowHistory).updateTimestamp(updateTimestamp)))
  private def onCashFlowHistoryDeleted(cashFlowHistory: CashFlowHistory, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.removeCashHistory(cashFlowHistory).updateTimestamp(updateTimestamp)))
  private def onStockAdded(stock: Stock, category: Category, stockHolding: StockHolding, cashFlowHistories: Seq[CashFlowHistory], updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.addAssetCategory(category, stock).addStockHolding(stockHolding).addCashHistories(stock.country, cashFlowHistories).updateTimestamp(updateTimestamp)))
  private def onTradeHistoryAdded(historySet: HistorySet, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.addTradeHistory(historySet.tradeHistory).addCashHistory(historySet.cashFlowHistory).updateTimestamp(updateTimestamp)))
  private def onTradeHistoryDeleted(tradeHistory: TradeHistory, cashFlowHistory: CashFlowHistory, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.removeTradeHistory(tradeHistory).removeCashHistory(cashFlowHistory).updateTimestamp(updateTimestamp)))
  private def onTradeHistoryUpdated(lastHistorySet: HistorySet, newHistorySet: HistorySet, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.removeTradeHistory(lastHistorySet.tradeHistory).addTradeHistory(newHistorySet.tradeHistory)
      .removeCashHistory(lastHistorySet.cashFlowHistory).addCashHistory(newHistorySet.cashFlowHistory).updateTimestamp(updateTimestamp)))
  private def onStockDeleted(stock: Stock, category: Category, stockHolding: StockHolding, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.removeAssetCategory(category, stock).removeStockHolding(stock).removeCashHistories(stock.country, stockHolding.tradeHistories.map(h=>CashFlowHistory(h))).updateTimestamp(updateTimestamp)))
  private def onStockCategoryUpdated(stock: Stock, lastCategory: Category, newCategory: Category, updateTimestamp: Long): PortfolioEntity =
    copy(state.map(_.removeAssetCategory(lastCategory, stock).addAssetCategory(newCategory, stock).updateTimestamp(updateTimestamp)))


}
