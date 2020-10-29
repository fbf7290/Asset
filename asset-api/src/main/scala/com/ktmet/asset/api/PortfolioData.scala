package com.ktmet.asset.api

import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, Market, Stock}
import com.ktmet.asset.api.CashFlowHistory.FlowType
import com.ktmet.asset.api.CashFlowHistory.FlowType.FlowType
import com.ktmet.asset.api.TradeHistory.TradeType
import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{Format, JsBoolean, JsResult, JsSuccess, JsValue, Json, Reads, Writes}
import cats.Functor
import cats.Id
import io.jvm.uuid._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

case class Category(value: String) extends AnyVal
object Category {
  implicit val format:Format[Category] = Json.format
  val CashCategory = Category("Cash")
}

case class CategorySet(values: Set[Category]){
  def contains(category: Category) = values.contains(category)
  def add(category: Category) = copy(values + category)
}
object CategorySet {
  implicit val format:Format[CategorySet] = Json.format

  def empty = CategorySet(Set(Category.CashCategory))
}

case class StockRatio(stock: Stock, ratio: Int)
object StockRatio{
  implicit val format:Format[StockRatio] = Json.format
  }
case class CashRatio(country: Country, ratio: Int)
object CashRatio{
  implicit val format:Format[CashRatio] = Json.format

}


case class GoalAssetRatio(stockRatios: Map[Category, List[StockRatio]]
                          , cashRatios: Map[Category, List[CashRatio]]){
  def containCategory(category: Category) = stockRatios.contains(category)
  def addCategory(category: Category) = copy(stockRatios = stockRatios + (category -> List.empty))
  def getCategoryRatios = stockRatios.map{ case (c, l) => c -> l.map(_.ratio).fold(0)(_+_)} ++
                                                cashRatios.map{ case (c, l) => c -> l.map(_.ratio).fold(0)(_+_)}
  def isValid = if(getCategoryRatios.values.fold(0)(_+_) == 100) true else false
  def getCategories = stockRatios.keySet ++ cashRatios.keySet
}
object GoalAssetRatio{

  implicit val stockRatiosReads: Reads[Map[Category, List[StockRatio]]] =
    new Reads[Map[Category, List[StockRatio]]] {
      def reads(jv: JsValue): JsResult[Map[Category, List[StockRatio]]] =
        JsSuccess(jv.as[Map[String, List[StockRatio]]].map{case (k, v) =>
          Category(k) -> v .asInstanceOf[List[StockRatio]]
        })
    }
  implicit val stockRatiosWrites: Writes[Map[Category, List[StockRatio]]] =
    new Writes[Map[Category, List[StockRatio]]] {
      override def writes(o: Map[Category, List[StockRatio]]): JsValue =
        Json.obj(o.map{case (k, v) =>
          k.value -> Json.toJsFieldJsValueWrapper(v)
        }.toSeq:_*)
    }
  implicit val cashRatiosReads: Reads[Map[Category, List[CashRatio]]] =
    new Reads[Map[Category, List[CashRatio]]] {
      def reads(jv: JsValue): JsResult[Map[Category, List[CashRatio]]] =
        JsSuccess(jv.as[Map[String, List[CashRatio]]].map{case (k, v) =>
          Category(k) -> v .asInstanceOf[List[CashRatio]]
        })
    }
  implicit val cashRatiosWrites: Writes[Map[Category, List[CashRatio]]] =
    new Writes[Map[Category, List[CashRatio]]] {
      override def writes(o: Map[Category, List[CashRatio]]): JsValue =
        Json.obj(o.map{case (k, v) =>
          k.value -> Json.toJsFieldJsValueWrapper(v)
        }.toSeq:_*)
    }

  implicit val format:Format[GoalAssetRatio] = Json.format

  def empty = GoalAssetRatio(Map.empty, Map(Category.CashCategory ->
    List(CashRatio(Country.USA, 0), CashRatio(Country.KOREA, 0))))

  def messageToObject(stockRatios: Map[String, List[StockRatio]], cashRatios: Map[String, List[CashRatio]]) =
    GoalAssetRatio(stockRatios.map{ case (k, v) => Category(k)->v}, cashRatios.map{ case (k, v) => Category(k)->v})
}

