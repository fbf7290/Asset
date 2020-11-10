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
import cats.{Functor, Id}
import com.asset.collector.api.Country.Country
import com.ktmet.asset.api.CashFlowHistory.FlowType
import com.ktmet.asset.api.TradeHistory.TradeType
import com.ktmet.asset.api.message.PortfolioStatusMessage.StockStatus
import com.ktmet.asset.api.message.{AddingCashFlowHistory, AddingCategoryMessage, AddingStockMessage, AddingTradeHistoryMessage, CashFlowHistoryAddedMessage, CashFlowHistoryDeletedMessage, CashFlowHistoryUpdatedMessage, CreatingPortfolioMessage, DeletingCashFlowHistory, DeletingStockMessage, DeletingTradeHistoryMessage, PortfolioCreatedMessage, PortfolioMessage, PortfolioStatusMessage, PortfolioStockMessage, StockAddedMessage, StockCategoryUpdatedMessage, StockDeletedMessage, TimestampMessage, TradeHistoryAddedMessage, TradeHistoryDeletedMessage, TradeHistoryUpdatedMessage, UpdatingCashFlowHistory, UpdatingGoalAssetRatioMessage, UpdatingStockCategory, UpdatingTradeHistoryMessage}
import com.ktmet.asset.common.api.ClientException
import com.ktmet.asset.impl.actor.StockAutoCompleter.SearchResponse
import com.ktmet.asset.impl.entity.{PortfolioEntity, UserEntity}
import com.lightbend.lagom.scaladsl.api.transport.{BadRequest, ResponseHeader}
import com.lightbend.lagom.scaladsl.server.ServerServiceCall
import io.jvm.uuid._
import play.api.libs.json.Json


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
                PortfolioEntity.CreatePortfolio(portfolioId, userId, createPortfolioMessage.name, reply))
            .collect{
              case PortfolioEntity.CreatedResponse(portfolioId, name, updateTimestamp) =>
                PortfolioCreatedMessage(portfolioId.value, updateTimestamp)
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

  override def getPortfolio(portfolioId: String): ServiceCall[NotUsed, PortfolioMessage] =  authenticate{ userId =>
    ServerServiceCall{ (_, _) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
        .collect{
          case PortfolioEntity.PortfolioResponse(portfolioState) =>
            (ResponseHeader.Ok.withStatus(200), PortfolioMessage(portfolioState))
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
              , addingStockMessage.stock, history.amount, history.price, history.timestamp, None, None, cashId))
          case TradeType.SELL =>
            HistorySet(SellTradeHistory(tradeId, history.tradeType
              , addingStockMessage.stock, history.amount, history.price, history.timestamp, cashId, BigDecimal(0), BigDecimal(0)))
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
            , addingTradeHistoryMessage.stock, addingTradeHistoryMessage.amount, addingTradeHistoryMessage.price
            , addingTradeHistoryMessage.timestamp, None, None, cashId))
        case TradeType.SELL =>
          HistorySet(SellTradeHistory(tradeId, addingTradeHistoryMessage.tradeType
            , addingTradeHistoryMessage.stock, addingTradeHistoryMessage.amount, addingTradeHistoryMessage.price
            , addingTradeHistoryMessage.timestamp, cashId, BigDecimal(0), BigDecimal(0)))
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

  override def updateTradeHistory(portfolioId: String): ServiceCall[UpdatingTradeHistoryMessage, TradeHistoryUpdatedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, updatingTradeHistoryMessage) =>
      val historySet = updatingTradeHistoryMessage.tradeType match {
        case TradeType.BUY =>
          HistorySet(BuyTradeHistory(updatingTradeHistoryMessage.tradeHistoryId, updatingTradeHistoryMessage.tradeType
            , updatingTradeHistoryMessage.stock, updatingTradeHistoryMessage.amount, updatingTradeHistoryMessage.price
            , updatingTradeHistoryMessage.timestamp, None, None, UUID.randomString))
        case TradeType.SELL =>
          HistorySet(SellTradeHistory(updatingTradeHistoryMessage.tradeHistoryId, updatingTradeHistoryMessage.tradeType
            , updatingTradeHistoryMessage.stock, updatingTradeHistoryMessage.amount, updatingTradeHistoryMessage.price
            , updatingTradeHistoryMessage.timestamp, UUID.randomString, BigDecimal(0), BigDecimal(0)))
      }
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.UpdateTradeHistory(userId, historySet, reply))
        .collect{
          case PortfolioEntity.TradeHistoryUpdatedResponse(stockHolding, cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), TradeHistoryUpdatedMessage(stockHolding, cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def addCashFlowHistory(portfolioId: String): ServiceCall[AddingCashFlowHistory, CashFlowHistoryAddedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, addingCashFlowHistory) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.AddCashFlowHistory(userId, CashFlowHistory(UUID.randomString
          , addingCashFlowHistory.flowType, addingCashFlowHistory.country
          , addingCashFlowHistory.balance, addingCashFlowHistory.timestamp), reply))
        .collect{
          case PortfolioEntity.CashFlowHistoryAddedResponse(cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), CashFlowHistoryAddedMessage(cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def deleteCashFlowHistory(portfolioId: String): ServiceCall[DeletingCashFlowHistory, CashFlowHistoryDeletedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, deletingCashFlowHistory) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.DeleteCashFlowHistory(userId, deletingCashFlowHistory.country
          , deletingCashFlowHistory.cashFlowHistoryId, reply))
        .collect{
          case PortfolioEntity.CashFlowHistoryDeletedResponse(cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), CashFlowHistoryDeletedMessage(cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def updateCashFlowHistory(portfolioId: String): ServiceCall[UpdatingCashFlowHistory, CashFlowHistoryUpdatedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, updatingCashFlowHistory) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.UpdateCashFlowHistory(userId, CashFlowHistory(updatingCashFlowHistory.cashHistoryId
          , updatingCashFlowHistory.flowType, updatingCashFlowHistory.country
          , updatingCashFlowHistory.balance, updatingCashFlowHistory.timestamp), reply))
        .collect{
          case PortfolioEntity.CashFlowHistoryUpdatedResponse(cashHolding, updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), CashFlowHistoryUpdatedMessage(cashHolding, updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }

  override def updateStockCategory(portfolioId: String): ServiceCall[UpdatingStockCategory, StockCategoryUpdatedMessage] = authenticate { userId =>
    ServerServiceCall{ (_, updatingStockCategory) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.UpdateStockCategory(userId, updatingStockCategory.stock
          , Category(updatingStockCategory.lastCategory), Category(updatingStockCategory.newCategory), reply))
        .collect{
          case PortfolioEntity.TimestampResponse(updateTimestamp) =>
            (ResponseHeader.Ok.withStatus(200), StockCategoryUpdatedMessage(updateTimestamp))
          case m: ClientException => throw m
        }
    }
  }
//
//  override def getPortfolioStatus(portfolioId: String): ServiceCall[NotUsed, PortfolioStatusMessage] = authenticate { userId =>
//    ServerServiceCall{ (_, _) =>
//      def getPortfolio: Future[PortfolioState] =
//        portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
//          PortfolioEntity.GetPortfolio(reply))
//          .collect{
//            case PortfolioEntity.PortfolioResponse(portfolioState) => portfolioState
//            case m: ClientException => throw m
//          }
//
//      def getNowPricesAndKrwUsd(portfolioState: PortfolioState): Future[(Map[Stock, BigDecimal], BigDecimal)] =
//        nowPriceActor.ask[NowPriceActor.PricesAndKrwUsdResponse](reply =>
//          NowPriceActor.GetPricesAndKrwUsd(portfolioState.getHoldingAssets._1.toSeq, reply))
//          .map{ case NowPriceActor.PricesAndKrwUsdResponse(prices, krwUsd) =>
//            (prices.map { case (stock, maybePrice) =>
//              maybePrice match {
//                case Some(nowPrice) => stock -> nowPrice.price
//                case None => stock -> portfolioState.getHoldingStock(stock).get.tradeHistories.headOption.fold(BigDecimal(0))(_.price)
//              }
//            }, krwUsd.rate)
//          }
//      def getStockStatus(price: BigDecimal, stockHolding: StockHolding): StockStatus =
//        Functor[Id].map( stockHolding.tradeHistories.foldLeft((BigDecimal(0), List.empty[TradeHistory])) {
//          case ((realizedProfitBalance, histories), history) =>
//            history match {
//              case h: BuyTradeHistory =>
//                (realizedProfitBalance
//                  , h.copy(profitRate = Some((price / h.price - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100)
//                  , profitBalance = Some((price - h.price) * h.amount)):: histories)
//              case h: SellTradeHistory => (realizedProfitBalance + h.realizedProfitBalance, history :: histories)
//            }
//          }
//        ){ case (realizedBalance, histories) =>
//          StockStatus(stock = stockHolding.stock, amount = stockHolding.amount, avgPrice = stockHolding.avgPrice
//              , nowPrice = price
//              , profitBalance = if(stockHolding.avgPrice == 0 || stockHolding.amount == 0) BigDecimal(0)
//                                  else (price - stockHolding.avgPrice) * stockHolding.amount
//              , profitRate = if(stockHolding.avgPrice == 0 || stockHolding.amount == 0) BigDecimal(0)
//                           else (price / stockHolding.avgPrice - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100
//              , realizedProfitBalance = realizedBalance, boughtBalance = stockHolding.avgPrice * stockHolding.amount
//              , evaluatedBalance = price * stockHolding.amount, tradeHistories = histories.reverse)
//        }
//
//
//      for {
//        portfolioState <- getPortfolio
//        (nowPrices, krwUsd) <- getNowPricesAndKrwUsd(portfolioState)
//        stockStatus = portfolioState.getHoldingStocks.map.map{ case (stock, holding) =>
//                  stock -> getStockStatus(nowPrices.get(stock).get, holding)
//                }
//        cashStatus = portfolioState.getHoldingCashes.map
//      } yield {
//        var (evaluatedTotalAsset, profitBalance, realizedProfitBalance, boughtBalance, totalAsset) =
//          (BigDecimal(0), BigDecimal(0), BigDecimal(0), BigDecimal(0), BigDecimal(0))
//        stockStatus.map { case (stock, status) =>
//            val krwUsdRatio = stock.country match {
//              case Country.USA => krwUsd
//              case Country.KOREA => BigDecimal(1)
//            }
//            evaluatedTotalAsset += status.evaluatedBalance * krwUsdRatio
//            totalAsset += status.boughtBalance * krwUsdRatio
//            profitBalance += status.profitBalance * krwUsdRatio
//            realizedProfitBalance += status.realizedProfitBalance * krwUsdRatio
//            boughtBalance += status.boughtBalance * krwUsdRatio
//          }
//
//        cashStatus.map { case (cash, status) =>
//          val krwUsdRatio = cash match {
//            case Country.USA => krwUsd
//            case Country.KOREA => BigDecimal(1)
//          }
//          evaluatedTotalAsset += status.balance * krwUsdRatio
//          totalAsset += status.balance * krwUsdRatio
//        }
//
//        val stockRatios = portfolioState.getStockRatio.foldLeft(Map.empty[Category, List[PortfolioStatusMessage.StockRatio]]){
//          case (result, (category, stockRatios)) =>
//            result + (category -> stockRatios.foldLeft(List.empty[PortfolioStatusMessage.StockRatio]){
//              (result, ratio) => PortfolioStatusMessage.StockRatio(ratio.stock, ratio.ratio
//                , stockStatus.get(ratio.stock).fold(BigDecimal(0))(i =>
//                  if(evaluatedTotalAsset == 0) BigDecimal(0)
//                  else (i.evaluatedBalance/evaluatedTotalAsset - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100)) :: result
//              })
//        }
//        val cashRatios = portfolioState.getCashRatio.foldLeft(Map.empty[Category, List[PortfolioStatusMessage.CashRatio]]){
//          case (result, (category, cashRatios)) =>
//            result + (category -> cashRatios.foldLeft(List.empty[PortfolioStatusMessage.CashRatio]){
//              (result, ratio) => PortfolioStatusMessage.CashRatio(ratio.country, ratio.ratio
//                , cashStatus.get(ratio.country).fold(BigDecimal(0))(i =>
//                  if(evaluatedTotalAsset == 0) BigDecimal(0)
//                  else (i.balance/evaluatedTotalAsset - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100)) :: result
//            })
//        }
//
//        (ResponseHeader.Ok.withStatus(200), PortfolioStatusMessage( evaluatedTotalAsset
//          , profitBalance = profitBalance
//          , profitRate = if(totalAsset == 0) BigDecimal(0)
//                        else (evaluatedTotalAsset/totalAsset - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100
//          , realizedProfitBalance =  realizedProfitBalance
//          , boughtBalance = boughtBalance, assetCategory = portfolioState.assetCategory
//          , assetRatio = PortfolioStatusMessage.AssetRatio(stockRatios, cashRatios)
//          , cashStatus = cashStatus, stockStatus = stockStatus))
//      }
//    }
//  }


  override def getPortfolioStatus(portfolioId: String): ServiceCall[NotUsed, PortfolioStatusMessage] = authenticate { userId =>
    ServerServiceCall{ (_, _) =>
      def getPortfolio: Future[PortfolioState] =
        portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
          PortfolioEntity.GetPortfolio(reply))
          .collect{
            case PortfolioEntity.PortfolioResponse(portfolioState) => portfolioState
            case m: ClientException => throw m
          }

      def getNowPricesAndKrwUsd(portfolioState: PortfolioState): Future[(Map[Stock, BigDecimal], BigDecimal)] =
        nowPriceActor.ask[NowPriceActor.PricesAndKrwUsdResponse](reply =>
          NowPriceActor.GetPricesAndKrwUsd(portfolioState.getHoldingAssets._1.toSeq, reply))
          .map{ case NowPriceActor.PricesAndKrwUsdResponse(prices, krwUsd) =>
            (prices.map { case (stock, maybePrice) =>
              maybePrice match {
                case Some(nowPrice) => stock -> nowPrice.price
                case None => stock -> portfolioState.getHoldingStock(stock).get.tradeHistories.headOption.fold(BigDecimal(0))(_.price)
              }
            }, krwUsd.rate)
          }
      def getStockStatus(price: BigDecimal, stockHolding: StockHolding): StockStatus =
        Functor[Id].map( stockHolding.tradeHistories.foldLeft((BigDecimal(0), List.empty[TradeHistory])) {
          case ((realizedProfitBalance, histories), history) =>
            history match {
              case h: BuyTradeHistory =>
                (realizedProfitBalance
                  , h.copy(profitRate = Some((price / h.price - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100)
                  , profitBalance = Some((price - h.price) * h.amount)):: histories)
              case h: SellTradeHistory => (realizedProfitBalance + h.realizedProfitBalance, history :: histories)
            }
        }
        ){ case (realizedBalance, histories) =>
          StockStatus(stock = stockHolding.stock, amount = stockHolding.amount, avgPrice = stockHolding.avgPrice
            , nowPrice = price
            , profitBalance = if(stockHolding.avgPrice == 0 || stockHolding.amount == 0) BigDecimal(0)
            else (price - stockHolding.avgPrice) * stockHolding.amount
            , profitRate = if(stockHolding.avgPrice == 0 || stockHolding.amount == 0) BigDecimal(0)
            else (price / stockHolding.avgPrice - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100
            , realizedProfitBalance = realizedBalance, boughtBalance = stockHolding.avgPrice * stockHolding.amount
            , evaluatedBalance = price * stockHolding.amount, tradeHistories = histories.reverse)
        }


      for {
        portfolioState <- getPortfolio
        (nowPrices, krwUsd) <- getNowPricesAndKrwUsd(portfolioState)
        stockStatus = portfolioState.getHoldingStocks.map.map{ case (stock, holding) =>
          stock -> getStockStatus(nowPrices.get(stock).get, holding)
        }
        cashStatus = portfolioState.getHoldingCashes.map
      } yield {
        var (evaluatedTotalAsset, profitBalance, realizedProfitBalance, boughtBalance, totalAsset, evaluatedTotalAssetToRatio) =
          (BigDecimal(0), BigDecimal(0), BigDecimal(0), BigDecimal(0), BigDecimal(0), BigDecimal(0))
        stockStatus.map { case (stock, status) =>
          val krwUsdRatio = stock.country match {
            case Country.USA => krwUsd
            case Country.KOREA => BigDecimal(1)
          }
          evaluatedTotalAsset += status.evaluatedBalance * krwUsdRatio
          evaluatedTotalAssetToRatio += status.evaluatedBalance * krwUsdRatio
          totalAsset += status.boughtBalance * krwUsdRatio
          profitBalance += status.profitBalance * krwUsdRatio
          realizedProfitBalance += status.realizedProfitBalance * krwUsdRatio
          boughtBalance += status.boughtBalance * krwUsdRatio
        }

        cashStatus.map { case (cash, status) =>
          val krwUsdRatio = cash match {
            case Country.USA => krwUsd
            case Country.KOREA => BigDecimal(1)
          }
          evaluatedTotalAsset += status.balance * krwUsdRatio
          totalAsset += status.balance * krwUsdRatio
          if(status.balance > 0) evaluatedTotalAssetToRatio += status.balance * krwUsdRatio
        }

        val stockRatios = portfolioState.getStockRatio.foldLeft(Map.empty[Category, List[PortfolioStatusMessage.StockRatio]]){
          case (result, (category, stockRatios)) =>
            result + (category -> stockRatios.foldLeft(List.empty[PortfolioStatusMessage.StockRatio]){
              (result, ratio) =>
                val krwUsdRatio = ratio.stock.country match {
                  case Country.USA => krwUsd
                  case Country.KOREA => BigDecimal(1)
                }
                PortfolioStatusMessage.StockRatio(ratio.stock, ratio.ratio
                , stockStatus.get(ratio.stock).fold(BigDecimal(0))(i =>
                  if(evaluatedTotalAssetToRatio == 0) BigDecimal(0)
                  else ((i.evaluatedBalance * krwUsdRatio)/evaluatedTotalAssetToRatio - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100)) :: result
            })
        }
        val cashRatios = portfolioState.getCashRatio.foldLeft(Map.empty[Category, List[PortfolioStatusMessage.CashRatio]]){
          case (result, (category, cashRatios)) =>
            result + (category -> cashRatios.foldLeft(List.empty[PortfolioStatusMessage.CashRatio]){
              (result, ratio) =>
                val krwUsdRatio = ratio.country match {
                  case Country.USA => krwUsd
                  case Country.KOREA => BigDecimal(1)
                }
                PortfolioStatusMessage.CashRatio(ratio.country, ratio.ratio
                , cashStatus.get(ratio.country).fold(BigDecimal(0))(i =>
                  if(evaluatedTotalAssetToRatio == 0 || i.balance <= 0) BigDecimal(0)
                  else ((i.balance * krwUsdRatio)/evaluatedTotalAsset - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100)) :: result
            })
        }

        (ResponseHeader.Ok.withStatus(200), PortfolioStatusMessage( evaluatedTotalAsset
          , profitBalance = profitBalance
          , profitRate = if(totalAsset == 0) BigDecimal(0)
          else (evaluatedTotalAsset/totalAsset - 1).setScale(2, BigDecimal.RoundingMode.HALF_UP) * 100
          , realizedProfitBalance =  realizedProfitBalance
          , boughtBalance = boughtBalance, assetCategory = portfolioState.assetCategory
          , assetRatio = PortfolioStatusMessage.AssetRatio(stockRatios, cashRatios)
          , cashStatus = cashStatus, stockStatus = stockStatus))
      }
    }
  }

  override def getPortfolioStock(portfolioId: String, stock: String): ServiceCall[NotUsed, PortfolioStockMessage] = authenticate { userId =>
    ServerServiceCall{ (_, _) =>
      val stockObj = Json.parse(URLDecoder.decode(stock, "UTF-8")).as[Stock]
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
        PortfolioEntity.GetStock(stockObj, reply))
        .collect{
          case PortfolioEntity.StockResponse(stockHolding) =>
            (ResponseHeader.Ok.withStatus(200),  PortfolioStockMessage(stockHolding))
          case m: ClientException => throw m
        }
    }
  }


  override def test: ServiceCall[NotUsed, Done] =
    ServerServiceCall{ (_, updatingGoalAssetRatioMessage) =>


      val a = UpdatingGoalAssetRatioMessage(Map("123" -> List(StockRatio(Stock(Country.KOREA, Market.AMEX, "123", "123"), 1))),
        Map("123" -> List(CashRatio(Country.KOREA, 1))), Map("123"-> List(Stock(Country.KOREA, Market.AMEX, "123", "123"))),
        Map("123" -> List(Country.KOREA))
      )
      println(Json.toJson(a))

//      val serialization = SerializationExtension(system)
//
////      case class GoalAssetRatio(stockRatios: Map[Category, List[StockRatio]]
////                                , cashRatios: Map[Category, List[CashRatio]])
////      case class AssetCategory(stockCategory: Map[Category, List[StockRatio]], cashCategory: Map[Category, List[StockRatio]])
//      // Have something to serialize
////      val original = PortfolioEntity.PortfolioResponse(PortfolioState.empty)
//      val asset = AssetCategory(Map(Category("10")->List(Stock(Country.USA, Market.ETF, "123","13"))), Map(Category.CashCategory -> List(Country.USA, Country.KOREA)))
//
//      val stock = Stock(Country.USA, Market.ETF, "123","13")
//      val goal = GoalAssetRatio(Map(Category("10")->List(StockRatio(Stock(Country.USA, Market.ETF, "123","13"), 10))), Map(Category.CashCategory ->List(CashRatio(Country.USA, 10))))
//      val tradeHistory = SellTradeHistory("123", TradeType.BUY, Stock(Country.USA, Market.ETF, "123","13"), 10, BigDecimal(10),  123, "123", BigDecimal(10), BigDecimal(0))
//      val cashHistory = CashFlowHistory("123", FlowType.SOLDAMOUNT, Country.USA, BigDecimal(10), 123)
//      val stockHolding = StockHolding(Stock(Country.USA, Market.ETF, "123","13"), 10, BigDecimal(10), BigDecimal(10),  List(tradeHistory))
//      val stockHoldingMap = StockHoldingMap(Map(stock -> stockHolding))
//      val cashHolding = CashHolding(Country.USA, BigDecimal(0), List(cashHistory))
//      val state = PortfolioState(PortfolioId("123"), "123", 0, UserId("123"), goal, asset, Holdings(stockHoldingMap, CashHoldingMap(Map(Country.USA->cashHolding))))
////      val original = PortfolioEntity.PortfolioResponse(state)
//      val original = state
//      // Turn it into bytes, and retrieve the serializerId and manifest, which are needed for deserialization
//      val bytes = serialization.serialize(original).get
//      val serializerId = serialization.findSerializerFor(original).identifier
//      val manifest = Serializers.manifestFor(serialization.findSerializerFor(original), original)
//
//      // Turn it back into an object
//      val back = serialization.deserialize(bytes, serializerId, manifest).get
//      println(back)
//
//      println(back)


      Future.successful(ResponseHeader.Ok.withStatus(200), Done)
    }

}
// TODO 상장폐지 종목에 대한 처리