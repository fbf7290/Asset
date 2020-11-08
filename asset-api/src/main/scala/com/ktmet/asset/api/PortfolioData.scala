package com.ktmet.asset.api

import akka.serialization.Serializer
import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, CountryType, Market, Stock}
import com.ktmet.asset.api.CashFlowHistory.{FlowSerialType, FlowType}
import com.ktmet.asset.api.CashFlowHistory.FlowType.FlowType
import com.ktmet.asset.api.TradeHistory.TradeType.TradeType
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{Format, JsBoolean, JsError, JsObject, JsPath, JsResult, JsString, JsSuccess, JsValue, Json, Reads, Writes}
import cats.Functor
import cats.Id
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.scala.JsonScalaEnumeration
import com.ktmet.asset.api.TradeHistory.TradeType
import io.jvm.uuid._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer


case class Category(value: String) extends AnyVal
object Category {
  implicit val format:Format[Category] = Json.format
  val CashCategory = Category("Cash")
}

case class CategorySet(values: Set[Category]){
  def contains(category: Category): Boolean = values.contains(category)
  def add(category: Category): CategorySet = copy(values + category)
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
  def isLimitCategorySize: Boolean = stockRatios.size >= AssetSettings.maxCategorySize
  def containCategory(category: Category): Boolean = stockRatios.contains(category)
  def addCategory(category: Category): GoalAssetRatio = copy(stockRatios = stockRatios + (category -> List.empty))
  def getCategoryRatios: Map[Category, Int] = stockRatios.map{ case (c, l) => c -> l.map(_.ratio).fold(0)(_+_)} ++
    cashRatios.map{ case (c, l) => c -> l.map(_.ratio).fold(0)(_+_)}
  def isValid = if(getCategoryRatios.values.fold(0)(_+_) == 100) true else false
  def getCategories: Set[Category] = stockRatios.keySet ++ cashRatios.keySet
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

  def empty: GoalAssetRatio = GoalAssetRatio(Map.empty, Map(Category.CashCategory ->
    List(CashRatio(Country.USA, 0), CashRatio(Country.KOREA, 0))))

  def messageToObject(stockRatios: Map[String, List[StockRatio]], cashRatios: Map[String, List[CashRatio]]): GoalAssetRatio =
    GoalAssetRatio(stockRatios.map{ case (k, v) => Category(k)->v}, cashRatios.map{ case (k, v) => Category(k)->v})
}

case class AssetCategory(stockCategory: Map[Category, List[Stock]]
                         , cashCategory: Map[Category, List[Country]]){
  def getCategories: Set[Category] = stockCategory.keySet ++ cashCategory.keySet
  def getAssets: (Iterable[Stock], Iterable[Country]) = (stockCategory.values.flatten[Stock], cashCategory.values.flatten[Country])
  def addStock(category: Category, stock:Stock): AssetCategory =
    copy(stockCategory = stockCategory + (category -> (stock :: stockCategory.getOrElse(category, List.empty[Stock]))))
  def contain(category: Category, stock: Stock): Boolean =
    stockCategory.find{ case (c, l) => c == category && l.contains(stock)}.fold(false)(_=>true)
  def removeStock(category: Category, stock: Stock): AssetCategory =
    copy(stockCategory = stockCategory + (category -> stockCategory.getOrElse(category, List.empty[Stock]).filterNot(_ == stock)))
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
  def empty: AssetCategory = AssetCategory(Map.empty, Map(Category.CashCategory -> List(Country.USA, Country.KOREA)))
  def messageToObject(stockCategory: Map[String, List[Stock]] , cashCategory: Map[String, List[Country]]): AssetCategory =
    AssetCategory(stockCategory.map{ case (k, v) => Category(k)->v}, cashCategory.map{ case (k, v) => Category(k)->v})

}




sealed trait TradeHistory extends Equals with Ordered[TradeHistory]{
  val id: String
  val tradeType: TradeType
  val stock: Stock
  val amount: Int
  val price: BigDecimal
  val timestamp: Long
  val cashHistoryId: String