case class AssetCategory(stockCategory: Map[Category, List[Stock]], cashCategory: Map[Category, List[Country]]){
  def getCategories = stockCategory.keySet ++ cashCategory.keySet
  def getAssets = (stockCategory.values.flatten[Stock], cashCategory.values.flatten[Country])
}
object AssetCategory {
  implicit val stockCategoryReads: Reads[Map[Category, List[Stock]]] =
    new Reads[Map[Category, List[Stock]]] {
      def reads(jv: JsValue): JsResult[Map[Category, List[Stock]]] =
        JsSuccess(jv.as[Map[String, List[Stock]]].map{case (k, v) =>
          Category(k) -> v .asInstanceOf[List[Stock]]
        })
    }
  implicit val stockCategoryWrites: Writes[Map[Category, List[Stock]]] =
    new Writes[Map[Category, List[Stock]]] {
      override def writes(o: Map[Category, List[Stock]]): JsValue =
        Json.obj(o.map{case (k, v) =>
          k.value -> Json.toJsFieldJsValueWrapper(v)
        }.toSeq:_*)
    }

  implicit val cashCategoryReads: Reads[Map[Category, List[Country]]] =
    new Reads[Map[Category, List[Country]]] {
      def reads(jv: JsValue): JsResult[Map[Category, List[Country]]] =
        JsSuccess(jv.as[Map[String, List[Country]]].map{case (k, v) =>
          Category(k) -> v .asInstanceOf[List[Country]]
        })
    }
  implicit val cashCategoryWrites: Writes[Map[Category, List[Country]]] =
    new Writes[Map[Category, List[Country]]] {
      override def writes(o: Map[Category, List[Country]]): JsValue =
        Json.obj(o.map{case (k, v) =>
          k.value -> Json.toJsFieldJsValueWrapper(v)
        }.toSeq:_*)
    }

  implicit val format:Format[AssetCategory] = Json.format
  def empty = AssetCategory(Map.empty, Map(Category.CashCategory -> List(Country.USA, Country.KOREA)))
  def messageToObject(stockCategory: Map[String, List[Stock]] , cashCategory: Map[String, List[Country]]) =
    AssetCategory(stockCategory.map{ case (k, v) => Category(k)->v}, cashCategory.map{ case (k, v) => Category(k)->v})

}

