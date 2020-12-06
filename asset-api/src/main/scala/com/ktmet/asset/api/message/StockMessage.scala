package com.ktmet.asset.api.message

import com.asset.collector.api.NowPrice
import play.api.libs.json.{Format, Json}

case class NowPrices(prices: Map[String, NowPrice])
object NowPrices{
  implicit val format:Format[NowPrices] = Json.format
}