  override def canEqual(a: Any): Boolean = a.isInstanceOf[TradeHistory]

  override def equals(that: Any): Boolean =
    that match {
      case that: TradeHistory =>
        that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode:Int = {
    s"${id}".hashCode
  }

  override def compare(that: TradeHistory): Int = {
    val c = Ordering.Long.compare(this.timestamp, that.timestamp)
    if(c == 0){
      if(that.tradeType == TradeType.SELL) 1
      else if(this.tradeType == TradeType.SELL) -1
      else 1
    } else c * -1
  }
}
case class BuyTradeHistory(id: String, tradeType: TradeType, stock: Stock
                           , amount: Int, price: BigDecimal, timestamp: Long
                            , profitBalance: Option[BigDecimal], profitRate: Option[BigDecimal]
                           , cashHistoryId: String) extends TradeHistory
object BuyTradeHistory {
  implicit val format:Format[BuyTradeHistory] = Json.format
}
case class SellTradeHistory(id: String, tradeType: TradeType, stock: Stock
                            , amount: Int, price: BigDecimal, timestamp: Long
                            , cashHistoryId: String, realizedProfitBalance: BigDecimal, realizedProfitRate: BigDecimal) extends TradeHistory
object SellTradeHistory {
  implicit val format:Format[SellTradeHistory] = Json.format
}


object TradeHistory {
  implicit val format = Format[TradeHistory](
    Reads { js =>
      val tradeType = (JsPath \ "tradeType").read[String].reads(js)
      tradeType.fold(
        errors => JsError("tradeType undefined or incorrect"), {
          case "Buy"   => (JsPath \ "data").read[BuyTradeHistory].reads(js)
          case "Sell"  => (JsPath \ "data").read[SellTradeHistory].reads(js)
        }
      )
    },
    Writes {
      case o: BuyTradeHistory  =>
        JsObject(
          Seq(
            "tradeType" -> JsString("Buy"),
            "data"      -> BuyTradeHistory.format.writes(o)
          )
        )
      case o: SellTradeHistory =>
        JsObject(
          Seq(
            "tradeType" -> JsString("Sell"),
            "data"      -> SellTradeHistory.format.writes(o)
          )
        )
    }
  )

  class TradeSerialType extends TypeReference[TradeType.type] {}
  object TradeType extends Enumeration {
    type TradeType = Value

    val BUY = Value("Buy")
    val SELL = Value("Sell")

    implicit val format1: Format[TradeType] = Json.formatEnum(TradeType)

