package com.ktmet.asset.impl.actor

import akka.Done
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import cats.{Functor, Id}
import com.ktmet.asset.api.{AssetSettings, BoughtStockCashHistory, BuyTradeHistory, CashAssetStatistic, CashFlowHistory, CashHolding, Category, CategoryAssetStatistic, DateValue, DepositHistory, PortfolioId, PortfolioState, PortfolioStatistic, SellTradeHistory, SoldStockCashHistory, StatisticVersion, StockHolding, TimeSeriesStatistic, TotalAssetStatistic, TradeHistory, WithdrawHistory}
import com.ktmet.asset.impl.actor.StatisticSharding.Command
import com.ktmet.asset.impl.entity.PortfolioEntity
import com.ktmet.asset.impl.repo.statistic.{StatisticRepoAccessor, StatisticRepoTrait}
import cats.instances.future._
import com.asset.collector.api.Country.Country
import com.asset.collector.api.message.GettingClosePricesAfterDate
import com.asset.collector.api.{ClosePrice, CollectorService, Country, KrwUsd, Stock}
import com.ktmet.asset.api.PortfolioStatistic.StatisticType
import com.ktmet.asset.common.api.{ClientException, Timestamp}
import cats.data.{NonEmptyList, OptionT}
import com.asset.collector.impl.repo.stock.{StockRepoAccessor, StockRepoTrait}
import cats.syntax.option._
import cats.data.EitherT
import cats.syntax.either._

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
  case class StockValueOverTime(stock: Stock, value: Seq[DateValue])
  case class CashValueOverTime(country: Country, value: Seq[DateValue])
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

  object StatisticCalculatorUtils {

    def getKrwUsds(date: String): Future[Map[String, KrwUsd]] =
      collectorService.getKrwUsdsAfterDate(date).invoke()
        .map{ _.foldLeft(Map.empty[String, KrwUsd]){ (r, krwUsd) => r + (krwUsd.date -> krwUsd)}
        }

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

    def calculateCashValues(cashStatusOverTime: CashStatusOverTime, krwUsds: Map[String, KrwUsd]): CashValueOverTime = {
      def rangeCashValue(cashStatus: CashStatus, fromDate: String, toDate: String): List[DateValue] =
        Timestamp.rangeDateString(fromDate, toDate)
          .map{ date =>
            val krwUsd = cashStatusOverTime.country match {
              case Country.KOREA => BigDecimal(1)
              case Country.USA => krwUsds.getOrElse(date, KrwUsd.empty).rate
            }
            DateValue(date, cashStatus.balance * krwUsd)
          }

      var lastCashStatus: Option[CashStatus] = None
      val values = cashStatusOverTime.statusOverTime.toList.sliding(2).flatMap{ prices =>
        prices.toList match {
          case elem1 :: elem2 :: Nil =>
            lastCashStatus = Some(elem2)
            rangeCashValue(elem1, elem1.date, elem2.date)
          case elem :: Nil =>
            rangeCashValue(elem, elem.date, Timestamp.timestampToTomorrowDateString(Timestamp.now))
          case _ => throw new IllegalArgumentException
        }
      }.toList
      lastCashStatus match {
        case Some(lastCashStatus) => CashValueOverTime(cashStatusOverTime.country,
          values ++ rangeCashValue(lastCashStatus, lastCashStatus.date
            , Timestamp.timestampToTomorrowDateString(Timestamp.now)))
        case None => CashValueOverTime(cashStatusOverTime.country, values)
      }
    }

    def calculateStockValues(stockStatusOverTime: StockHoldAmountOverTime, krwUsds: Map[String, KrwUsd]): Future[Either[Throwable, StockValueOverTime]] = {
      var (holdAmount, nextHoldAmount, nextHoldAmounts) =
        if(stockStatusOverTime.holdAmounts.size >= 2)
          (stockStatusOverTime.holdAmounts.head, stockStatusOverTime.holdAmounts.tail.headOption, stockStatusOverTime.holdAmounts.tail.tail)
        else (stockStatusOverTime.holdAmounts.head, None, List.empty)
      var lastClosePrice: Option[ClosePrice] = None

      def rangeMarketValue(closePrice: ClosePrice, fromDate: String, toDate: String): List[DateValue] =
        Timestamp.rangeDateString(fromDate, toDate).map{ date =>
          val krwUsd = stockStatusOverTime.stock.country match {
            case Country.KOREA => BigDecimal(1)
            case Country.USA => krwUsds.getOrElse(date, KrwUsd.empty).rate
          }
          nextHoldAmount match {
            case Some(nextAmount) =>
              if(date < nextAmount.date) DateValue(date, closePrice.price * holdAmount.amount * krwUsd)
              else {
                holdAmount = nextAmount
                nextHoldAmounts.headOption.fold{
                  nextHoldAmount = None
                  nextHoldAmounts = List.empty
                }{ _ =>
                  nextHoldAmount = nextHoldAmounts.headOption
                  nextHoldAmounts = nextHoldAmounts.tail
                }
                DateValue(date, closePrice.price * nextAmount.amount * krwUsd)
              }
            case None => DateValue(date, closePrice.price * holdAmount.amount * krwUsd)
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
          prices.toList match {
            case elem1 :: elem2 :: Nil =>
              lastClosePrice = Some(elem2)
              rangeMarketValue(elem1, elem1.date, elem2.date)
            case elem :: Nil => rangeMarketValue(elem, elem.date, Timestamp.timestampToTomorrowDateString(Timestamp.now))
            case _ => throw new IllegalArgumentException
          }
        }.runWith(Sink.seq)
          .map{ values =>
            lastClosePrice match {
              case Some(lastClosePrice) =>
                Right(StockValueOverTime(stockStatusOverTime.stock, values.flatten
                  ++ rangeMarketValue(lastClosePrice, lastClosePrice.date
                  , Timestamp.timestampToTomorrowDateString(Timestamp.now))))
              case None => Right(StockValueOverTime(stockStatusOverTime.stock, values.flatten))
            }
          }.recover{ case e =>
          println(e)
          Left(e)
        }
      }
    }

//    def mergeStockValues(stockValues: List[StockValueOverTime]): Seq[StockValue]=
//      StatisticCalculatorUtils.mergeStockValues(stockValues.map(_.value))
    def mergeStockValues(stockValues: Seq[Seq[DateValue]]): Seq[DateValue]= ???
  }

  private def portfolioEntityRef: EntityRef[PortfolioEntity.Command] =
    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId.value)

  private def calPortfolioStatistic(portfolioState: PortfolioState):Future[Either[Throwable, Option[PortfolioStatistic]]] = {
    def minDate: Option[String] = portfolioState.getHoldingCashes.map.values
      .foldLeft(none[Long]){(maybeTimestamp, cash) =>
        cash.cashFlowHistories.lastOption match {
          case Some(value) =>
            maybeTimestamp.fold(value.timestamp.some)(t => Math.min(value.timestamp, t).some)
          case None => maybeTimestamp
        }}.map(timestamp => Timestamp.timestampToDateString(timestamp))

    minDate match {
      case Some(minDate) =>
        (for {
          krwUsds <- EitherT(StatisticCalculatorUtils.getKrwUsds(minDate)
                      .map{r => r.asRight[Throwable]}
                      .recover{ case e => e.asLeft[Map[String, KrwUsd]]})
          categoryStockValues <- EitherT(Future.sequence(portfolioState.getStockRatio.map{ case (category, stockRatios) =>
            Future.sequence(stockRatios
              .map(ratio => StatisticCalculatorUtils.getStockHoldAmountOverTime(portfolioState.getHoldingStock(ratio.stock).get))
              .flatten
              .map( holdAmount => StatisticCalculatorUtils.calculateStockValues(holdAmount, krwUsds))
            ).map{ r =>
                val (lefts, rights) = r.partition(_.isLeft)
                lefts.isEmpty match {
                  case true =>
                    (category -> StatisticCalculatorUtils.mergeStockValues(rights.map(_.right.get.value)))
                  case false =>
                    throw lefts.head.left.get
                }
              }
            }).map(r => r.asRight).recover{ case e => e.asLeft})
            cashValues = StatisticCalculatorUtils.mergeStockValues(List(Country.KOREA, Country.USA)
              .map(c => StatisticCalculatorUtils.getCashStatusOverTime(portfolioState.getHoldingCash(c)))
              .flatten
              .map(r => StatisticCalculatorUtils.calculateCashValues(r, krwUsds)).map(_.value))
            totalValue = StatisticCalculatorUtils.mergeStockValues(cashValues :: categoryStockValues.map(_._2).toList)
          } yield {
            Some(PortfolioStatistic(TotalAssetStatistic(totalValue)
              , CategoryAssetStatistic(Map(categoryStockValues.toSeq:_*)[Category, Seq[DateValue]])
              , CashAssetStatistic(cashValues)))
          }).value
      case None => Future.successful(Right(None))
    }
  }

  def init: Behavior[Command] = {
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
//              StatisticRepoAccessor.insertTimeSeriesBatch(portfolioId, StatisticType.TotalAsset, statistic)
//              Init(Some(StatisticVersion(portfolioId, v2.updateTimestamp)), statistic)
//            }
//          case (_, None) => Future.successful(Init(None, TimeSeriesStatistic.empty))
//          case (None, Some(v)) =>
//            portfolioEntityRef.ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
//              .collect{
//                case PortfolioEntity.PortfolioResponse(state) => calTotalAssetStatistic(state)
//              }.map{ statistic =>
//              StatisticRepoAccessor.insertTimeSeriesBatch(portfolioId, StatisticType.TotalAsset, statistic)
//              Init(Some(StatisticVersion(portfolioId, v.updateTimestamp)), statistic)
//            }
//        }
//      } yield{
//        r
//      }
//    ){
//      case Success(value) => value
//      case Failure(exception) => throw exception
//    }

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
// TODO 통계치 당일꺼 포함할지




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