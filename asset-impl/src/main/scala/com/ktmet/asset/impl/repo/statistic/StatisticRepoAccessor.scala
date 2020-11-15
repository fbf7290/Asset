package com.ktmet.asset.impl.repo.statistic

import akka.Done
import cats.Monad
import cats.data.ReaderT
import com.ktmet.asset.api.PortfolioStatistic.StatisticType.StatisticType
import com.ktmet.asset.api.{PortfolioId, StatisticVersion, TimeSeriesStatistic}

object StatisticRepoAccessor {

  def createStatisticVersionTable[F[_]:Monad]:ReaderT[F, StatisticRepoTrait[F], Done] =
    ReaderT[F, StatisticRepoTrait[F], Done] {
      db => db.createStatisticVersionTable
    }

  def selectStatisticVersion[F[_]:Monad](portfolioId: PortfolioId):ReaderT[F, StatisticRepoTrait[F], Option[StatisticVersion]] =
    ReaderT[F, StatisticRepoTrait[F], Option[StatisticVersion]] {
      db => db.selectStatisticVersion(portfolioId)
    }

  def insertStatisticVersion[F[_]:Monad](statisticVersion: StatisticVersion):ReaderT[F, StatisticRepoTrait[F], Done] =
    ReaderT[F, StatisticRepoTrait[F], Done] {
      db => db.insertStatisticVersion(statisticVersion)
    }

  def createTimeSeriesTable[F[_]:Monad](statisticType: StatisticType):ReaderT[F, StatisticRepoTrait[F], Done] =
    ReaderT[F, StatisticRepoTrait[F], Done] {
      db => db.createTimeSeriesTable(statisticType)
    }

  def insertTimeSeriesBatch[F[_]:Monad](portfolioId: PortfolioId, statisticType: StatisticType
                                        , timeSeriesStatistic: TimeSeriesStatistic):ReaderT[F, StatisticRepoTrait[F], Done] =
    ReaderT[F, StatisticRepoTrait[F], Done] {
      db => db.insertTimeSeriesBatch(portfolioId, statisticType, timeSeriesStatistic)
    }

  def selectTimeSeries[F[_]:Monad](portfolioId: PortfolioId
                                   , statisticType: StatisticType):ReaderT[F, StatisticRepoTrait[F], TimeSeriesStatistic] =
    ReaderT[F, StatisticRepoTrait[F], TimeSeriesStatistic] {
      db => db.selectTimeSeries(portfolioId, statisticType)
    }
}
