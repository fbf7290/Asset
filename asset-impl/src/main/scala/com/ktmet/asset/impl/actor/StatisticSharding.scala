package com.ktmet.asset.impl.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import cats.{Functor, Id}
import com.ktmet.asset.api.{AssetSettings, BoughtStockCashHistory, BuyTradeHistory, CashFlowHistory, CashHolding, DepositHistory, PortfolioId, PortfolioState, SellTradeHistory, SoldStockCashHistory, StatisticVersion, StockHolding, TimeSeriesStatistic, TradeHistory, WithdrawHistory}
import com.ktmet.asset.impl.actor.StatisticSharding.Command
import com.ktmet.asset.impl.entity.PortfolioEntity
import com.ktmet.asset.impl.repo.statistic.{StatisticRepoAccessor, StatisticRepoTrait}
import cats.instances.future._
import com.asset.collector.api.Country.Country
import com.asset.collector.api.message.GettingClosePricesAfterDate
import com.asset.collector.api.{ClosePrice, CollectorService, Country, KrwUsd, Stock}
import com.ktmet.asset.api.PortfolioStatistic.StatisticType
import com.ktmet.asset.common.api.{ClientException, Timestamp}
import cats.data.NonEmptyList
import com.asset.collector.impl.repo.stock.{StockRepoAccessor, StockRepoTrait}

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

  case class HoldAmount(date: String, amount: Int)
  object HoldAmount{
    def empty: HoldAmount = HoldAmount("00000000", 0)
  }
  case class StockHoldAmountOverTime(stock: Stock, holdAmounts: NonEmptyList[HoldAmount])

  case class CashStatus(date: String, balance: BigDecimal)
  object CashStatus{
    def empty: CashStatus = CashStatus("00000000", 0)
  }
  case class CashStatusOverTime(country: Country, statusOverTime: NonEmptyList[CashStatus])
  case class MarketValue(date: String, value: BigDecimal)
  case class StockMarketValueOverTime(stock: Stock, marketValue: Seq[MarketValue])
  case class CashMarketValueOverTime(country: Country, marketValue: MarketValue)
}

