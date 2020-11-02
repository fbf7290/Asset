package com.ktmet.asset.api.message

import com.asset.collector.api.Country.Country
import com.asset.collector.api.Stock
import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
import com.ktmet.asset.api.message.AddingStockMessage.AddingTradeHistory
import com.ktmet.asset.api.{CashFlowHistory, CashHolding, CashRatio, Category, StockHolding, StockRatio}
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