    def toTradeType(value:String): Option[TradeType] =
      if(value=="Buy") Some(BUY)
      else if(value=="Sell") Some(SELL) else None
  }
}


case class StockHolding(stock: Stock, amount: Int
                        , avgPrice: BigDecimal, realizedProfitBalance: BigDecimal
                        , tradeHistories: List[TradeHistory]){
  def containHistory(history: TradeHistory): Boolean =
    tradeHistories.find(elem => elem == history).fold(false)(_=>true)
  def findHistory(tradeHistoryId: String): Option[TradeHistory] = tradeHistories.find(elem => elem.id == tradeHistoryId)
  def isValid: Boolean = tradeHistories.reverse.scanLeft(0){ (r, h) => val amount =  h match {
    case h: BuyTradeHistory => h.amount
    case h: SellTradeHistory => h.amount * -1}
    amount + r }.find(_ < 0).fold(true)(_=>false)
  def calcAmountAndAvgPrice: StockHolding = Functor[Id].map(tradeHistories.reverse.foldLeft((0, 0, BigDecimal(0))){
    (r, history) => history match {
      case history: BuyTradeHistory => (r._1 + history.amount, r._2 + history.amount, r._3 + history.amount * history.price)
      case history: SellTradeHistory => (r._1 - history.amount, r._2, r._3)
    }}){ case (amount, buyAmount, totalPrice) =>
    if(buyAmount == 0) copy(amount = 0, avgPrice = BigDecimal(0))
    else copy(amount = amount, avgPrice = (totalPrice/buyAmount).setScale(2, BigDecimal.RoundingMode.HALF_UP))
  }
  def calcRealized: StockHolding = {
    var (pBuyAmount, tBuyAmount, tSellAmount) = (0, 0, 0)
    var (pBuyTotalPrice, tBuyTotalPrice, tSellTotalPrice) = (BigDecimal(0), BigDecimal(0), BigDecimal(0))
    val histories = tradeHistories.reverse.map{
      case history: BuyTradeHistory =>
        pBuyAmount += history.amount
        pBuyTotalPrice += history.price * history.amount
        history
      case history: SellTradeHistory =>
        tSellAmount += history.amount
        tSellTotalPrice += history.price * history.amount
        tBuyAmount += pBuyAmount
        tBuyTotalPrice += pBuyTotalPrice
        if(tBuyAmount == 0) history.copy(realizedProfitBalance = BigDecimal(0), realizedProfitRate = BigDecimal(0))
        else{
          val diff =  history.price - tBuyTotalPrice/tBuyAmount
          history.copy(realizedProfitBalance = (diff * history.amount).setScale(2, BigDecimal.RoundingMode.HALF_UP)
            , realizedProfitRate = (diff/(tBuyTotalPrice/tBuyAmount) * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP))
        }
    }.reverse
    if(tSellAmount == 0 || tBuyAmount == 0) copy(tradeHistories = histories)
    else {
      val diff = tSellTotalPrice/tSellAmount - tBuyTotalPrice/tBuyAmount
        copy(realizedProfitBalance = (diff * tSellAmount).setScale(3, BigDecimal.RoundingMode.HALF_UP)
        , tradeHistories =  histories)
    }
  }
  def addHistory(history: TradeHistory): Either[StockHolding, StockHolding] = {
    @tailrec
    def add(list: List[TradeHistory], preList: ListBuffer[TradeHistory]): List[TradeHistory] =
      list match {
        case elem :: rest =>
          elem.timestamp < history.timestamp match {
            case true =>
              preList.toList ::: history :: elem :: rest
            case false if elem.timestamp == history.timestamp =>
              if(history.tradeType == TradeType.SELL) preList.toList ::: history :: elem :: rest
              else if(elem.tradeType == TradeType.SELL) add(rest, preList += elem)
              else preList.toList ::: history :: elem :: rest
            case false =>
              add(rest, preList += elem)
          }
        case Nil => (preList += history).toList
      }
    val res = copy(tradeHistories = add(tradeHistories, ListBuffer.empty))

    res.isValid match {
      case true =>
        Right(res.calcAmountAndAvgPrice.calcRealized)
      case false =>
        Left(res)
    }
  }
  def removeHistory(history: TradeHistory): Either[StockHolding, StockHolding] = {
    val res = copy(tradeHistories = tradeHistories.filterNot(_ == history))
    res.isValid match {
      case true =>
        Right(res.calcAmountAndAvgPrice.calcRealized)
      case false => Left(res)
    }
  }

}
object StockHolding {
  implicit val format:Format[StockHolding] = Json.format
  def empty(stock: Stock) = StockHolding(stock, 0, 0, 0, List.empty)
}


@JsonSerialize(using = classOf[StockHoldingMapSerializer])
@JsonDeserialize(using = classOf[StockHoldingMapDeserializer])
case class StockHoldingMap(map: Map[Stock, StockHolding]){
  def isLimitStockSize: Boolean = map.size >= AssetSettings.maxStockSize
  def containStock(stock: Stock): Boolean = map.contains(stock)
  def getAssets: Set[Stock] = map.keySet
  def getStock(stock: Stock): Option[StockHolding] = map.get(stock)
  def addHistory(history: TradeHistory): StockHoldingMap =
    copy(map + (history.stock -> map.get(history.stock).get.addHistory(history).fold(i=>i, i=>i)))
  def removeHistory(history: TradeHistory): StockHoldingMap =
    copy(map + (history.stock -> map.get(history.stock).get.removeHistory(history).fold(i=>i, i=>i)))
  def addStockHolding(stockHolding: StockHolding): StockHoldingMap =
    copy(map + (stockHolding.stock -> stockHolding))
  def removeStockHolding(stock: Stock): StockHoldingMap =
    copy(map - stock)

}
class StockHoldingMapSerializer extends StdSerializer[StockHoldingMap](classOf[StockHoldingMap]) {
  override def serialize(value: StockHoldingMap, gen: JsonGenerator, provider: SerializerProvider): Unit =
    gen.writeString(Json.toJson(value).toString())
}
class StockHoldingMapDeserializer extends StdDeserializer[StockHoldingMap](classOf[StockHoldingMap]) {
  override def deserialize(p: JsonParser, ctxt: DeserializationContext): StockHoldingMap =
    Json.parse(p.getText).as[StockHoldingMap]
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

