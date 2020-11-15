package com.ktmet.asset.impl.repo.statistic

import akka.Done
import com.ktmet.asset.api.PortfolioStatistic.StatisticType.StatisticType
import com.ktmet.asset.api.{PortfolioId, StatisticVersion, TimeSeriesStatistic, TimeStatistic}
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraSession
import cats.data.OptionT
import cats.instances.future._
import com.datastax.driver.core.BatchStatement

import scala.concurrent.{ExecutionContext, Future}

class StatisticRepo(session: CassandraSession)(implicit val  ec: ExecutionContext)
  extends StatisticRepoTrait[Future] {

  private def portfolioIdHash(portfolioId: PortfolioId): Int = portfolioId.value.hashCode.abs % 64

  override def createStatisticVersionTable: Future[Done] =
    session.executeCreateTable(s"CREATE TABLE IF NOT EXISTS statistic_version (id_hash INT, portfolio_id TEXT, timestamp BIGINT, PRIMARY KEY(id_hash, portfolio_id))")

  override def selectStatisticVersion(portfolioId: PortfolioId): Future[Option[StatisticVersion]] =
    OptionT(session.selectOne(s"SELECT timestamp FROM statistic_version " +
      s"WHERE id_hash=${portfolioIdHash(portfolioId)} and portfolio_id='${portfolioId.value}'"))
    .map(row => StatisticVersion(portfolioId, row.getLong("timestamp"))).value

  override def insertStatisticVersion(statisticVersion: StatisticVersion): Future[Done] =
    session.executeWrite(s"INSERT INTO statistic_version (id_hash, portfolio_id, timestamp) " +
      s"VALUES (${portfolioIdHash(statisticVersion.portfolioId)}, '${statisticVersion.portfolioId.value}' " +
      s"${statisticVersion.timestamp})")

  override def createTimeSeriesTable(statisticType: StatisticType): Future[Done] =
    session.executeCreateTable(s"CREATE TABLE IF NOT EXISTS ${statisticType}_statistic (portfolio_id TEXT, timestamp BIGINT, value DECIMAL, PRIMARY KEY(portfolio_id, timestamp))")

  override def insertTimeSeriesBatch(portfolioId: PortfolioId, statisticType: StatisticType
                                     , timeSeriesStatistic: TimeSeriesStatistic): Future[Done] =
    for{
      stmt <- session.prepare(s"INSERT INTO ${statisticType}_statistic (portfolio_id, timestamp, value) VALUES (?, ?, ?)")
      batch = new BatchStatement
      _ = timeSeriesStatistic.values.map{ statistic =>
        batch.add(stmt.bind
          .setString("portfolio_id", portfolioId.value)
          .setLong("timestamp", statistic.timestamp)
          .setDecimal("value", statistic.value.bigDecimal))
      }
      r <- session.executeWriteBatch(batch)
    } yield {
      r
    }

  override def selectTimeSeries(portfolioId: PortfolioId, statisticType: StatisticType): Future[TimeSeriesStatistic] =
    session.selectAll(s"SELECT timestamp, value from ${statisticType}_statistic " +
      s"where portfolio_id='${portfolioId.value}'").map{ rows => TimeSeriesStatistic(rows.map{ row =>
      TimeStatistic(row.getLong("timestamp"), BigDecimal(row.getDecimal("value")))
    })}

}
