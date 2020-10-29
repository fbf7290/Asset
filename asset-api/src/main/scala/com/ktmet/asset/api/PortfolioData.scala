package com.ktmet.asset.api

import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, Market, Stock}
import com.ktmet.asset.api.CashFlowHistory.FlowType.FlowType
import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{Format, JsBoolean, JsResult, JsSuccess, JsValue, Json, Reads, Writes}

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

case class TradeHistory(tradeType: TradeType, stock: Stock, amount: Int, price: BigDecimal, timestamp: Long)
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

case class CashFlowHistory(flowType: FlowType, country: Country, amount: BigDecimal, timestamp: Long){
  override def canEqual(a: Any) = a.isInstanceOf[CashFlowHistory]

  override def equals(that: Any): Boolean =
    that match {
      case that: CashFlowHistory =>
        that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode:Int = {
    s"${country}_${flowType}_${timestamp}".hashCode
  }
}
object CashFlowHistory {
  implicit val format:Format[CashFlowHistory] = Json.format

  object FlowType extends Enumeration {
    type FlowType = Value

    val DEPOSIT = Value("Deposit")
    val WITHDRAW = Value("Withdraw")

    implicit val format1: Format[FlowType] = Json.formatEnum(FlowType)

    def toTradeType(value:String):Option[FlowType] =
      if(value=="Deposit") Some(DEPOSIT)
      else if(value=="Withdraw") Some(WITHDRAW) else None
  }
}

case class StockHolding(stock: Stock, amount: Int
                        , avgPrice: BigDecimal, tradeHistories: List[TradeHistory])
object StockHolding {
  implicit val format:Format[StockHolding] = Json.format
  def empty(stock: Stock) = StockHolding(stock, 0, 0, List.empty)
}
case class StockHoldingMap(map: Map[Stock, StockHolding]){
  def getAssets = map.keySet
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
//    cashFlowHistories.find(elem => elem.country == history.country &&
//      elem.flowType == history.flowType && elem.timestamp == history.timestamp).fold(false)(_=>true)
  def addHistory(history: CashFlowHistory) = {
    @tailrec
    def add(list: List[CashFlowHistory], preList: ListBuffer[CashFlowHistory]): List[CashFlowHistory] =
      list match {
        case elem :: rest =>
          if(elem.timestamp <= history.timestamp) preList.toList ::: history :: list
          else add(rest, preList += elem)
        case Nil => (preList += history).toList
      }
    copy(cashFlowHistories = add(cashFlowHistories, ListBuffer.empty))
  }
  def removeHistory(history: CashFlowHistory) = copy(cashFlowHistories = cashFlowHistories.filterNot(_ != history))
  def deposit(history: CashFlowHistory) = copy(amount = amount + history.amount).addHistory(history)
  def withdraw(history: CashFlowHistory) = copy(amount = amount - history.amount).addHistory(history)

  def isValidBalance = cashFlowHistories.foldLeft(BigDecimal(0))(_+_.amount) >= 0

}
object CashHolding {
  implicit val format:Format[CashHolding] = Json.format
  def empty(country: Country) = CashHolding(country, 0, List.empty)
}
case class CashHoldingMap(map: Map[Country, CashHolding]){
  def getAssets = map.keySet
  def getHoldingCash(country: Country) = map.get(country)
  def deposit(history: CashFlowHistory) =
    copy(map + (history.country -> map.get(history.country).get.deposit(history)))
  def withdraw(history: CashFlowHistory) =
    copy(map + (history.country -> map.get(history.country).get.withdraw(history)))
  def removeHistory(history: CashFlowHistory) =
    copy(map + (history.country -> map.get(history.country).get.removeHistory(history)))
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
  def getAssets = (stockHoldingMap.getAssets, cashHoldingMap.getAssets)
  def getCash(country: Country) = cashHoldingMap.getHoldingCash(country)
  def deposit(history: CashFlowHistory) = copy(cashHoldingMap = cashHoldingMap.deposit(history))
  def withdraw(history: CashFlowHistory) = copy(cashHoldingMap = cashHoldingMap.withdraw(history))
  def removeCashHistory(history: CashFlowHistory) = copy(cashHoldingMap = cashHoldingMap.removeHistory(history))
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
  def deposit(cashFlowHistory: CashFlowHistory) = copy(holdings = holdings.deposit(cashFlowHistory))
  def withdraw(cashFlowHistory: CashFlowHistory) = copy(holdings = holdings.withdraw(cashFlowHistory))
  def removeCashHistory(cashFlowHistory: CashFlowHistory) = copy(holdings = holdings.removeCashHistory(cashFlowHistory))
}
object PortfolioState {
  implicit val format:Format[PortfolioState] = Json.format
  def empty = PortfolioState(PortfolioId.empty, "", 0, UserId.empty
    , GoalAssetRatio.empty, AssetCategory.empty, Holdings.empty)
}



//package com.ktmet.asset.api
//
//import com.asset.collector.api.Country.Country
//import com.asset.collector.api.{Country, Market, Stock}
//import com.ktmet.asset.api.CashFlowHistory.FlowType.FlowType
//import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
//import play.api.libs.json.Json.JsValueWrapper
//import play.api.libs.json.{Format, JsBoolean, JsResult, JsSuccess, JsValue, Json, Reads, Writes}
//
//case class Category(value: String) extends AnyVal
//object Category {
//  implicit val format:Format[Category] = Json.format
//  val CashCategory = Category("Cash")
//}
//
//case class CategorySet(values: Set[Category]){
//  def contains(category: Category) = values.contains(category)
//  def add(category: Category) = copy(values + category)
//}
//object CategorySet {
//  implicit val format:Format[CategorySet] = Json.format
//
//  def empty = CategorySet(Set(Category.CashCategory))
//}
//
//case class StockRatio(stock: Stock, ratio: BigDecimal)
//object StockRatio{
//  implicit val format:Format[StockRatio] = Json.format
//}
//case class CashRatio(country: Country, ratio: BigDecimal)
//object CashRatio{
//  implicit val format:Format[CashRatio] = Json.format
//}
//
//
//case class GoalAssetRatio(stockRatios: Map[Category, List[StockRatio]]
//                          , cashRatios: Map[Category, List[CashRatio]])
//object GoalAssetRatio{
//
//  implicit val stockRatiosReads: Reads[Map[Category, List[StockRatio]]] =
//    new Reads[Map[Category, List[StockRatio]]] {
//      def reads(jv: JsValue): JsResult[Map[Category, List[StockRatio]]] =
//        JsSuccess(jv.as[Map[String, List[StockRatio]]].map{case (k, v) =>
//          Category(k) -> v .asInstanceOf[List[StockRatio]]
//        })
//    }
//  implicit val stockRatiosWrites: Writes[Map[Category, List[StockRatio]]] =
//    new Writes[Map[Category, List[StockRatio]]] {
//      override def writes(o: Map[Category, List[StockRatio]]): JsValue =
//        Json.obj(o.map{case (k, v) =>
//          k.value -> Json.toJsFieldJsValueWrapper(v)
//        }.toSeq:_*)
//    }
//  implicit val cashRatiosReads: Reads[Map[Category, List[CashRatio]]] =
//    new Reads[Map[Category, List[CashRatio]]] {
//      def reads(jv: JsValue): JsResult[Map[Category, List[CashRatio]]] =
//        JsSuccess(jv.as[Map[String, List[CashRatio]]].map{case (k, v) =>
//          Category(k) -> v .asInstanceOf[List[CashRatio]]
//        })
//    }
//  implicit val cashRatiosWrites: Writes[Map[Category, List[CashRatio]]] =
//    new Writes[Map[Category, List[CashRatio]]] {
//      override def writes(o: Map[Category, List[CashRatio]]): JsValue =
//        Json.obj(o.map{case (k, v) =>
//          k.value -> Json.toJsFieldJsValueWrapper(v)
//        }.toSeq:_*)
//    }
//
//
//
//  implicit val format:Format[GoalAssetRatio] = Json.format
//
//  def empty = GoalAssetRatio(Map.empty, Map(Category.CashCategory ->
//    List(CashRatio(Country.USA, 0), CashRatio(Country.KOREA, 0))))
//}
//
//case class TradeHistory(tradeType: TradeType, stock: Stock, amount: Int, price: BigDecimal, timestamp: Long)
//object TradeHistory {
//  implicit val format:Format[TradeHistory] = Json.format
//
//  object TradeType extends Enumeration {
//    type TradeType = Value
//
//    val BUY = Value("Buy")
//    val SELL = Value("Sell")
//
//    implicit val format1: Format[TradeType] = Json.formatEnum(TradeType)
//
//    def toTradeType(value:String):Option[TradeType] =
//      if(value=="Buy") Some(BUY)
//      else if(value=="Sell") Some(SELL) else None
//  }
//}
//
//case class CashFlowHistory(flowType: FlowType, country: Country, amount: BigDecimal, timestamp: Long)
//object CashFlowHistory {
//  implicit val format:Format[CashFlowHistory] = Json.format
//
//  object FlowType extends Enumeration {
//    type FlowType = Value
//
//    val DEPOSIT = Value("Deposit")
//    val WITHDRAWAL = Value("Withdrawal")
//
//    implicit val format1: Format[FlowType] = Json.formatEnum(FlowType)
//
//    def toTradeType(value:String):Option[FlowType] =
//      if(value=="Deposit") Some(DEPOSIT)
//      else if(value=="Withdrawal") Some(WITHDRAWAL) else None
//  }
//}
//
//case class StockHolding(stock: Stock, amount: Int
//                        , avgPrice: BigDecimal, tradeHistories: List[TradeHistory])
//object StockHolding {
//  implicit val format:Format[StockHolding] = Json.format
//}
//
//case class CacheHolding(country: Country, amount: BigDecimal, cashFlowHistories: List[CashFlowHistory])
//object CacheHolding {
//  implicit val format:Format[CacheHolding] = Json.format
//}
//case class Holdings(stockHoldings: Map[Stock, StockHolding], cashHoldings: Map[Country, CashFlowHistory])
//object Holdings {
//
//  implicit val stockHoldingsReads: Reads[Map[Stock, StockHolding]] =
//    new Reads[Map[Stock, StockHolding]] {
//      def reads(jv: JsValue): JsResult[Map[Stock, StockHolding]] =
//        JsSuccess(jv.as[Map[String, StockHolding]].map{case (k, v) =>
//          v.stock -> v .asInstanceOf[StockHolding]
//        })
//    }
//  implicit val stockHoldingsWrites: Writes[Map[Stock, StockHolding]] =
//    new Writes[Map[Stock, StockHolding]] {
//      override def writes(o: Map[Stock, StockHolding]): JsValue =
//        Json.obj(o.map{case (k, v) =>
//          k.code -> Json.toJsFieldJsValueWrapper(v)
//        }.toSeq:_*)
//    }
//
//  implicit val cashHoldingsReads: Reads[Map[Country, CashFlowHistory]] =
//    new Reads[Map[Country, CashFlowHistory]] {
//      def reads(jv: JsValue): JsResult[Map[Country, CashFlowHistory]] =
//        JsSuccess(jv.as[Map[String, CashFlowHistory]].map{case (k, v) =>
//          v.country -> v .asInstanceOf[CashFlowHistory]
//        })}
//
//  implicit val cashHoldingsWrites: Writes[Map[Country, CashFlowHistory]] =
//    new Writes[Map[Country, CashFlowHistory]] {
//      override def writes(o: Map[Country, CashFlowHistory]): JsValue =
//        Json.obj(o.map{case (k, v) =>
//          k.toString -> Json.toJsFieldJsValueWrapper(v)
//        }.toSeq:_*)
//    }
//
//
//  implicit val format:Format[Holdings] = Json.format
//  def empty = Holdings(Map.empty, Map.empty)
//}
//
//case class PortfolioId(value: String){
//  override def toString: String = value
//  override def canEqual(a: Any) = a.isInstanceOf[PortfolioId]
//
//  override def equals(that: Any): Boolean =
//    that match {
//      case that: PortfolioId =>
//        that.canEqual(this) && this.hashCode == that.hashCode
//      case _ => false
//    }
//
//  override def hashCode:Int = {
//    value.hashCode
//  }
//}
//case object PortfolioId {
//  implicit val format:Format[PortfolioId] = Json.format
//  def empty = PortfolioId("")
//}
//
//case class PortfolioState(portfolioId: PortfolioId, name: String, updateTimestamp:Long, owner: UserId
//                          , goalAssetRatio: GoalAssetRatio, holdings: Holdings) {
//  def updateTimestamp(timestamp: Long) = copy(updateTimestamp = timestamp)
//
//}
//object PortfolioState {
//  implicit val format:Format[PortfolioState] = Json.format
//  def empty = PortfolioState(PortfolioId.empty, "", 0, UserId.empty
//                  , GoalAssetRatio.empty, Holdings.empty)
//}