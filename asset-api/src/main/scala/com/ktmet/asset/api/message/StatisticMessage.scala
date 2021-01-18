package com.ktmet.asset.api.message

import com.asset.collector.api.Stock
import com.ktmet.asset.api.{CashAssetStatistic, CategoryAssetStatistic, MddStatistic, MonthProfitStatistic, PortfolioStatistic, TotalAssetStatistic}
import play.api.libs.json.{Format, Json}


case class PortfolioStatisticMessage(timestamp: Long, statistic: Option[PortfolioStatistic])
object PortfolioStatisticMessage{
  implicit val format:Format[PortfolioStatisticMessage] = Json.format
}