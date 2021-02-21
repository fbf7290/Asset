package com.ktmet.asset.api.message

import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, Stock}
import com.ktmet.asset.api.message.PortfolioMessage.HoldingMessage
import com.ktmet.asset.api.message.PortfolioStatusMessage.{AssetRatio, StockStatus}
import com.ktmet.asset.api.{AssetCategory, CashFlowHistory, CashHolding, CashRatio, Category, GoalAssetRatio, Holdings, PortfolioId, PortfolioState, StockHolding, StockRatio, TradeHistory, UserId}
import com.ktmet.asset.common.api.MapFormat
import play.api.libs.json.{Format, JsError, JsObject, JsPath, JsResult, JsString, JsSuccess, JsValue, Json, Reads, Writes}

case class TimestampMessage(updateTimestamp: Long)
object TimestampMessage {
  implicit val format:Format[TimestampMessage] = Json.format
}

case class CreatingPortfolioMessage(name: String)
object CreatingPortfolioMessage {
  implicit val format:Format[CreatingPortfolioMessage] = Json.format
}

case class PortfolioCreatedMessage(portfolioId: String
                                   , updateTimestamp: Long)
object PortfolioCreatedMessage {
  implicit val format:Format[PortfolioCreatedMessage] = Json.format
}

case class AddingCategoryMessage(category: String)
object AddingCategoryMessage {
  implicit val format:Format[AddingCategoryMessage] = Json.format
}

case class RatiosMessage(stockRatios: Map[String, List[StockRatio]]
                        , cashRatios: Map[String, List[CashRatio]])
object RatiosMessage {
  implicit val format:Format[RatiosMessage] = Json.format
}
case class CategoriesMessage(stockCategory: Map[String, List[Stock]]
                             , cashCategory: Map[String, List[Country]])
object CategoriesMessage {
  implicit val format:Format[CategoriesMessage] = Json.format
}


case class UpdatingGoalAssetRatioMessage(ratios: RatiosMessage
                                        , categories: CategoriesMessage)
object UpdatingGoalAssetRatioMessage {
  implicit val format:Format[UpdatingGoalAssetRatioMessage] = Json.format
}

sealed trait TradeHistoryMessage
object TradeHistoryMessage{
  implicit val format = Format[TradeHistoryMessage](
    Reads { js =>
      val tradeType = (JsPath \ "tradeType").read[String].reads(js)
      tradeType.fold(
        errors => JsError("tradeType undefined or incorrect"), {
          case "Buy" => (JsPath \ "data").read[BuyTradeHistoryMessage].reads(js)
          case "Sell" => (JsPath \ "data").read[SellTradeHistoryMessage].reads(js)
        }
      )
    },
    Writes {
      case o: BuyTradeHistoryMessage =>
        JsObject(
          Seq(
            "tradeType" -> JsString("Buy"),
            "data" -> BuyTradeHistoryMessage.format.writes(o)
          )
        )
      case o: SellTradeHistoryMessage =>
        JsObject(
          Seq(
            "tradeType" -> JsString("Sell"),
            "data" -> SellTradeHistoryMessage.format.writes(o)
          )
        )
    }
  )
}
case class BuyTradeHistoryMessage(amount: Int
                                  , price: BigDecimal
                                  , timestamp: Long) extends TradeHistoryMessage
object BuyTradeHistoryMessage{
  implicit val format:Format[BuyTradeHistoryMessage] = Json.format
}
case class SellTradeHistoryMessage(amount: Int
                                  , price: BigDecimal
                                  , timestamp: Long) extends TradeHistoryMessage
object SellTradeHistoryMessage{
  implicit val format:Format[SellTradeHistoryMessage] = Json.format
}
case class AddingStockMessage(stock: Stock
                              , category: String, tradingHistories: Seq[TradeHistoryMessage])