  def empty: StockHoldingMap = StockHoldingMap(Map.empty)
}


case class CashFlowHistory(id: String, flowType: FlowType
                           , country: Country
                           , balance: BigDecimal, timestamp: Long){
  override def canEqual(a: Any): Boolean = a.isInstanceOf[CashFlowHistory]

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

  class FlowSerialType extends TypeReference[FlowType.type] {}
  object FlowType extends Enumeration {
    type FlowType = Value

    val DEPOSIT = Value("Deposit")
    val WITHDRAW = Value("Withdraw")
    val SOLDAMOUNT = Value("SoldAmount")
    val BOUGHTAMOUNT = Value("BoughtAmount")


    implicit val format1: Format[FlowType] = Json.formatEnum(FlowType)

    def toFlowType(value:String): Option[FlowType] =
      if(value=="Deposit") Some(DEPOSIT)
      else if(value=="Withdraw") Some(WITHDRAW)
      else if(value=="SoldAmount") Some(SOLDAMOUNT)
      else if(value=="BoughtAmount") Some(BOUGHTAMOUNT)
      else None
  }

  def apply(tradeHistory: TradeHistory) = tradeHistory match {
    case tradeHistory: BuyTradeHistory => new CashFlowHistory(tradeHistory.cashHistoryId, FlowType.BOUGHTAMOUNT
      , tradeHistory.stock.country, tradeHistory.price * tradeHistory.amount, tradeHistory.timestamp)
    case tradeHistory: SellTradeHistory => new CashFlowHistory(tradeHistory.cashHistoryId, FlowType.SOLDAMOUNT
      , tradeHistory.stock.country, tradeHistory.price * tradeHistory.amount, tradeHistory.timestamp)
  }
}

case class CashHolding(country: Country, balance: BigDecimal, cashFlowHistories: List[CashFlowHistory]){
  def containHistory(history: CashFlowHistory): Boolean =
    cashFlowHistories.find(elem => elem == history).fold(false)(_=>true)
  def findHistory(id: String): Option[CashFlowHistory] = cashFlowHistories.find(elem => elem.id == id)
  def addHistory(history: CashFlowHistory): CashHolding = {
    @tailrec
    def add(list: List[CashFlowHistory], preList: ListBuffer[CashFlowHistory]): List[CashFlowHistory] =
      list match {
        case elem :: rest =>
          if(elem.timestamp <= history.timestamp) preList.toList ::: history :: list
          else add(rest, preList += elem)
        case Nil => (preList += history).toList
      }
    val amount = history.flowType match {
      case FlowType.DEPOSIT | FlowType.SOLDAMOUNT => this.balance + history.balance
      case FlowType.WITHDRAW | FlowType.BOUGHTAMOUNT => this.balance - history.balance
    }
    copy(balance = amount, cashFlowHistories = add(cashFlowHistories, ListBuffer.empty))
  }
  def removeHistory(history: CashFlowHistory): CashHolding =
    history.flowType match {
      case FlowType.DEPOSIT | FlowType.SOLDAMOUNT => copy(balance = balance - history.balance
        , cashFlowHistories = cashFlowHistories.filterNot(_ == history))
      case FlowType.WITHDRAW | FlowType.BOUGHTAMOUNT  => copy(balance = balance + history.balance
        , cashFlowHistories = cashFlowHistories.filterNot(_ == history))
    }
  def addHistories(histories: Seq[CashFlowHistory]): CashHolding = histories.foldLeft(this){
    (holding, history) => holding.addHistory(history)
  }
  def removeHistories(histories: Seq[CashFlowHistory]): CashHolding = histories.foldLeft(this){
    (holding, history) => holding.removeHistory(history)
  }
}
object CashHolding {
  implicit val format:Format[CashHolding] = Json.format
  def empty(country: Country): CashHolding = CashHolding(country, 0, List.empty)
}
@JsonSerialize(using = classOf[CashHoldingMapSerializer])
@JsonDeserialize(using = classOf[CashHoldingMapDeserializer])
case class CashHoldingMap(map: Map[Country, CashHolding]){
  def getAssets: Set[Country] = map.keySet
  def getHoldingCash(country: Country): Option[CashHolding] = map.get(country)
  def removeHistory(history: CashFlowHistory): CashHoldingMap =
    copy(map + (history.country -> map.getOrElse(history.country, CashHolding.empty(history.country)).removeHistory(history)))
  def addHistory(history: CashFlowHistory): CashHoldingMap =
    copy(map + (history.country -> map.getOrElse(history.country, CashHolding.empty(history.country)).addHistory(history)))
  def addHistories(country: Country, histories: Seq[CashFlowHistory]): CashHoldingMap =
    copy(map + (country -> map.getOrElse(country, CashHolding.empty(country)).addHistories(histories)))
  def removeHistories(country: Country, histories: Seq[CashFlowHistory]): CashHoldingMap =
    copy(map + (country -> map.getOrElse(country, CashHolding.empty(country)).removeHistories(histories)))
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

  def empty: CashHoldingMap = CashHoldingMap(Map(Country.KOREA->CashHolding.empty(Country.KOREA)
    , Country.USA->CashHolding.empty(Country.USA)))
}

class CashHoldingMapSerializer extends StdSerializer[CashHoldingMap](classOf[CashHoldingMap]) {
  override def serialize(value: CashHoldingMap, gen: JsonGenerator, provider: SerializerProvider): Unit =
    gen.writeString(Json.toJson(value).toString())
}
class CashHoldingMapDeserializer extends StdDeserializer[CashHoldingMap](classOf[StockHoldingMap]) {
  override def deserialize(p: JsonParser, ctxt: DeserializationContext): CashHoldingMap =
    Json.parse(p.getText).as[CashHoldingMap]
}

case class Holdings(stockHoldingMap: StockHoldingMap, cashHoldingMap: CashHoldingMap){
  def isLimitStockSize: Boolean = stockHoldingMap.isLimitStockSize
  def containStock(stock: Stock): Boolean = stockHoldingMap.containStock(stock)
  def getAssets: (Set[Stock], Set[Country]) = (stockHoldingMap.getAssets, cashHoldingMap.getAssets)
  def getStock(stock: Stock): Option[StockHolding] = stockHoldingMap.getStock(stock)
  def getCash(country: Country): Option[CashHolding] = cashHoldingMap.getHoldingCash(country)
  def removeCashHistory(history: CashFlowHistory): Holdings = copy(cashHoldingMap = cashHoldingMap.removeHistory(history))
  def removeCashHistories(country: Country, histories: Seq[CashFlowHistory]): Holdings = copy(cashHoldingMap = cashHoldingMap.removeHistories(country, histories))
  def addCashHistory(history: CashFlowHistory): Holdings = copy(cashHoldingMap = cashHoldingMap.addHistory(history))
  def addCashHistories(country: Country, histories: Seq[CashFlowHistory]): Holdings = copy(cashHoldingMap = cashHoldingMap.addHistories(country, histories))
  def removeStockHistory(history: TradeHistory): Holdings = copy(stockHoldingMap = stockHoldingMap.removeHistory(history))
  def addStockHistory(history: TradeHistory): Holdings = copy(stockHoldingMap = stockHoldingMap.addHistory(history))
  def addStockHolding(stockHolding: StockHolding): Holdings = copy(stockHoldingMap = stockHoldingMap.addStockHolding(stockHolding))
  def removeStockHolding(stock: Stock): Holdings = copy(stockHoldingMap = stockHoldingMap.removeStockHolding(stock))
}
object Holdings {
  implicit val format:Format[Holdings] = Json.format
  def empty: Holdings = Holdings(StockHoldingMap.empty, CashHoldingMap.empty)
}

case class HistorySet(tradeHistory: TradeHistory, cashFlowHistory: CashFlowHistory)
object HistorySet {
  implicit val format:Format[HistorySet] = Json.format