case class StatisticSharding(portfolioId: PortfolioId
                             , statisticDb: StatisticRepoTrait[Future]
                             , stockDb: StockRepoTrait[Future]
                             , clusterSharding: ClusterSharding
                             , context: ActorContext[Command]
                             , buffer: StashBuffer[Command])
                            (implicit collectorService: CollectorService
                            , materializer: Materializer
                             , ec: ExecutionContext, askTimeout: Timeout){
  import StatisticSharding._

  private def portfolioEntityRef: EntityRef[PortfolioEntity.Command] =
    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId.value)

  private def calTotalAssetStatistic(portfolioState: PortfolioState): TimeSeriesStatistic = {

    def getKrwUsds(date: String): Future[Seq[KrwUsd]] = collectorService.getKrwUsdsAfterDate(date).invoke()

    def getStockHoldAmountOverTime(stockHolding: StockHolding): Option[StockHoldAmountOverTime] = {
      val amountOverTime = stockHolding.tradeHistories
        .groupBy(history => Timestamp.timestampToDateString(history.timestamp))
        .map{ case (date, histories) => HoldAmount(date, histories.foldLeft(0){
            (amount, history) => history match {
              case _: BuyTradeHistory => amount + history.amount
              case _: SellTradeHistory => amount - history.amount
            }})}
        .toList
        .sortBy(_.date)
        .scanLeft(HoldAmount.empty){ (result, holdAmount) =>
          HoldAmount(holdAmount.date, result.amount + holdAmount.amount)
        }
        .drop(1)
      NonEmptyList.fromList(amountOverTime).map(StockHoldAmountOverTime(stockHolding.stock, _))
    }


    def getCashStatusOverTime(cashHolding: CashHolding): Option[CashStatusOverTime] = {
      val statusOverTime = cashHolding.cashFlowHistories.groupBy(history =>
        Timestamp.timestampToDateString(history.timestamp)).map{
        case (date, histories) =>
          CashStatus(date, histories.foldLeft(BigDecimal(0)){ (balance, history) =>
            history match {
              case _: DepositHistory | _: SoldStockCashHistory => balance + history.balance
              case _: WithdrawHistory | _: BoughtStockCashHistory => balance - history.balance
            }
          })
      }.toList.sortBy(_.date).scanLeft(CashStatus.empty){
        (cashStatusOverTime, cashStatus) =>
          CashStatus(cashStatus.date, cashStatusOverTime.balance + cashStatus.balance)
      }.drop(1)
      NonEmptyList.fromList(statusOverTime).map(CashStatusOverTime(cashHolding.country, _))
    }

    def calculateStockMarketValue(stockStatusOverTime: StockHoldAmountOverTime): Future[Option[StockMarketValueOverTime]] = {
      var (holdAmount, nextHoldAmount, nextHoldAmounts) =
        if(stockStatusOverTime.holdAmounts.size >= 2)
          (stockStatusOverTime.holdAmounts.head, stockStatusOverTime.holdAmounts.tail.headOption, stockStatusOverTime.holdAmounts.tail.tail)
        else (stockStatusOverTime.holdAmounts.head, None, List.empty)
      var lastClosePrice: Option[ClosePrice] = None

      def rangeMarketValue(closePrice: ClosePrice, fromDate: String, toDate: String): List[MarketValue] =
        Timestamp.rangeDateString(fromDate, toDate).map{ date =>
          nextHoldAmount match {
            case Some(nextAmount) =>
              if(date < nextAmount.date) MarketValue(date, closePrice.price * holdAmount.amount)
              else {
                holdAmount = nextAmount
                nextHoldAmounts.headOption.fold{
                  nextHoldAmount = None
                  nextHoldAmounts = List.empty
                }{ _ =>
                  nextHoldAmount = nextHoldAmounts.headOption
                  nextHoldAmounts = nextHoldAmounts.tail
                }
                MarketValue(date, closePrice.price * nextAmount.amount)
              }
            case None => MarketValue(date, closePrice.price * holdAmount.amount)
          }
        }

      //        collectorService.getClosePricesAfterDate1.invoke(GettingClosePricesAfterDate(stockStatusOverTime.stock
      //          , if(stockStatusOverTime.holdAmounts.head.date > AssetSettings.minStatisticDate) stockStatusOverTime.holdAmounts.head.date
      //          else AssetSettings.minStatisticDate)).map{
      //          prices =>
      //            Some(StockMarketValueOverTime(stockStatusOverTime.stock, prices.sliding(2).flatMap{
      //              prices =>
      //                prices.toList match {
      //                  case elem1 :: elem2 :: Nil =>
      //                    lastClosePrice = Some(elem2)
      //                    rangeMarketValue(elem1, elem1.date, elem2.date)
      //                  case elem :: Nil =>
      //                    rangeMarketValue(elem, elem.date, Timestamp.timestampToTomorrowDateString(Timestamp.now))
      //                  case _ => throw new ArrayIndexOutOfBoundsException
      //                }
      //            }.toList))
      //        }

      collectorService.getClosePricesAfterDate.invoke(GettingClosePricesAfterDate(stockStatusOverTime.stock
        , if(stockStatusOverTime.holdAmounts.head.date > AssetSettings.minStatisticDate) stockStatusOverTime.holdAmounts.head.date
        else AssetSettings.minStatisticDate)).flatMap{ source =>
        source
          .sliding(2).map{ prices =>
          var t = Timestamp.nowMilli
          prices.toList match {
            case elem1 :: elem2 :: Nil =>
              lastClosePrice = Some(elem2)
              rangeMarketValue(elem1, elem1.date, elem2.date)
            case elem :: Nil => rangeMarketValue(elem, elem.date, Timestamp.timestampToTomorrowDateString(Timestamp.now))
            case _ => throw new ArrayIndexOutOfBoundsException
          }
        }.runWith(Sink.seq)
          .map{ marketValues =>
            lastClosePrice match {
              case Some(lastClosePrice) =>
                Some(StockMarketValueOverTime(stockStatusOverTime.stock, marketValues.flatten
                  ++ rangeMarketValue(lastClosePrice, lastClosePrice.date
                  , Timestamp.timestampToTomorrowDateString(Timestamp.now))))
              case None => Some(StockMarketValueOverTime(stockStatusOverTime.stock, marketValues.flatten))
            }
          }.recover{ case e =>
          println(e)
          None
        }
      }
    }

    ???
  }

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
              case _ => ()
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




