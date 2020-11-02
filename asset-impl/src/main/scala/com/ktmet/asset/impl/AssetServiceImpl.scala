package com.ktmet.asset.impl

import java.net.URLDecoder

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.asset.collector.api.{CollectorService, Country, KrwUsd, Market, NowPrice, Stock}
import com.ktmet.asset.api.{AssetCategory, AssetService, AutoCompleteMessage, BuyTradeHistory, CashFlowHistory, CashHolding, CashHoldingMap, CashRatio, Category, CategorySet, GoalAssetRatio, HistorySet, Holdings, PortfolioId, PortfolioState, SellTradeHistory, StockHolding, StockHoldingMap, StockRatio, TradeHistory, UserId}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import akka.actor.typed.scaladsl.adapter._
import com.ktmet.asset.impl.actor.{NowPriceActor, StockAutoCompleter}
import akka.actor.typed.scaladsl.AskPattern._
import akka.serialization.{SerializationExtension, Serializers}
import com.asset.collector.api.Country.Country
import com.ktmet.asset.api.CashFlowHistory.FlowType
import com.ktmet.asset.api.TradeHistory.TradeType
import com.ktmet.asset.api.message.{AddingCategoryMessage, AddingStockMessage, AddingTradeHistoryMessage, CreatingPortfolioMessage, DeletingStockMessage, DeletingTradeHistoryMessage, PortfolioCreatedMessage, StockAddedMessage, StockDeletedMessage, TimestampMessage, TradeHistoryAddedMessage, TradeHistoryDeletedMessage, UpdatingGoalAssetRatioMessage}
import com.ktmet.asset.common.api.ClientException
import com.ktmet.asset.impl.actor.StockAutoCompleter.SearchResponse
import com.ktmet.asset.impl.entity.{PortfolioEntity, UserEntity}
import com.lightbend.lagom.scaladsl.api.transport.{BadRequest, ResponseHeader}
import com.lightbend.lagom.scaladsl.server.ServerServiceCall
import io.jvm.uuid._








