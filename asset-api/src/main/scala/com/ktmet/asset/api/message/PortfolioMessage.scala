package com.ktmet.asset.api.message

import com.asset.collector.api.Country.Country
import com.asset.collector.api.Stock
import com.ktmet.asset.api.{CashRatio, Category, StockRatio}
import play.api.libs.json.{Format, Json}

case class TimestampMessage(updateTimestamp: Long)
object TimestampMessage {
  implicit val format:Format[TimestampMessage] = Json.format
}

case class CreatingPortfolioMessage(name: String)
object CreatingPortfolioMessage {
  implicit val format:Format[CreatingPortfolioMessage] = Json.format
}

case class PortfolioCreatedMessage(portfolioId: String, updateTimestamp: Long)
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