object AddingStockMessage {
  implicit val format:Format[AddingStockMessage] = Json.format
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

case class AddingTradeHistoryMessage(stock: Stock, history: TradeHistoryMessage)
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

case class DeletingAllTradeHistoriesMessage(stock: Stock)
object DeletingAllTradeHistoriesMessage{
  implicit val format:Format[DeletingAllTradeHistoriesMessage] = Json.format
}
case class AllTradeHistoriesDeletedMessage(stockHolding: StockHolding
                                          , cashHolding: CashHolding, updateTimestamp: Long)
object AllTradeHistoriesDeletedMessage{
  implicit val format:Format[AllTradeHistoriesDeletedMessage] = Json.format
}

case class UpdatingTradeHistoryMessage(stock: Stock
                                       , tradeHistoryId: String
                                       , history: TradeHistoryMessage)
object UpdatingTradeHistoryMessage {
  implicit val format:Format[UpdatingTradeHistoryMessage] = Json.format
}
case class TradeHistoryUpdatedMessage(stockHolding: StockHolding
                                      , cashHolding: CashHolding, updateTimestamp: Long)
object TradeHistoryUpdatedMessage {
  implicit val format:Format[TradeHistoryUpdatedMessage] = Json.format
}

sealed trait CashFlowHistoryMessage
object CashFlowHistoryMessage{
  implicit val format = Format[CashFlowHistoryMessage](
    Reads { js =>
      val cashFlowType = (JsPath \ "cashFlowType").read[String].reads(js)
      cashFlowType.fold(
        errors => JsError("cashFlowType undefined or incorrect"), {
          case "Deposit"   => (JsPath \ "data").read[DepositHistoryMessage].reads(js)
          case "Withdraw"  => (JsPath \ "data").read[WithdrawHistoryMessage].reads(js)
          case "SoldStockCash"  => (JsPath \ "data").read[SoldStockCashHistoryMessage].reads(js)
          case "BoughtStockCash"  => (JsPath \ "data").read[BoughtStockCashHistoryMessage].reads(js)
        }
      )
    },
    Writes {
      case o: DepositHistoryMessage  =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("Deposit"),
            "data"      -> DepositHistoryMessage.format.writes(o)
          )
        )
      case o: WithdrawHistoryMessage =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("WithdrawHistory"),
            "data"      -> WithdrawHistoryMessage.format.writes(o)
          )
        )
      case o: SoldStockCashHistoryMessage =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("SoldStockCashHistory"),
            "data"      -> SoldStockCashHistoryMessage.format.writes(o)
          )
        )
      case o: BoughtStockCashHistoryMessage =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("BoughtStockCashHistory"),
            "data"      -> BoughtStockCashHistoryMessage.format.writes(o)
          )
        )
    }
  )
}
case class DepositHistoryMessage(balance: Int
                                 , country: Country
                                 , timestamp: Long) extends CashFlowHistoryMessage
object DepositHistoryMessage{
  implicit val format:Format[DepositHistoryMessage] = Json.format
}
case class WithdrawHistoryMessage(balance: Int
                                 , country: Country
                                 , timestamp: Long) extends CashFlowHistoryMessage
object WithdrawHistoryMessage{
  implicit val format:Format[WithdrawHistoryMessage] = Json.format
}
case class SoldStockCashHistoryMessage(balance: Int
                                  , country: Country
                                  , timestamp: Long) extends CashFlowHistoryMessage
object SoldStockCashHistoryMessage{
  implicit val format:Format[SoldStockCashHistoryMessage] = Json.format
}
case class BoughtStockCashHistoryMessage(balance: Int
                                       , country: Country
                                       , timestamp: Long) extends CashFlowHistoryMessage
object BoughtStockCashHistoryMessage{
  implicit val format:Format[BoughtStockCashHistoryMessage] = Json.format
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
                                   , history: CashFlowHistoryMessage)
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
case class StockCategoryUpdatedMessage(goalAssetRatio: GoalAssetRatio
                                       , assetCategory: AssetCategory, updateTimestamp: Long)
object StockCategoryUpdatedMessage {
  implicit val format:Format[StockCategoryUpdatedMessage] = Json.format
}


