package com.ktmet.asset.impl.repo.statistic

import akka.Done
import com.ktmet.asset.api.PortfolioStatistic.StatisticType.StatisticType
import com.ktmet.asset.api.{PortfolioId, StatisticVersion, TimeSeriesStatistic}

trait StatisticRepoTrait[F[_]] {
  def createStatisticVersionTable: F[Done]
  def selectStatisticVersion(portfolioId: PortfolioId): F[Option[StatisticVersion]]
  def insertStatisticVersion(statisticVersion: StatisticVersion): F[Done]

  def createTimeSeriesTable(statisticType: StatisticType): F[Done]
  def insertTimeSeriesBatch(portfolioId: PortfolioId, statisticType: StatisticType
                            , timeSeriesStatistic: TimeSeriesStatistic): F[Done]
  def selectTimeSeries(portfolioId: PortfolioId, statisticType: StatisticType): F[TimeSeriesStatistic]

}
