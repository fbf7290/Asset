package com.ktmet.asset.api.message

import com.asset.collector.api.Country.Country
import com.asset.collector.api.Stock
import com.ktmet.asset.api.CashFlowHistory.FlowType.FlowType
import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
import com.ktmet.asset.api.message.AddingStockMessage.AddingTradeHistory
import com.ktmet.asset.api.message.PortfolioStatusMessage.{AssetRatio, StockStatus}
import com.ktmet.asset.api.{AssetCategory, CashFlowHistory, CashHolding, CashRatio, Category, StockHolding, StockRatio, TradeHistory}
import play.api.libs.json.{Format, Json}

case class TimestampMessage(updateTimestamp: Long)
object TimestampMessage {
  implicit val format:Format[TimestampMessage] = Json.format
}

case class CreatingPortfolioMessage(name: String
                                    , usaCash: BigDecimal
                                    , koreaCash: BigDecimal)
object CreatingPortfolioMessage {
  implicit val format:Format[CreatingPortfolioMessage] = Json.format
}

case class PortfolioCreatedMessage(portfolioId: String
                                   , usaCashHistory: CashFlowHistory
                                   , koreaCashHistory: CashFlowHistory
                                   , updateTimestamp: Long)
object PortfolioCreatedMessage {
  implicit val format:Format[PortfolioCreatedMessage] = Json.format
}

case class AddingCategoryMessage(category: String)
object AddingCategoryMessage {
  implicit val format:Format[AddingCategoryMessage] = Json.format
}

case class UpdatingGoalAssetRatioMessage(stockRatios: Map[String, List[StockRatio]]
                                        , cashRatios: Map[String, List[CashRatio]]
                                        , stockCategory: Map[String, List[Stock]]
                                         , cashCategory: Map[String, List[Country]])
object UpdatingGoalAssetRatioMessage {
  implicit val format:Format[UpdatingGoalAssetRatioMessage] = Json.format
}

case class AddingStockMessage(stock: Stock
                              , category: String, tradingHistories: Seq[AddingTradeHistory])
object AddingStockMessage {
  implicit val format:Format[AddingStockMessage] = Json.format

  case class AddingTradeHistory(tradeType: TradeType
                                       , amount: Int
                                       , price: BigDecimal
                                       , timestamp: Long)
  object AddingTradeHistory {
    implicit val format:Format[AddingTradeHistory] = Json.format
  }
}
case class StockAddedMessage(stockHolding: StockHolding
                             , cashHolding: CashHolding, updateTimestamp: Long)
object StockAddedMessage {
  implicit val format:Format[StockAddedMessage] = Json.format
}
case class DeletingStockMessage(stock: Stock, category: String)
object DeletingStockMessage {
  implicit val format:Format[DeletingStockMessage] = Json.format
}
case class StockDeletedMessage(cashHolding: CashHolding, updateTimestamp: Long)
object StockDeletedMessage {
  implicit val format:Format[StockDeletedMessage] = Json.format
}

case class AddingTradeHistoryMessage(stock: Stock, tradeType: TradeType
                              , amount: Int
                              , price: BigDecimal
                              , timestamp: Long)
object AddingTradeHistoryMessage {
  implicit val format:Format[AddingTradeHistoryMessage] = Json.format
}
case class TradeHistoryAddedMessage(stockHolding: StockHolding
                                    , cashHolding: CashHolding, updateTimestamp: Long)
object TradeHistoryAddedMessage {
  implicit val format:Format[TradeHistoryAddedMessage] = Json.format
}

case class DeletingTradeHistoryMessage(stock: Stock
                                     , tradeHistoryId: String)
object DeletingTradeHistoryMessage {
  implicit val format:Format[DeletingTradeHistoryMessage] = Json.format
}
case class TradeHistoryDeletedMessage(stockHolding: StockHolding
                                    , cashHolding: CashHolding, updateTimestamp: Long)
object TradeHistoryDeletedMessage {
  implicit val format:Format[TradeHistoryDeletedMessage] = Json.format
}

