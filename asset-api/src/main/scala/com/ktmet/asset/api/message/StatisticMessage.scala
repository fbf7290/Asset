package com.ktmet.asset.api.message

import com.asset.collector.api.Stock
import play.api.libs.json.{Format, Json}


case class MarketValueMessage(date: String, value: BigDecimal)
object MarketValueMessage{
  implicit val format:Format[MarketValueMessage] = Json.format
}
case class StockMarketValueOverTimeMessage(stock: Stock, marketValue: Seq[MarketValueMessage])
object StockMarketValueOverTimeMessage{
  implicit val format:Format[StockMarketValueOverTimeMessage] = Json.format
}