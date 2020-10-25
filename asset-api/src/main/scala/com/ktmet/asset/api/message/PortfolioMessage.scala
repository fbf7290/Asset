package com.ktmet.asset.api.message

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

