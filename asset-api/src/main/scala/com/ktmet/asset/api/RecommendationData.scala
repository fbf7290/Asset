package com.ktmet.asset.api

import com.asset.collector.api.Country.Country
import com.asset.collector.api.Stock
import com.ktmet.asset.common.api.MapFormat
import play.api.libs.json.{Format, Json, Reads, Writes}



case class RecommendationStock(stock: Stock, buyPrice: BigDecimal, additionalAmount: Int)
object RecommendationStock{
  implicit val format:Format[RecommendationStock] = Json.format
}


case class Category(value: String) extends AnyVal
object Category {
  implicit val format:Format[Category] = Json.valueFormat
  val CashCategory = Category("Cash")
}

case class StockRatioChange(stock:Stock, nowRatio: Int, changeRatio: Int, goalRatio: Int)
object StockRatioChange {
  implicit val format:Format[StockRatioChange] = Json.format
}
case class CashRatioChange(country: Country, nowRatio: Int, changeRatio: Int, goalRatio: Int)
object CashRatioChange {
  implicit val format:Format[CashRatioChange] = Json.format
}

case class RatioChanges(stockRatioChanges: Map[Category, StockRatioChange]
                        , cashRatioChanges: Map[Category, CashRatioChange])

object RatioChanges{
  implicit val stockRatioChangesReads: Reads[Map[Category, List[StockRatioChange]]] =
    MapFormat.read((k, _) => Category(k))
  implicit val stockRatioChangesWrites: Writes[Map[Category, List[StockRatioChange]]] =
    MapFormat.write((k, _) => k.value)
  implicit val cashRatioChangesReads: Reads[Map[Category, List[CashRatioChange]]] =
    MapFormat.read((k, _) => Category(k))
  implicit val cashRatioChangesWrites: Writes[Map[Category, List[CashRatioChange]]] =
    MapFormat.write((k, _) => k.value)

  implicit val format:Format[RatioChanges] = Json.format
}


/*
1. 종목의 목표 비율과 현재가를 가져온다.
2. 현재가 기준 현재 비율을 계산한다.
3. n을 구한다.(n을 구하는 방법 검토 필요)
  - 10만원씩
  - 목표와 현재 비율 차이가 가장 작은 종목의 비율을 채우기 위한 금액
  -
4. 비율차이가 가장 많이 나는 종목부터 n씩 채워 나간다.
5. 비율차이가 없다면 가장 비중이 많은 종목을 n 채운다.

* 클라이언트에서 직접 변경해가면서 비중을 조절해 나가는 기능이 있으면 좋겠다.
 */

