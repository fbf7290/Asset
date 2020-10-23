package com.ktmet.asset.api

import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, Market, Stock}
import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
import play.api.libs.json.{Format, Json}

case class Category(value: String) extends AnyVal
object Category {
  implicit val format:Format[Category] = Json.format
  val CashCategory = Category("현금")
}

case class CategorySet(values: Set[Category])
object CategorySet {
  implicit val format:Format[CategorySet] = Json.format

  def empty() = CategorySet(Set(Category.CashCategory))
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

  def empty() = GoalAssetRatio(Map.empty, Map(Category.CashCategory ->
    List(CashRatio(Country.USA, 0), CashRatio(Country.KOREA, 0))))
}

case class TradeHistory(tradeType: TradeType, stock: Stock, amount: Int, price: BigDecimal, timestamp:Long)
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

case class Holding(stock: Stock, amount: Int
                   , avgPrice: BigDecimal, tradeHistories: List[TradeHistory])
object Holding {
  implicit val format:Format[Holding] = Json.format
}
case class Holdings(holdings: Map[Stock, Holding])
object Holdings {
  implicit val format:Format[Holdings] = Json.format
}

case class PortfolioState(name: String, updateTimestamp:Long
                          , categorySet: CategorySet, goalAlloc: GoalAssetRatio
                         , holdings: Holdings) {


}
object PortfolioState {
  implicit val format:Format[PortfolioState] = Json.format
}