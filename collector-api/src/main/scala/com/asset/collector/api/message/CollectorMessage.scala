package com.asset.collector.api.message

import com.asset.collector.api.Stock
import play.api.libs.json.{Format, Json}

case class GettingClosePricesAfterDate(stock: Stock, date: String)
object GettingClosePricesAfterDate {
  implicit val format :Format[GettingClosePricesAfterDate]= Json.format
}
