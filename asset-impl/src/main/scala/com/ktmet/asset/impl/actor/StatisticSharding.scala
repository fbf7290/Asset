package com.ktmet.asset.impl.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import akka.util.Timeout
import cats.{Functor, Id}
import com.ktmet.asset.api.{CashHolding, PortfolioId, PortfolioState, StatisticVersion, StockHolding, TimeSeriesStatistic}
import com.ktmet.asset.impl.actor.StatisticSharding.Command
import com.ktmet.asset.impl.entity.PortfolioEntity
import com.ktmet.asset.impl.repo.statistic.{StatisticRepoAccessor, StatisticRepoTrait}
import cats.instances.future._
import com.asset.collector.api.Country.Country
import com.asset.collector.api.Stock
import com.ktmet.asset.api.CashFlowHistory.FlowType
import com.ktmet.asset.api.PortfolioStatistic.StatisticType
import com.ktmet.asset.api.TradeHistory.TradeType
import com.ktmet.asset.common.api.{ClientException, Timestamp}

import scala.collection.mutable.ListBuffer
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

  case class StockStatus(timestamp: Long, avgPrice: BigDecimal, amount: Int)
  case class StockStatusOverTime(stock: Stock, statusOverTime: Seq[StockStatus])
  case class CashStatus(timestamp: Long, balance: BigDecimal)
  case class CashStatusOverTime(country: Country, statusOverTime: Seq[CashStatus])
}

case class StatisticSharding(portfolioId: PortfolioId, statisticDb: StatisticRepoTrait[Future]
                            , clusterSharding: ClusterSharding
                             , context: ActorContext[Command]
                             , buffer: StashBuffer[Command])
                            (implicit ec: ExecutionContext, askTimeout: Timeout){
  import StatisticSharding._

  private def portfolioEntityRef: EntityRef[PortfolioEntity.Command] =
    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId.value)

  private def calTotalAssetStatistic(portfolioState: PortfolioState): TimeSeriesStatistic = {

    def getStockStatusOverTime(stockHolding: StockHolding): StockStatusOverTime =
      StockStatusOverTime(stockHolding.stock, stockHolding.tradeHistories.reverse.scanLeft(StockStatus(0, 0, 0)){
        (stockStatus, history) =>
          history.tradeType match {
            case TradeType.BUY =>
              StockStatus(Timestamp.tomorrow(history.timestamp)
                , (stockStatus.avgPrice * stockStatus.amount + history.price * history.amount)
                  .setScale(4, BigDecimal.RoundingMode.HALF_UP)
                , stockStatus.amount + history.amount)
            case TradeType.SELL =>
              (stockStatus.amount - history.amount) <= 0 match {
                case true => StockStatus(Timestamp.tomorrow(history.timestamp), 0, 0)
                case false => StockStatus(Timestamp.tomorrow(history.timestamp)
                  , (stockStatus.avgPrice * stockStatus.amount - history.price * history.amount)
                    .setScale(4, BigDecimal.RoundingMode.HALF_UP)
                  , stockStatus.amount - history.amount)
              }
          }
      }.drop(1))

    def getCashStatusOverTime(cashHolding: CashHolding): CashStatusOverTime =
      CashStatusOverTime(cashHolding.country, cashHolding.cashFlowHistories.reverse.scanLeft(CashStatus(0, 0)){
        (cashStatus, history) =>
          history.flowType match {
            case FlowType.DEPOSIT | FlowType.SOLDAMOUNT =>
              CashStatus(Timestamp.tomorrow(history.timestamp), cashStatus.balance + history.balance)
            case FlowType.WITHDRAW | FlowType.BOUGHTAMOUNT =>
              CashStatus(Timestamp.tomorrow(history.timestamp), cashStatus.balance - history.balance)
          }
      }.drop(1))

    ???
  }

  // 1. 각 종목, 현금 별 시간별 보유량과 매수가
  // 2. 보유량과 매수가에 따른 자산별 시장 가치 계산
  // 3. 모든 자산 시장 가치 계산




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