case class PortfolioStatusMessage(totalAsset: BigDecimal, profitBalance: BigDecimal
                                  ,profitRate: BigDecimal, realizedProfitBalance: BigDecimal
                                  ,boughtBalance: BigDecimal
                                  , assetCategory: AssetCategory, assetRatio: AssetRatio
                                  , cashStatus: Map[Country, CashHolding]
                                  , stockStatus: Map[Stock, StockStatus])
object PortfolioStatusMessage {

  implicit val cashHoldingsReads: Reads[Map[Country, CashHolding]] =
    MapFormat.read((_, v) => v.country)
  implicit val cashHoldingsWrites: Writes[Map[Country, CashHolding]] =
    MapFormat.write((k, _) => k.toString)
  implicit val stockHoldingsReads: Reads[Map[Stock, StockStatus]] =
    MapFormat.read((_, v) => v.stock)
  implicit val stockHoldingsWrites: Writes[Map[Stock, StockStatus]] =
    MapFormat.write((k, _) => k.code)

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


    implicit val stockRatiosReads: Reads[Map[Category, List[StockRatio]]] =
      MapFormat.read((k, _) => Category(k))
    implicit val stockRatiosWrites: Writes[Map[Category, List[StockRatio]]] =
      MapFormat.write((k, _) => k.value)
    implicit val cashRatiosReads: Reads[Map[Category, List[CashRatio]]] =
      MapFormat.read((k, _) => Category(k))
    implicit val cashRatiosWrites: Writes[Map[Category, List[CashRatio]]] =
      MapFormat.write((k, _) => k.value)

    implicit val format:Format[AssetRatio] = Json.format
  }

  implicit val format:Format[PortfolioStatusMessage] = Json.format
}


case class PortfolioStockMessage(stock: Stock, amount: Int
                                 , avgPrice: BigDecimal, realizedProfitBalance: BigDecimal
                                 , tradeHistories: List[TradeHistory])
object PortfolioStockMessage {
  implicit val format:Format[PortfolioStockMessage] = Json.format

  def apply(stockHolding: StockHolding): PortfolioStockMessage =
    new PortfolioStockMessage(stock = stockHolding.stock
      , amount = stockHolding.amount, avgPrice = stockHolding.avgPrice
      , realizedProfitBalance = stockHolding.realizedProfitBalance
      , tradeHistories = stockHolding.tradeHistories)
}


case class PortfolioMessage(portfolioId: String, name: String, updateTimestamp:Long, owner: String
                            , goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory,  holdings: HoldingMessage)
object PortfolioMessage {
  implicit val format:Format[PortfolioMessage] = Json.format

  case class HoldingMessage(cashHoldings: Map[Country, CashHolding]
                            , stockHoldings: Map[Stock, StockHolding])
  object HoldingMessage{
    implicit val cashHoldingsReads: Reads[Map[Country, CashHolding]] =
      MapFormat.read((_, v) => v.country)
    implicit val cashHoldingsWrites: Writes[Map[Country, CashHolding]] =
      MapFormat.write((k, _) => k.toString)

    implicit val stockHoldingsReads: Reads[Map[Stock, StockHolding]] =
      MapFormat.read((_, v) => v.stock)
    implicit val stockHoldingsWrites: Writes[Map[Stock, StockHolding]] =
      MapFormat.write((k, _) => k.code)
    implicit val format:Format[HoldingMessage] = Json.format
  }

  def apply(portfolioState: PortfolioState): PortfolioMessage =
    new PortfolioMessage(portfolioId = portfolioState.portfolioId.value
      , name = portfolioState.name
      , updateTimestamp = portfolioState.updateTimestamp
      , owner = portfolioState.owner.value
      , goalAssetRatio = portfolioState.goalAssetRatio
      , assetCategory = portfolioState.assetCategory
      , holdings = HoldingMessage(portfolioState.holdings.cashHoldingMap.map
        , portfolioState.holdings.stockHoldingMap.map))
}

