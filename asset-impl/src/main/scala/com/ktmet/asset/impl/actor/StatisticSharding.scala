package com.ktmet.asset.impl.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import akka.util.Timeout
import com.ktmet.asset.api.{PortfolioId, PortfolioState, StatisticVersion, TimeSeriesStatistic}
import com.ktmet.asset.impl.actor.StatisticSharding.Command
import com.ktmet.asset.impl.entity.PortfolioEntity
import com.ktmet.asset.impl.repo.statistic.{StatisticRepoAccessor, StatisticRepoTrait}
import cats.instances.future._
import com.ktmet.asset.api.PortfolioStatistic.StatisticType
import com.ktmet.asset.common.api.ClientException

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object StatisticSharding {


  def typeKey:EntityTypeKey[Command] = EntityTypeKey[Command]("StatisticSharding")
  def entityId(portfolioId: PortfolioId) = s"${portfolioId.value}"


  sealed trait Command
  case class Init(statisticVersion: Option[StatisticVersion], timeSeriesStatistic: TimeSeriesStatistic) extends Command
  case class Get(replyTo: ActorRef[Response]) extends Command

  sealed trait Response
  case object NotFoundPortfolio extends ClientException(404, "NotFoundPortfolio", "NotFoundPortfolio") with Response

}

case class StatisticSharding(portfolioId: PortfolioId, statisticDb: StatisticRepoTrait[Future]
                            , clusterSharding: ClusterSharding
                             , context: ActorContext[Command]
                             , buffer: StashBuffer[Command])
                            (implicit ec: ExecutionContext, askTimeout: Timeout){
  import StatisticSharding._

  private def portfolioEntityRef: EntityRef[PortfolioEntity.Command] =
    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId.value)

  private def calTotalAssetStatistic(portfolioState: PortfolioState): TimeSeriesStatistic = ???


  def init: Behavior[Command] = {
    context.pipeToSelf(
      for{
        (statisticVer, portfolioVer) <- StatisticRepoAccessor.selectStatisticVersion(portfolioId).run(statisticDb) zip
          portfolioEntityRef.ask[PortfolioEntity.Response](reply => PortfolioEntity.GetTimestamp(reply))
            .collect{
              case m : PortfolioEntity.TimestampResponse => Some(m)
              case PortfolioEntity.NoPortfolioException => None
            }
        r <- (statisticVer, portfolioVer) match {
          case (Some(v1), Some(v2)) =>
            if(v1.timestamp == v2.updateTimestamp) StatisticRepoAccessor.selectTimeSeries(portfolioId, StatisticType.TotalAsset)
              .run(statisticDb)
              .map(statistic => Init(statisticVer, statistic))
            else portfolioEntityRef.ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
              .collect{
                case PortfolioEntity.PortfolioResponse(state) => calTotalAssetStatistic(state)
              }.map{ statistic =>
                StatisticRepoAccessor.insertTimeSeriesBatch(portfolioId, StatisticType.TotalAsset, statistic)
                Init(Some(StatisticVersion(portfolioId, v2.updateTimestamp)), statistic)
              }
          case (_, None) => Future.successful(Init(None, TimeSeriesStatistic.empty))
          case (None, Some(v)) =>
            portfolioEntityRef.ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
              .collect{
                case PortfolioEntity.PortfolioResponse(state) => calTotalAssetStatistic(state)
              }.map{ statistic =>
                StatisticRepoAccessor.insertTimeSeriesBatch(portfolioId, StatisticType.TotalAsset, statistic)
                Init(Some(StatisticVersion(portfolioId, v.updateTimestamp)), statistic)
              }
        }
      } yield{
        r
      }
    ){
      case Success(value) => value
      case Failure(exception) => throw exception
    }

    Behaviors.receiveMessage{
      case Init(statisticVersion, timeSeriesStatistic) =>
        statisticVersion match {
          case Some(ver) =>Behaviors.same
          case None =>
            buffer.foreach{
              case Get(replyTo) => replyTo ! NotFoundPortfolio
            }
            Behaviors.stopped
        }
      case o =>
        buffer.stash(o)
        Behaviors.same
    }

  }
}