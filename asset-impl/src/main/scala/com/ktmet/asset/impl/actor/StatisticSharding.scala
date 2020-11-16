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
import com.asset.collector.api.message.GettingClosePricesAfterDate
import com.asset.collector.api.{CollectorService, KrwUsd, Stock}
import com.ktmet.asset.api.CashFlowHistory.FlowType
import com.ktmet.asset.api.PortfolioStatistic.StatisticType
import com.ktmet.asset.api.TradeHistory.TradeType
import com.ktmet.asset.common.api.{ClientException, Timestamp}
import cats.data.NonEmptyList

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

  case class StockStatus(date: String, avgPrice: BigDecimal, amount: Int)
  object StockStatus{
    def empty: StockStatus = StockStatus("00000000", 0, 0)
  }
  case class StockStatusOverTime(stock: Stock, statusOverTime: NonEmptyList[StockStatus])
  case class CashStatus(date: String, balance: BigDecimal)
  object CashStatus{
    def empty: CashStatus = CashStatus("00000000", 0)
  }
  case class CashStatusOverTime(country: Country, statusOverTime: NonEmptyList[CashStatus])
  case class MarketValue(date: String, value: BigDecimal)
  case class StockMarketValueOverTime(stock: Stock, marketValue: MarketValue)
  case class CashMarketValueOverTime(country: Country, marketValue: MarketValue)
}

case class StatisticSharding(portfolioId: PortfolioId, statisticDb: StatisticRepoTrait[Future]
                            , clusterSharding: ClusterSharding
                             , context: ActorContext[Command]
                             , buffer: StashBuffer[Command])
                            (implicit collectorService: CollectorService
                             , ec: ExecutionContext, askTimeout: Timeout){
  import StatisticSharding._

  private def portfolioEntityRef: EntityRef[PortfolioEntity.Command] =
    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId.value)

  private def calTotalAssetStatistic(portfolioState: PortfolioState): TimeSeriesStatistic = {

    def getKrwUsds(date: String): Future[Seq[KrwUsd]] = collectorService.getKrwUsdsAfterDate(date).invoke()

    def getStockStatusOverTime(stockHolding: StockHolding): Option[StockStatusOverTime] = {
      val statusOverTime = stockHolding.tradeHistories.reverse.scanLeft(StockStatus.empty){
        (stockStatus, history) =>
          history.tradeType match {
            case TradeType.BUY =>
              StockStatus(Timestamp.tomorrowDate(history.timestamp)
                , (stockStatus.avgPrice * stockStatus.amount + history.price * history.amount)
                  .setScale(4, BigDecimal.RoundingMode.HALF_UP)
                , stockStatus.amount + history.amount)
            case TradeType.SELL =>
              (stockStatus.amount - history.amount) <= 0 match {
                case true => StockStatus(Timestamp.tomorrowDate(history.timestamp), 0, 0)
                case false => StockStatus(Timestamp.tomorrowDate(history.timestamp)
                  , (stockStatus.avgPrice * stockStatus.amount - history.price * history.amount)
                    .setScale(4, BigDecimal.RoundingMode.HALF_UP)
                  , stockStatus.amount - history.amount)
              }
          }
        }.drop(1)
      NonEmptyList.fromList(statusOverTime).map(StockStatusOverTime(stockHolding.stock, _))
    }

    def getCashStatusOverTime(cashHolding: CashHolding): Option[CashStatusOverTime] = {
      val statusOverTime = cashHolding.cashFlowHistories.reverse.scanLeft(CashStatus.empty){
        (cashStatus, history) =>
          history.flowType match {
            case FlowType.DEPOSIT | FlowType.SOLDAMOUNT =>
              CashStatus(Timestamp.tomorrowDate(history.timestamp), cashStatus.balance + history.balance)
            case FlowType.WITHDRAW | FlowType.BOUGHTAMOUNT =>
              CashStatus(Timestamp.tomorrowDate(history.timestamp), cashStatus.balance - history.balance)
          }
      }.drop(1)
      NonEmptyList.fromList(statusOverTime).map(CashStatusOverTime(cashHolding.country, _))
    }

    def calculateStockMarketValue(stockStatusOverTime: StockStatusOverTime): Option[StockMarketValueOverTime] = {
      collectorService.getClosePricesAfterDate.invoke(GettingClosePricesAfterDate(stockStatusOverTime.stock
        , stockStatusOverTime.statusOverTime.head.date))

      ???
    }

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

// TODO 상장일 체크, 실제 주식이 있는 지 체크