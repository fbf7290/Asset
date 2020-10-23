package com.ktmet.asset.api

import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, Market, Stock}
import com.ktmet.asset.api.CashFlowHistory.FlowType.FlowType
import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
import play.api.libs.json.{Format, Json}

case class Category(value: String) extends AnyVal
object Category {
  implicit val format:Format[Category] = Json.format
  val CashCategory = Category("Cash")
}

case class CategorySet(values: Set[Category])
object CategorySet {
  implicit val format:Format[CategorySet] = Json.format

  def empty = CategorySet(Set(Category.CashCategory))
}

case class StockRatio(stock: Stock, ratio: BigDecimal)
object StockRatio{
  implicit val format:Format[StockRatio] = Json.format
}
case class CashRatio(country: Country, ratio: BigDecimal)
object CashRatio{
  implicit val format:Format[CashRatio] = Json.format
}


case class GoalAssetRatio(stockRatios: Map[Category, List[StockRatio]]
                          , cashRatios: Map[Category, List[CashRatio]])
object GoalAssetRatio{
  implicit val format:Format[GoalAssetRatio] = Json.format

  def empty = GoalAssetRatio(Map.empty, Map(Category.CashCategory ->
    List(CashRatio(Country.USA, 0), CashRatio(Country.KOREA, 0))))
}

case class TradeHistory(tradeType: TradeType, stock: Stock, amount: Int, price: BigDecimal, timestamp: Long)
object TradeHistory {
  implicit val format:Format[TradeHistory] = Json.format

  object TradeType extends Enumeration {
    type TradeType = Value

    val BUY = Value("Buy")
    val SELL = Value("Sell")

    implicit val format1: Format[TradeType] = Json.formatEnum(TradeType)

    def toTradeType(value:String):Option[TradeType] =
      if(value=="Buy") Some(BUY)
      else if(value=="Sell") Some(SELL) else None
  }
}

case class CashFlowHistory(flowType: FlowType, country: Country, amount: BigDecimal, timestamp: Long)
object CashFlowHistory {
  implicit val format:Format[CashFlowHistory] = Json.format

  object FlowType extends Enumeration {
    type FlowType = Value

    val DEPOSIT = Value("Deposit")
    val WITHDRAWAL = Value("Withdrawal")

    implicit val format1: Format[FlowType] = Json.formatEnum(FlowType)

    def toTradeType(value:String):Option[FlowType] =
      if(value=="Deposit") Some(DEPOSIT)
      else if(value=="Withdrawal") Some(WITHDRAWAL) else None
  }
}

case class StockHolding(stock: Stock, amount: Int
                        , avgPrice: BigDecimal, tradeHistories: List[TradeHistory])
object StockHolding {
  implicit val format:Format[StockHolding] = Json.format
}

case class CacheHolding(country: Country, amount: BigDecimal, cashFlowHistories: List[CashFlowHistory])
object CacheHolding {
  implicit val format:Format[CacheHolding] = Json.format
}
case class Holdings(stockHoldings: Map[Stock, StockHolding], cashHoldings: Map[Country, CashFlowHistory])
object Holdings {
  implicit val format:Format[Holdings] = Json.format
  def empty = Holdings(Map.empty, Map.empty)
}

case class PortfolioState(name: String, updateTimestamp:Long
                          , categorySet: CategorySet, goalAssetRatio: GoalAssetRatio
                         , holdings: Holdings) {


}
object PortfolioState {
  implicit val format:Format[PortfolioState] = Json.format
  def empty = PortfolioState("", 0, CategorySet.empty, GoalAssetRatio.empty, Holdings.empty)
}