//package com.ktmet.asset.impl.actor
//
//import akka.actor.typed.{ActorRef, Behavior}
//import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
//import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
//import akka.util.Timeout
//import cats.{Functor, Id}
//import com.ktmet.asset.api.{BoughtStockCashHistory, BuyTradeHistory, CashFlowHistory, CashHolding, DepositHistory, PortfolioId, PortfolioState, SellTradeHistory, SoldStockCashHistory, StatisticVersion, StockHolding, TimeSeriesStatistic, TradeHistory, WithdrawHistory}
//import com.ktmet.asset.impl.actor.StatisticSharding.Command
//import com.ktmet.asset.impl.entity.PortfolioEntity
//import com.ktmet.asset.impl.repo.statistic.{StatisticRepoAccessor, StatisticRepoTrait}
//import cats.instances.future._
//import com.asset.collector.api.Country.Country
//import com.asset.collector.api.message.GettingClosePricesAfterDate
//import com.asset.collector.api.{CollectorService, Country, KrwUsd, Stock}
//import com.ktmet.asset.api.PortfolioStatistic.StatisticType
//import com.ktmet.asset.common.api.{ClientException, Timestamp}
//import cats.data.NonEmptyList
//import com.asset.collector.impl.repo.stock.{StockRepoAccessor, StockRepoTrait}
//
//import scala.collection.mutable.ListBuffer
//import scala.concurrent.{ExecutionContext, Future}
//import scala.util.{Failure, Success}
//
//object StatisticSharding {
//
//
//  def typeKey:EntityTypeKey[Command] = EntityTypeKey[Command]("StatisticSharding")
//  def entityId(portfolioId: PortfolioId) = s"${portfolioId.value}"
//
//
//  sealed trait Command
//  case class Init(statisticVersion: Option[StatisticVersion], timeSeriesStatistic: TimeSeriesStatistic) extends Command
//  case class Get(replyTo: ActorRef[Response]) extends Command
//
//  sealed trait Response
//  case object NotFoundPortfolio extends ClientException(404, "NotFoundPortfolio", "NotFoundPortfolio") with Response
//
//  sealed trait StockTradeSummary{
//    val date: String
//    val amount: Int
//    val avgPrice:BigDecimal
//  }
//  case class BuyTradeSummary(date: String, amount: Int, avgPrice:BigDecimal) extends StockTradeSummary
//  case class SellTradeSummary(date: String, amount: Int, avgPrice:BigDecimal) extends StockTradeSummary
//  case class StockStatus(date: String, avgPrice: BigDecimal, amount: Int)
//  object StockStatus{
//    def empty: StockStatus = StockStatus("00000000", 0, 0)
//  }
//  case class StockStatusOverTime(stock: Stock, statusOverTime: NonEmptyList[StockStatus])
//
//  case class CashStatus(date: String, balance: BigDecimal)
//  object CashStatus{
//    def empty: CashStatus = CashStatus("00000000", 0)
//  }
//  case class CashStatusOverTime(country: Country, statusOverTime: NonEmptyList[CashStatus])
//  case class MarketValue(date: String, value: BigDecimal)
//  case class StockMarketValueOverTime(stock: Stock, marketValue: MarketValue)
//  case class CashMarketValueOverTime(country: Country, marketValue: MarketValue)
//}
//
//case class StatisticSharding(portfolioId: PortfolioId
//                             , statisticDb: StatisticRepoTrait[Future]
//                            , stockDb: StockRepoTrait[Future]
//                            , clusterSharding: ClusterSharding
//                             , context: ActorContext[Command]
//                             , buffer: StashBuffer[Command])
//                            (implicit collectorService: CollectorService
//                             , ec: ExecutionContext, askTimeout: Timeout){
//  import StatisticSharding._
//
//  private def portfolioEntityRef: EntityRef[PortfolioEntity.Command] =
//    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId.value)
//
//  private def calTotalAssetStatistic(portfolioState: PortfolioState): TimeSeriesStatistic = {
//
//    def getKrwUsds(date: String): Future[Seq[KrwUsd]] = collectorService.getKrwUsdsAfterDate(date).invoke()
//
//    def getStockStatusOverTime(stockHolding: StockHolding): Option[StockStatusOverTime] = {
//      val statusOverTime = stockHolding.tradeHistories.groupBy(history =>
//        Timestamp.timestampToDateString(history.timestamp)).map{
//          case (date, histories) =>
//            val (buyAmount, sellAmount, buyBalance) = histories.foldLeft((0, 0, BigDecimal(0))){
//              case ((buyAmount, sellAmount, buyBalance), history) =>
//                history match {
//                  case _: BuyTradeHistory => (buyAmount + history.amount, sellAmount, buyBalance + history.price * history.amount)
//                  case _: SellTradeHistory => (buyAmount, sellAmount + history.amount, buyBalance)
//                }
//              }
//            if(buyAmount > sellAmount) BuyTradeSummary(date, buyAmount - sellAmount
//              , Country.setScale(buyBalance/buyAmount)(stockHolding.stock.country))
//            else SellTradeSummary(date, sellAmount - buyAmount, 0)
//        }.toList.sortBy(_.date).scanLeft(StockStatus.empty){
//          (stockStatus, summary) =>
//            summary match {
//              case _: BuyTradeSummary =>
//                StockStatus(summary.date
//                  , Country.setScale(stockStatus.avgPrice * stockStatus.amount + summary.avgPrice * summary.amount)
//                  (stockHolding.stock.country), stockStatus.amount + summary.amount)
//              case _: SellTradeSummary =>
//                ((stockStatus.amount - summary.amount) <= 0) match {
//                  case true => StockStatus(summary.date, 0, 0)
//                  case false => StockStatus(summary.date, stockStatus.avgPrice, stockStatus.amount - summary.amount)
//                }
//            }
//        }.drop(1)
//      NonEmptyList.fromList(statusOverTime).map(StockStatusOverTime(stockHolding.stock, _))
//    }
//
//    def getCashStatusOverTime(cashHolding: CashHolding): Option[CashStatusOverTime] = {
//      val statusOverTime = cashHolding.cashFlowHistories.groupBy(history =>
//        Timestamp.timestampToDateString(history.timestamp)).map{
//        case (date, histories) =>
//          CashStatus(date, histories.foldLeft(BigDecimal(0)){ (balance, history) =>
//            history match {
//              case _: DepositHistory | _: SoldStockCashHistory => balance + history.balance
//              case _: WithdrawHistory | _: BoughtStockCashHistory => balance - history.balance
//            }
//          })
//      }.toList.sortBy(_.date).scanLeft(CashStatus.empty){
//        (cashStatusOverTime, cashStatus) =>
//          CashStatus(cashStatus.date, cashStatusOverTime.balance + cashStatus.balance)
//      }.drop(1)
//      NonEmptyList.fromList(statusOverTime).map(CashStatusOverTime(cashHolding.country, _))
//    }
//
//
//    def calculateStockMarketValue(stockStatusOverTime: StockStatusOverTime): Future[Option[StockMarketValueOverTime]] = {
//      StockRepoAccessor.selectClosePricesAfterDate(stockDb
//        , stockStatusOverTime.stock, stockStatusOverTime.statusOverTime.head.date)
//      // 매수 평균가
//      ???
//    }
//
//    ???
//  }
//
//  // 1. 각 종목, 현금 별 시간별 보유량과 매수가
//  // 2. 보유량과 매수가에 따른 자산별 시장 가치 계산
//  // 3. 모든 자산 시장 가치 계산
//  // TODO 매수 평균가가 필요없나?
//
//
//
//  def init: Behavior[Command] = {
//    context.pipeToSelf(
//      for{
//        (statisticVer, portfolioVer) <- StatisticRepoAccessor.selectStatisticVersion(portfolioId).run(statisticDb) zip
//          portfolioEntityRef.ask[PortfolioEntity.Response](reply => PortfolioEntity.GetTimestamp(reply))
//            .collect{
//              case m : PortfolioEntity.TimestampResponse => Some(m)
//              case PortfolioEntity.NoPortfolioException => None
//            }
//        r <- (statisticVer, portfolioVer) match {
//          case (Some(v1), Some(v2)) =>
//            if(v1.timestamp == v2.updateTimestamp) StatisticRepoAccessor.selectTimeSeries(portfolioId, StatisticType.TotalAsset)
//              .run(statisticDb)
//              .map(statistic => Init(statisticVer, statistic))
//            else portfolioEntityRef.ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
//              .collect{
//                case PortfolioEntity.PortfolioResponse(state) => calTotalAssetStatistic(state)
//              }.map{ statistic =>
//                StatisticRepoAccessor.insertTimeSeriesBatch(portfolioId, StatisticType.TotalAsset, statistic)
//                Init(Some(StatisticVersion(portfolioId, v2.updateTimestamp)), statistic)
//              }
//          case (_, None) => Future.successful(Init(None, TimeSeriesStatistic.empty))
//          case (None, Some(v)) =>
//            portfolioEntityRef.ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
//              .collect{
//                case PortfolioEntity.PortfolioResponse(state) => calTotalAssetStatistic(state)
//              }.map{ statistic =>
//                StatisticRepoAccessor.insertTimeSeriesBatch(portfolioId, StatisticType.TotalAsset, statistic)
//                Init(Some(StatisticVersion(portfolioId, v.updateTimestamp)), statistic)
//              }
//        }
//      } yield{
//        r
//      }
//    ){
//      case Success(value) => value
//      case Failure(exception) => throw exception
//    }
//
//    Behaviors.receiveMessage{
//      case Init(statisticVersion, timeSeriesStatistic) =>
//        statisticVersion match {
//          case Some(ver) =>Behaviors.same
//          case None =>
//            buffer.foreach{
//              case Get(replyTo) => replyTo ! NotFoundPortfolio
//            }
//            Behaviors.stopped
//        }
//      case o =>
//        buffer.stash(o)
//        Behaviors.same
//    }
//
//  }
//}
//
//// TODO 상장일 체크, 실제 주식이 있는 지 체크