case class TradeHistory(id: String, tradeType: TradeType, stock: Stock
                        , amount: Int, price: BigDecimal, timestamp: Long
                       , cashHistoryId: String){
  override def canEqual(a: Any) = a.isInstanceOf[CashFlowHistory]

  override def equals(that: Any): Boolean =
    that match {
      case that: CashFlowHistory =>
        that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode:Int = {
    s"${id}".hashCode
  }
}
object TradeHistory {
  implicit val format:Format[TradeHistory] = Json.format

  object TradeType extends Enumeration {
    type TradeType = Value

    val BUY = Value("Buy")
    val SELL = Value("Sell")

    implicit val format1: Format[TradeType] = Json.formatEnum(TradeType)

    def toTradeType(value:String):Option[TradeType] =
      if(value=="Buy") Some(BUY)
      else if(value=="Sell") Some(SELL) else None
  }
}

case class CashFlowHistory(id: String, flowType: FlowType, country: Country
                           , amount: BigDecimal, timestamp: Long){
  override def canEqual(a: Any) = a.isInstanceOf[CashFlowHistory]

  override def equals(that: Any): Boolean =
    that match {
      case that: CashFlowHistory =>
        that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode:Int = {
    s"${id}".hashCode
  }
}
object CashFlowHistory {
  implicit val format:Format[CashFlowHistory] = Json.format

  object FlowType extends Enumeration {
    type FlowType = Value

    val DEPOSIT = Value("Deposit")
    val WITHDRAW = Value("Withdraw")
    val SOLDAMOUNT = Value("SoldAmount")
    val BOUGHTAMOUNT = Value("BoughtAmount")


    implicit val format1: Format[FlowType] = Json.formatEnum(FlowType)

    def toFlowType(value:String):Option[FlowType] =
      if(value=="Deposit") Some(DEPOSIT)
      else if(value=="Withdraw") Some(WITHDRAW)
      else if(value=="SoldAmount") Some(SOLDAMOUNT)
      else if(value=="BoughtAmount") Some(BOUGHTAMOUNT)
      else None
  }
}

case class HistorySet(tradeHistory: TradeHistory, cashFlowHistory: CashFlowHistory)
object HistorySet {
  implicit val format:Format[HistorySet] = Json.format

  def apply(tradeHistory: TradeHistory) = tradeHistory.tradeType match {
    case TradeType.BUY => CashFlowHistory(UUID.randomString, FlowType.BOUGHTAMOUNT, tradeHistory.stock.)
    case TradeType.SELL =>
  }
}

case class StockHolding(stock: Stock, amount: Int
                        , avgPrice: BigDecimal, tradeHistories: List[TradeHistory]){
  def containHistory(history: TradeHistory) =
    tradeHistories.find(elem => elem == history).fold(false)(_=>true)
  def isValid = tradeHistories.reverse.scanLeft(0)(_ + _.amount).find(_ < 0).fold(true)(_=>false)
  def calcAmountAndAvgPrice = Functor[Id].map(tradeHistories.foldLeft((0, BigDecimal(0))){
      (r, history) => history.tradeType match {
        case TradeType.BUY => (r._1 + history.amount, r._2 + history.amount * history.price)
        case TradeType.SELL => r
      }}){ case (amount, totalPrice) =>
      (amount, (totalPrice/amount).setScale(2, BigDecimal.RoundingMode.HALF_UP))
    }
  def addHistory(history: TradeHistory) = {
    @tailrec
    def add(list: List[TradeHistory], preList: ListBuffer[TradeHistory]): List[TradeHistory] =
      list match {
        case elem :: rest =>
          elem.timestamp <= history.timestamp match {
            case true =>
              if(history.tradeType == TradeType.SELL) preList.toList ::: history :: list
              else if(elem.tradeType == TradeType.SELL) add(rest, preList += elem)
              else preList.toList ::: history :: list
            case false => add(rest, preList += elem)
          }
        case Nil => (preList += history).toList
      }
    val res = copy(tradeHistories = add(tradeHistories, ListBuffer.empty))
    res.isValid match {
      case true =>
        val (amount, avgPrice) = res.calcAmountAndAvgPrice
        Right(res.copy(amount = amount, avgPrice = avgPrice))
      case false => Left(this)
    }
  }
  def removeHistory(history: TradeHistory) = {
    val res = copy(tradeHistories = tradeHistories.filterNot(_ == history))
    res.isValid match {
      case true =>
        val (amount, avgPrice) = res.calcAmountAndAvgPrice
        Right(res.copy(amount = amount, avgPrice = avgPrice))
      case false => Left(this)
    }
  }

}
object StockHolding {
  implicit val format:Format[StockHolding] = Json.format
  def empty(stock: Stock) = StockHolding(stock, 0, 0, List.empty)
}
case class StockHoldingMap(map: Map[Stock, StockHolding]){
  def containStock(stock: Stock) = map.contains(stock)
  def getAssets = map.keySet
  def addHistory(history: TradeHistory) =
    copy(map + (history.stock -> map.get(history.stock).get.addHistory(history)))
  def removeHistory(history: TradeHistory) =
    copy(map + (history.stock -> map.get(history.stock).get.removeHistory(history)))
}
object StockHoldingMap {

  implicit val stockHoldingsReads: Reads[Map[Stock, StockHolding]] =
    new Reads[Map[Stock, StockHolding]] {
      def reads(jv: JsValue): JsResult[Map[Stock, StockHolding]] =
        JsSuccess(jv.as[Map[String, StockHolding]].map{case (k, v) =>
          v.stock -> v .asInstanceOf[StockHolding]
        })
    }
  implicit val stockHoldingsWrites: Writes[Map[Stock, StockHolding]] =
    new Writes[Map[Stock, StockHolding]] {
      override def writes(o: Map[Stock, StockHolding]): JsValue =
        Json.obj(o.map{case (k, v) =>
          k.code -> Json.toJsFieldJsValueWrapper(v)
        }.toSeq:_*)
    }
  implicit val format:Format[StockHoldingMap] = Json.format

  def empty = StockHoldingMap(Map.empty)
}


case class CashHolding(country: Country, amount: BigDecimal, cashFlowHistories: List[CashFlowHistory]){
  def containHistory(history: CashFlowHistory) =
    cashFlowHistories.find(elem => elem == history).fold(false)(_=>true)
  def findHistory(id: String) = cashFlowHistories.find(elem => elem.id == id)
  def addHistory(history: CashFlowHistory) = {
    @tailrec
    def add(list: List[CashFlowHistory], preList: ListBuffer[CashFlowHistory]): List[CashFlowHistory] =
      list match {
        case elem :: rest =>
          if(elem.timestamp <= history.timestamp) preList.toList ::: history :: list
          else add(rest, preList += elem)
        case Nil => (preList += history).toList
      }
    val amount = history.flowType match {
      case FlowType.DEPOSIT || FlowType.SOLDAMOUNT => this.amount + history.amount
      case FlowType.WITHDRAW || FlowType.BOUGHTAMOUNT => this.amount - history.amount
    }
    copy(amount = amount, cashFlowHistories = add(cashFlowHistories, ListBuffer.empty))
  }
  def removeHistory(history: CashFlowHistory) =
    history.flowType match {
      case FlowType.DEPOSIT || FlowType.SOLDAMOUNT => copy(amount = amount - history.amount
        , cashFlowHistories = cashFlowHistories.filterNot(_ != history))
      case FlowType.WITHDRAW || FlowType.BOUGHTAMOUNT  => copy(amount = amount + history.amount
        , cashFlowHistories = cashFlowHistories.filterNot(_ != history))
    }
}
object CashHolding {
  implicit val format:Format[CashHolding] = Json.format
  def empty(country: Country) = CashHolding(country, 0, List.empty)
}
case class CashHoldingMap(map: Map[Country, CashHolding]){
  def getAssets = map.keySet
  def getHoldingCash(country: Country) = map.get(country)
  def removeHistory(history: CashFlowHistory) =
    copy(map + (history.country -> map.get(history.country).get.removeHistory(history)))
  def addHistory(history: CashFlowHistory) =
    copy(map + (history.country -> map.get(history.country).get.addHistory(history)))
}
object CashHoldingMap {

  implicit val cashHoldingsReads: Reads[Map[Country, CashHolding]] =
    new Reads[Map[Country, CashHolding]] {
      def reads(jv: JsValue): JsResult[Map[Country, CashHolding]] =
        JsSuccess(jv.as[Map[String, CashHolding]].map{case (k, v) =>
          v.country -> v .asInstanceOf[CashHolding]
        })}

  implicit val cashHoldingsWrites: Writes[Map[Country, CashHolding]] =
    new Writes[Map[Country, CashHolding]] {
      override def writes(o: Map[Country, CashHolding]): JsValue =
        Json.obj(o.map{case (k, v) =>
          k.toString -> Json.toJsFieldJsValueWrapper(v)
        }.toSeq:_*)
    }
  implicit val format:Format[CashHoldingMap] = Json.format

  def empty = CashHoldingMap(Map(Country.KOREA->CashHolding.empty(Country.KOREA)
    , Country.USA->CashHolding.empty(Country.USA)))
}
case class Holdings(stockHoldingMap: StockHoldingMap, cashHoldingMap: CashHoldingMap){
  def containStock(stock: Stock) = stockHoldingMap.containStock(stock)
  def getAssets = (stockHoldingMap.getAssets, cashHoldingMap.getAssets)
  def getCash(country: Country) = cashHoldingMap.getHoldingCash(country)
  def removeCashHistory(history: CashFlowHistory) = copy(cashHoldingMap = cashHoldingMap.removeHistory(history))
  def addCashHistory(history: CashFlowHistory) = copy(cashHoldingMap = cashHoldingMap.addHistory(history))
  def removeStockHistory(history: TradeHistory) = copy(stockHoldingMap = stockHoldingMap.removeHistory(history))
  def addStockHistory(history: TradeHistory) = copy(stockHoldingMap = stockHoldingMap.addHistory(history))
}
object Holdings {
  implicit val format:Format[Holdings] = Json.format
  def empty = Holdings(StockHoldingMap.empty, CashHoldingMap.empty)
}

case class PortfolioId(value: String){
  override def toString: String = value
  override def canEqual(a: Any) = a.isInstanceOf[PortfolioId]

  override def equals(that: Any): Boolean =
    that match {
      case that: PortfolioId =>
        that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode:Int = {
    value.hashCode
  }
}
case object PortfolioId {
  implicit val format:Format[PortfolioId] = Json.format
  def empty = PortfolioId("")
}

case class PortfolioState(portfolioId: PortfolioId, name: String, updateTimestamp:Long, owner: UserId
                          , goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory,  holdings: Holdings) {
  def updateTimestamp(timestamp: Long) = copy(updateTimestamp = timestamp)
  def containCategory(category: Category) = goalAssetRatio.containCategory(category)
  def addCategory(category: Category) = copy(goalAssetRatio = goalAssetRatio.addCategory(category))
  def getHoldingAssets = holdings.getAssets
  def getHoldingCash(country: Country) = holdings.getCash(country)
  def removeCashHistory(cashFlowHistory: CashFlowHistory) = copy(holdings = holdings.removeCashHistory(cashFlowHistory))
  def addCashHistory(cashFlowHistory: CashFlowHistory) = copy(holdings = holdings.addCashHistory(cashFlowHistory))
  def containStock(stock: Stock) = holdings.containStock(stock)
  def removeTradeHistory(tradeHistory: TradeHistory) = copy(holdings = holdings.removeStockHistory(tradeHistory))
  def addTradeHistory(tradeHistory: TradeHistory) = copy(holdings = holdings.addStockHistory(tradeHistory))
}
object PortfolioState {
  implicit val format:Format[PortfolioState] = Json.format
  def empty = PortfolioState(PortfolioId.empty, "", 0, UserId.empty
    , GoalAssetRatio.empty, AssetCategory.empty, Holdings.empty)
}