  def apply(tradeHistory: TradeHistory): HistorySet = {
    new HistorySet(tradeHistory, CashFlowHistory(tradeHistory))
  }
}

case class PortfolioId(value: String){
  override def toString: String = value
  override def canEqual(a: Any): Boolean = a.isInstanceOf[PortfolioId]

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
  def empty: PortfolioId = PortfolioId("")
}

case class PortfolioState(portfolioId: PortfolioId, name: String, updateTimestamp:Long, owner: UserId
                          , goalAssetRatio: GoalAssetRatio, assetCategory: AssetCategory,  holdings: Holdings) {
  def updateTimestamp(timestamp: Long): PortfolioState = copy(updateTimestamp = timestamp)
  def containCategory(category: Category): Boolean = goalAssetRatio.containCategory(category)
  def addCategory(category: Category): PortfolioState = copy(goalAssetRatio = goalAssetRatio.addCategory(category))
  def addAssetCategory(category: Category, stock: Stock): PortfolioState = copy(assetCategory = assetCategory.addStock(category, stock))
  def containAssetCategory(category: Category, stock: Stock): Boolean = assetCategory.contain(category, stock)
  def removeAssetCategory(category: Category, stock: Stock): PortfolioState = copy(assetCategory = assetCategory.removeStock(category, stock))
  def getStockRatio: Map[Category, List[StockRatio]] = goalAssetRatio.stockRatios
  def getCashRatio: Map[Category, List[CashRatio]] = goalAssetRatio.cashRatios
  def getHoldingAssets: (Set[Stock], Set[Country]) = holdings.getAssets
  def getHoldingStock(stock: Stock): Option[StockHolding] = holdings.getStock(stock)
  def getHoldingStocks: StockHoldingMap = holdings.stockHoldingMap
  def getHoldingCashes: CashHoldingMap = holdings.cashHoldingMap
  def getHoldingCash(country: Country): Option[CashHolding] = holdings.getCash(country)
  def removeCashHistory(cashFlowHistory: CashFlowHistory): PortfolioState = copy(holdings = holdings.removeCashHistory(cashFlowHistory))
  def removeCashHistories(country: Country, cashFlowHistories: Seq[CashFlowHistory]): PortfolioState = copy(holdings = holdings.removeCashHistories(country, cashFlowHistories))
  def addCashHistory(cashFlowHistory: CashFlowHistory): PortfolioState = copy(holdings = holdings.addCashHistory(cashFlowHistory))
  def addCashHistories(country: Country, cashFlowHistories: Seq[CashFlowHistory]): PortfolioState = copy(holdings = holdings.addCashHistories(country, cashFlowHistories))
  def containStock(stock: Stock): Boolean = holdings.containStock(stock)
  def removeTradeHistory(tradeHistory: TradeHistory): PortfolioState = copy(holdings = holdings.removeStockHistory(tradeHistory))
  def addTradeHistory(tradeHistory: TradeHistory): PortfolioState = copy(holdings = holdings.addStockHistory(tradeHistory))
  def addStockHolding(stockHolding: StockHolding): PortfolioState = copy(holdings = holdings.addStockHolding(stockHolding))
  def removeStockHolding(stock: Stock): PortfolioState = copy(holdings = holdings.removeStockHolding(stock))
  def isLimitStockSize: Boolean = holdings.isLimitStockSize
}
object PortfolioState {
  implicit val format:Format[PortfolioState] = Json.format
  def empty: PortfolioState = PortfolioState(PortfolioId.empty, "", 0, UserId.empty
    , GoalAssetRatio.empty, AssetCategory.empty, Holdings.empty)
}