class AssetServiceImpl(protected val clusterSharding: ClusterSharding,
                       protected val system: ActorSystem,
                       protected val wsClient: WSClient)
                      (implicit protected val  ec: ExecutionContext
                       , implicit protected val timeout:Timeout
                       , implicit val collectorService: CollectorService) extends UserServiceImplPart {

  val stockAutoCompleter = system.spawn(StockAutoCompleter(), "stockAutoCompleter")
  val nowPriceActor = system.spawn(NowPriceActor(), "nowPriceActor")

  override def autoCompleteStock(prefix:String): ServiceCall[NotUsed, AutoCompleteMessage] = authenticate{ _ =>
    ServerServiceCall{ (_,_) =>
      stockAutoCompleter.ask[StockAutoCompleter.Response](reply => StockAutoCompleter.Search(URLDecoder.decode(prefix, "UTF-8"), reply))
        .collect{
          case StockAutoCompleter.SearchResponse(koreaStocks, usaStocks) =>
            (ResponseHeader.Ok.withStatus(200), AutoCompleteMessage(koreaStocks.toSeq, usaStocks.toSeq))
          case StockAutoCompleter.Initializing =>
            (ResponseHeader.Ok.withStatus(204), AutoCompleteMessage.empty)
        }
    }
  }

  override def getNowPrice(code: String): ServiceCall[NotUsed, NowPrice] = authenticate { _ =>
    ServerServiceCall{ (_,_) =>
      nowPriceActor.ask[NowPriceActor.Response](reply => NowPriceActor.GetPrice(code, reply))
        .collect{
          case NowPriceActor.PriceResponse(price) => (ResponseHeader.Ok.withStatus(200), price)
          case NowPriceActor.NotFoundStock => throw new ClientException(404, "NotFoundStock", "")
        }
    }
  }

  override def getNowKrwUsd: ServiceCall[NotUsed, KrwUsd] = authenticate { _ =>
    ServerServiceCall{ (_,_) =>
      nowPriceActor.ask[NowPriceActor.Response](reply => NowPriceActor.GetKrwUsd(reply))
        .collect{
          case NowPriceActor.KrwUsdResponse(krwUsd) => (ResponseHeader.Ok.withStatus(200), krwUsd)
        }
    }
  }

  override def createPortfolio: ServiceCall[CreatingPortfolioMessage, PortfolioCreatedMessage] = authenticate{ userId =>
    ServerServiceCall{ (_, createPortfolioMessage) =>
      if(createPortfolioMessage.name.size > 10) throw BadRequest("")

      val portfolioId = PortfolioId(UUID.random.string)
      for {
        _ <- userEntityRef(userId).ask[UserEntity.Response](reply => UserEntity.AddPortfolio(portfolioId, reply))
            .collect{
              case UserEntity.Yes => None
              case UserEntity.TooManyPortfolioException => throw UserEntity.TooManyPortfolioException
            }
        r <- portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
                PortfolioEntity.CreatePortfolio(portfolioId, userId, createPortfolioMessage.name
                  , createPortfolioMessage.usaCash, createPortfolioMessage.koreaCash, reply))
            .collect{
              case PortfolioEntity.CreatedResponse(portfolioId, name, usaCashFlowHistory, koreaCashFlowHistory, updateTimestamp) =>
                PortfolioCreatedMessage(portfolioId.value, usaCashFlowHistory, koreaCashFlowHistory, updateTimestamp)
              case m: ClientException =>
                userEntityRef(userId).ask[UserEntity.Response](reply => UserEntity.DeletePortfolio(portfolioId, reply))
                throw m
            }
      } yield {
        (ResponseHeader.Ok.withStatus(201), r)
      }
    }
  }

  override def deletePortfolio(portfolioId: String): ServiceCall[NotUsed, Done] = authenticate{ userId =>
    ServerServiceCall{ (_, _) =>
      for {
        _ <- portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
                PortfolioEntity.DeletePortfolio(userId, reply))
            .collect{
              case PortfolioEntity.Yes => None
              case m: ClientException => throw m
            }
        _ <- userEntityRef(userId).ask[UserEntity.Response](reply =>
                UserEntity.DeletePortfolio(PortfolioId(portfolioId), reply))
      } yield {
        (ResponseHeader.Ok.withStatus(204), Done)
      }
    }
  }

  override def getPortfolio(portfolioId: String): ServiceCall[NotUsed, PortfolioState] =  authenticate{ userId =>
    ServerServiceCall{ (_, _) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
        .collect{
          case PortfolioEntity.PortfolioResponse(portfolioState) =>
            (ResponseHeader.Ok.withStatus(200), portfolioState)
          case m: ClientException => throw m
        }
    }
  }

  override def addCategory(portfolioId: String): ServiceCall[AddingCategoryMessage, TimestampMessage] = authenticate{ userId =>
    ServerServiceCall{ (_, addingCategoryMessage) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply => PortfolioEntity.AddCategory(userId, Category(addingCategoryMessage.category), reply))
        .collect{
          case PortfolioEntity.TimestampResponse(updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), TimestampMessage(updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def updateGoalAssetRatio(portfolioId: String): ServiceCall[UpdatingGoalAssetRatioMessage, TimestampMessage] = authenticate{ userId =>
    ServerServiceCall{ (_, updatingGoalAssetRatioMessage) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.UpdateGoalAssetRatio(userId
          , GoalAssetRatio.messageToObject(updatingGoalAssetRatioMessage.stockRatios, updatingGoalAssetRatioMessage.cashRatios)
          , AssetCategory.messageToObject(updatingGoalAssetRatioMessage.stockCategory, updatingGoalAssetRatioMessage.cashCategory)
          , reply))
        .collect{
          case PortfolioEntity.TimestampResponse(updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), TimestampMessage(updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def addStock(portfolioId: String): ServiceCall[AddingStockMessage, StockAddedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, addingStockMessage) =>
      val historySets = addingStockMessage.tradingHistories.map{ history =>
        val (tradeId, cashId) = (UUID.randomString, UUID.randomString)
        history.tradeType match {
          case TradeType.BUY =>
            HistorySet(BuyTradeHistory(tradeId, history.tradeType
              , addingStockMessage.stock, history.amount, history.price, history.timestamp, cashId))
          case TradeType.SELL =>
            HistorySet(SellTradeHistory(tradeId, history.tradeType
              , addingStockMessage.stock, history.amount, history.price, history.timestamp, cashId, BigDecimal(0)))
        }
      }

      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.AddStock(userId
          , addingStockMessage.stock, Category(addingStockMessage.category), historySets, reply))
        .collect{
          case PortfolioEntity.StockAddedResponse(stockHolding, cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), StockAddedMessage(stockHolding, cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def deleteStock(portfolioId: String): ServiceCall[DeletingStockMessage, StockDeletedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, deletingStockMessage) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.DeleteStock(userId
          , deletingStockMessage.stock, Category(deletingStockMessage.category), reply))
        .collect{
          case PortfolioEntity.StockDeletedResponse(cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), StockDeletedMessage(cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def addTradeHistory(portfolioId: String): ServiceCall[AddingTradeHistoryMessage, TradeHistoryAddedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, addingTradeHistoryMessage) =>
      val (tradeId, cashId) = (UUID.randomString, UUID.randomString)
      val historySet = addingTradeHistoryMessage.tradeType match {
        case TradeType.BUY =>
          HistorySet(BuyTradeHistory(tradeId, addingTradeHistoryMessage.tradeType
            , addingTradeHistoryMessage.stock, addingTradeHistoryMessage.amount, addingTradeHistoryMessage.price, addingTradeHistoryMessage.timestamp, cashId))
        case TradeType.SELL =>
          HistorySet(SellTradeHistory(tradeId, addingTradeHistoryMessage.tradeType
            , addingTradeHistoryMessage.stock, addingTradeHistoryMessage.amount, addingTradeHistoryMessage.price, addingTradeHistoryMessage.timestamp, cashId, BigDecimal(0)))
      }

      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.AddTradeHistory(userId, historySet, reply))
        .collect{
          case PortfolioEntity.TradeHistoryAddedResponse(stockHolding, cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), TradeHistoryAddedMessage(stockHolding, cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def deleteTradeHistory(portfolioId: String): ServiceCall[DeletingTradeHistoryMessage, TradeHistoryDeletedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, deletingTradeHistoryMessage) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.DeleteTradeHistory(userId, deletingTradeHistoryMessage.stock
          , deletingTradeHistoryMessage.tradeHistoryId, reply))
        .collect{
          case PortfolioEntity.TradeHistoryDeletedResponse(stockHolding, cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), TradeHistoryDeletedMessage(stockHolding, cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def test: ServiceCall[NotUsed, Done] =
    ServerServiceCall{ (_, updatingGoalAssetRatioMessage) =>

      val serialization = SerializationExtension(system)

//      case class GoalAssetRatio(stockRatios: Map[Category, List[StockRatio]]
//                                , cashRatios: Map[Category, List[CashRatio]])
//      case class AssetCategory(stockCategory: Map[Category, List[StockRatio]], cashCategory: Map[Category, List[StockRatio]])
      // Have something to serialize
//      val original = PortfolioEntity.PortfolioResponse(PortfolioState.empty)
      val asset = AssetCategory(Map(Category("10")->List(Stock(Country.USA, Market.ETF, "123","13"))), Map(Category.CashCategory -> List(Country.USA, Country.KOREA)))

      val stock = Stock(Country.USA, Market.ETF, "123","13")
      val goal = GoalAssetRatio(Map(Category("10")->List(StockRatio(Stock(Country.USA, Market.ETF, "123","13"), 10))), Map(Category.CashCategory ->List(CashRatio(Country.USA, 10))))
      val tradeHistory = SellTradeHistory("123", TradeType.BUY, Stock(Country.USA, Market.ETF, "123","13"), 10, BigDecimal(10),  123, "123", BigDecimal(10))
      val cashHistory = CashFlowHistory("123", FlowType.SOLDAMOUNT, Country.USA, BigDecimal(10), 123)
      val stockHolding = StockHolding(Stock(Country.USA, Market.ETF, "123","13"), 10, BigDecimal(10), BigDecimal(10),  List(tradeHistory))
      val stockHoldingMap = StockHoldingMap(Map(stock -> stockHolding))
      val cashHolding = CashHolding(Country.USA, BigDecimal(0), List(cashHistory))
      val state = PortfolioState(PortfolioId("123"), "123", 0, UserId("123"), goal, asset, Holdings(stockHoldingMap, CashHoldingMap(Map(Country.USA->cashHolding))))
//      val original = PortfolioEntity.PortfolioResponse(state)
      val original = state
      // Turn it into bytes, and retrieve the serializerId and manifest, which are needed for deserialization
      val bytes = serialization.serialize(original).get
      val serializerId = serialization.findSerializerFor(original).identifier
      val manifest = Serializers.manifestFor(serialization.findSerializerFor(original), original)

      // Turn it back into an object
      val back = serialization.deserialize(bytes, serializerId, manifest).get
      println(back)

      println(back)


      Future.successful(ResponseHeader.Ok.withStatus(200), Done)
    }

}