case class UpdatingTradeHistoryMessage(stock: Stock
                                       , tradeHistoryId: String
                                       , tradeType: TradeType
                                       , amount: Int
                                       , price: BigDecimal
                                       , timestamp: Long)
object UpdatingTradeHistoryMessage {
  implicit val format:Format[UpdatingTradeHistoryMessage] = Json.format
}
case class TradeHistoryUpdatedMessage(stockHolding: StockHolding
                                      , cashHolding: CashHolding, updateTimestamp: Long)
object TradeHistoryUpdatedMessage {
  implicit val format:Format[TradeHistoryUpdatedMessage] = Json.format
}

case class AddingCashFlowHistory(flowType: FlowType
                                       , balance: Int
                                       , country: Country
                                       , timestamp: Long)
object AddingCashFlowHistory {
  implicit val format:Format[AddingCashFlowHistory] = Json.format
}
case class CashFlowHistoryAddedMessage(cashHolding: CashHolding, updateTimestamp: Long)
object CashFlowHistoryAddedMessage {
  implicit val format:Format[CashFlowHistoryAddedMessage] = Json.format
}


case class DeletingCashFlowHistory(country: Country
                                 , cashFlowHistoryId: String)
object DeletingCashFlowHistory {
  implicit val format:Format[DeletingCashFlowHistory] = Json.format
}
case class CashFlowHistoryDeletedMessage(cashHolding: CashHolding, updateTimestamp: Long)
object CashFlowHistoryDeletedMessage {
  implicit val format:Format[CashFlowHistoryDeletedMessage] = Json.format
}


case class UpdatingCashFlowHistory(cashHistoryId: String
                                   , flowType: FlowType
                                   , country: Country
                                   , balance: BigDecimal
                                   , timestamp: Long)
object UpdatingCashFlowHistory {
  implicit val format:Format[UpdatingCashFlowHistory] = Json.format
}
case class CashFlowHistoryUpdatedMessage(cashHolding: CashHolding, updateTimestamp: Long)
object CashFlowHistoryUpdatedMessage {
  implicit val format:Format[CashFlowHistoryUpdatedMessage] = Json.format
}


case class UpdatingStockCategory(stock: Stock
                                , lastCategory: String
                                , newCategory: String)
object UpdatingStockCategory {
  implicit val format:Format[UpdatingStockCategory] = Json.format
}
case class StockCategoryUpdatedMessage(updateTimestamp: Long)
object StockCategoryUpdatedMessage {
  implicit val format:Format[StockCategoryUpdatedMessage] = Json.format
}


case class PortfolioStatusMessage(totalAsset: BigDecimal, profitBalance: BigDecimal
                                  ,profitProfit: BigDecimal, realizedProfitBalance: BigDecimal
                                  ,boughtBalance: BigDecimal
                                  , assetCategory: AssetCategory, assetRatio: AssetRatio
                                  , cashStatus: Map[Country, CashHolding]
                                  , stockStatus: Map[Stock, StockStatus])
object PortfolioStatusMessage {
  implicit val format:Format[PortfolioStatusMessage] = Json.format

  case class StockStatus(stock: Stock, amount: Int, avgPrice: BigDecimal
                         , nowPrice: BigDecimal, profitBalance: BigDecimal
                         , profitRate: BigDecimal, realizedProfitBalance: BigDecimal
                         , boughtBalance: BigDecimal, evaluatedBalance: BigDecimal
                         , tradeHistories: List[TradeHistory])
  object StockStatus {
    implicit val format:Format[StockStatus] = Json.format
  }

  case class StockRatio(stock: Stock, goalRatio: Int, nowRatio: BigDecimal)
  object StockRatio{
    implicit val format:Format[StockRatio] = Json.format
  }
  case class CashRatio(country: Country, goalRatio: Int, nowRatio: BigDecimal)
  object CashRatio{
    implicit val format:Format[CashRatio] = Json.format

  }

  case class AssetRatio(stockRatios: Map[Category, List[StockRatio]]
                       , cashRatios: Map[Category, List[CashRatio]])
  object AssetRatio{
    implicit val format:Format[AssetRatio] = Json.format
  }
}