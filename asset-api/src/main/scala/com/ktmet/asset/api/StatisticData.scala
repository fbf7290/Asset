package com.ktmet.asset.api

import com.ktmet.asset.api.PortfolioStatistic.StatisticType
import com.ktmet.asset.api.PortfolioStatistic.StatisticType.StatisticType
import play.api.libs.json.{Format, Json}

case class StatisticVersion(portfolioId: PortfolioId, timestamp: Long)
object StatisticVersion{
  implicit val format:Format[StatisticVersion] = Json.format
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
case class MddStatistic(mdd: BigDecimal, timeSeries: TimeSeriesStatistic)
object MddStatistic {
  implicit val format:Format[MddStatistic] = Json.format
}
case class PerMonthStatistic(profitTimeSeries: TimeSeriesStatistic)
object PerMonthStatistic {
  implicit val format:Format[PerMonthStatistic] = Json.format
}
case class BalanceStatistic(totalAssetTimeSeries: TimeSeriesStatistic)
object BalanceStatistic {
  implicit val format:Format[BalanceStatistic] = Json.format
}


case class PortfolioStatistic(cagr: BigDecimal, mddStatistic: MddStatistic
                              , perMonthStatistic: PerMonthStatistic
                              , balanceStatistic: BalanceStatistic)
object PortfolioStatistic {
  implicit val format:Format[PortfolioStatistic] = Json.format

  object StatisticType extends Enumeration {
    type StatisticType = Value

    val TotalAsset = Value("TotalAsset")

    implicit val format1: Format[StatisticType] = Json.formatEnum(StatisticType)

    def toTradeType(value:String): Option[StatisticType] =
      if(value=="TotalAsset") Some(TotalAsset)
      else None
  }
}

