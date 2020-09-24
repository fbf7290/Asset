package com.ktmet.asset.api

import com.asset.collector.api.Stock
import play.api.libs.json.{Format, Json}




case class AutoCompleteMessage(koreaStocks:Seq[Stock], usaStocks:Seq[Stock])
object AutoCompleteMessage{
  implicit val format :Format[AutoCompleteMessage]= Json.format
  def empty = AutoCompleteMessage(Seq.empty, Seq.empty)
}