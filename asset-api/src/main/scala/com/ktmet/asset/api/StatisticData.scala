package com.ktmet.asset.api

import com.ktmet.asset.api.PortfolioStatistic.StatisticType
import com.ktmet.asset.api.PortfolioStatistic.StatisticType.StatisticType
import play.api.libs.json.{Format, Json}

case class StatisticVersion(portfolioId: PortfolioId, timestamp: Long)
object StatisticVersion{
  implicit val format:Format[StatisticVersion] = Json.format
}
case class DateValue(date: String, value: BigDecimal)
object DateValue {
  implicit val format:Format[DateValue] = Json.format
}

case class TimeStatistic(timestamp:Long, value: BigDecimal)
object TimeStatistic {
  implicit val format:Format[TimeStatistic] = Json.format
}

case class TimeSeriesStatistic(values: Seq[TimeStatistic])
object TimeSeriesStatistic {
  implicit val format:Format[TimeSeriesStatistic] = Json.format
  def empty: TimeSeriesStatistic = TimeSeriesStatistic(Seq.empty)
}

trait Statistic {
  val statisticType: StatisticType
}
case class MddStatistic(mdd: BigDecimal, timeSeries: TimeSeriesStatistic) extends Statistic {
  override val statisticType: StatisticType = StatisticType.Mdd
}
object MddStatistic {
  implicit val format:Format[MddStatistic] = Json.format
}
case class MonthProfitStatistic(profitTimeSeries: TimeSeriesStatistic) extends Statistic {
  override val statisticType: StatisticType = StatisticType.MonthProfit
}
object MonthProfitStatistic {
  implicit val format:Format[MonthProfitStatistic] = Json.format
}
case class TotalAssetStatistic(values: Seq[DateValue]) extends Statistic {
  override val statisticType: StatisticType = StatisticType.TotalAsset
}
object TotalAssetStatistic {
  implicit val format:Format[TotalAssetStatistic] = Json.format
}
case class CategoryAssetStatistic(values: Map[Category, Seq[DateValue]]) extends Statistic {
  override val statisticType: StatisticType = StatisticType.CategoryAsset
}
object CategoryAssetStatistic {
  implicit val format:Format[CategoryAssetStatistic] = Json.format
}
case class CashAssetStatistic(values: Seq[DateValue]) extends Statistic {
  override val statisticType: StatisticType = StatisticType.CategoryAsset
}
object CashAssetStatistic {
  implicit val format:Format[CashAssetStatistic] = Json.format
}

//case class PortfolioStatistic(cagr: BigDecimal, mddStatistic: MddStatistic
//                              , monthProfitStatistic: MonthProfitStatistic
//                              , totalAssetStatistic: TotalAssetStatistic
//                              , categoryAssetStatistic: CategoryAssetStatistic
//                              , cashAssetStatistic: CashAssetStatistic)
case class PortfolioStatistic(totalAssetStatistic: TotalAssetStatistic
                              , categoryAssetStatistic: CategoryAssetStatistic
                              , cashAssetStatistic: CashAssetStatistic)
object PortfolioStatistic {
  implicit val format:Format[PortfolioStatistic] = Json.format

  object StatisticType extends Enumeration {
    type StatisticType = Value

    val TotalAsset = Value("TotalAsset")
    val Mdd = Value("Mdd")
    val MonthProfit = Value("MonthProfit")
    val CategoryAsset = Value("CategoryAsset")
    val CashAsset = Value("CashAsset")

    implicit val format1: Format[StatisticType] = Json.formatEnum(StatisticType)

    def toTradeType(value:String): Option[StatisticType] =
      if(value=="TotalAsset") Some(TotalAsset)
      else if(value == "Mdd") Some(Mdd)
      else if(value == "MonthProfit") Some(MonthProfit)
      else if(value == "CategoryAsset") Some(CategoryAsset)
      else if(value == "CashAsset") Some(CashAsset)
      else None
  }
}

