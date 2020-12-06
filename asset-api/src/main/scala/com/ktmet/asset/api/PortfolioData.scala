
package com.ktmet.asset.api


import cats.{Functor, Id}
import com.asset.collector.api.Country.Country
import com.asset.collector.api.{Country, Stock}
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.ktmet.asset.common.api.MapFormat
import play.api.libs.json.{Format, JsError, JsObject, JsPath, JsResult, JsString, JsSuccess, JsValue, Json, Reads, Writes}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

case class Category(value: String) extends AnyVal
object Category {
  implicit val format:Format[Category] = Json.format
  val CashCategory = Category("Cash")
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
  def addCategory(category: Category): GoalAssetRatio =
    copy(stockRatios = stockRatios + (category -> List.empty))
  def addStock(category: Category, stock: Stock): Option[GoalAssetRatio] =
    stockRatios.get(category) match {
      case Some(ratios) => ratios.find(_.stock == stock) match {
        case Some(_) => Some(this)
        case None => Some(copy(stockRatios = stockRatios + (category -> (StockRatio(stock, 0) :: ratios))))
      }
      case None => None
    }
  def getCategoryRatios: Map[Category, Int] = stockRatios.map{ case (c, l) => c -> l.map(_.ratio).fold(0)(_+_)} ++
    cashRatios.map{ case (c, l) => c -> l.map(_.ratio).fold(0)(_+_)}
  def isValid = if(getCategoryRatios.values.fold(0)(_+_) == 100) true else false
  def getCategories: Set[Category] = stockRatios.keySet ++ cashRatios.keySet
  def getStocks: Iterable[Stock] = stockRatios.values.flatten.map(_.stock)
  def changeCategory(stock: Stock, lastCategory: Category, newCategory: Category): Option[GoalAssetRatio] = {
    stockRatios.get(lastCategory) match {
      case Some(ratios) => ratios.find(_.stock == stock) match {
        case Some(ratio) => Some(copy(stockRatios = stockRatios
          + (lastCategory -> ratios.filterNot(_.stock == stock))
          + (newCategory -> stockRatios.get(newCategory).fold(List(ratio))(ratios => ratio :: ratios))))
        case None => None
      }
      case None => None
    }
  }
}
object GoalAssetRatio{

  implicit val stockRatiosReads: Reads[Map[Category, List[StockRatio]]] =
    MapFormat.read((k, _) => Category(k))
  implicit val stockRatiosWrites: Writes[Map[Category, List[StockRatio]]] =
    MapFormat.write((k, _) => k.value)
  implicit val cashRatiosReads: Reads[Map[Category, List[CashRatio]]] =
    MapFormat.read((k, _) => Category(k))
  implicit val cashRatiosWrites: Writes[Map[Category, List[CashRatio]]] =
    MapFormat.write((k, _) => k.value)

  implicit val format:Format[GoalAssetRatio] = Json.format

  def empty: GoalAssetRatio = GoalAssetRatio(Map.empty, Map(Category.CashCategory ->
    List(CashRatio(Country.USA, 0), CashRatio(Country.KOREA, 0))))
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
    Functor[Id].map(stockCategory.get(category) match {
      case Some(stocks) => stocks.filterNot(_ == stock)
      case None => List.empty
    }){ stocks =>
      if(stocks.isEmpty) copy(stockCategory = stockCategory - category)
      else copy(stockCategory = stockCategory + (category -> stocks))
    }
}
object AssetCategory {
  implicit val stockCategoryReads: Reads[Map[Category, List[Stock]]] =
    MapFormat.read((k, _) => Category(k))
  implicit val stockCategoryWrites: Writes[Map[Category, List[Stock]]] =
    MapFormat.write((k, _) => k.value)
  implicit val cashCategoryReads: Reads[Map[Category, List[Country]]] =
    MapFormat.read((k, _) => Category(k))
  implicit val cashCategoryWrites: Writes[Map[Category, List[Country]]] =
    MapFormat.write((k, _) => k.value)

  implicit val format:Format[AssetCategory] = Json.format
  def empty: AssetCategory = new AssetCategory(Map.empty, Map(Category.CashCategory -> List(Country.USA, Country.KOREA)))
}

sealed trait TradeHistory extends Equals with Ordered[TradeHistory]{
  val id: String
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
      (that, this) match {
        case (_: SellTradeHistory, _) => 1
        case (_, _: SellTradeHistory) => -1
        case _ => 1
      }
    } else c * -1
  }
}
case class BuyTradeHistory(id: String, stock: Stock
                           , amount: Int, price: BigDecimal, timestamp: Long
                           , cashHistoryId: String
                           , profitBalance: Option[BigDecimal]
                           , profitRate: Option[BigDecimal]) extends TradeHistory
object BuyTradeHistory {
  implicit val format:Format[BuyTradeHistory] = Json.format
}
case class SellTradeHistory(id: String, stock: Stock
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
          case "Buy" => (JsPath \ "data").read[BuyTradeHistory].reads(js)
          case "Sell" => (JsPath \ "data").read[SellTradeHistory].reads(js)
        }
      )
    },
    Writes {
      case o: BuyTradeHistory =>
        JsObject(
          Seq(
            "tradeType" -> JsString("Buy"),
            "data" -> BuyTradeHistory.format.writes(o)
          )
        )
      case o: SellTradeHistory =>
        JsObject(
          Seq(
            "tradeType" -> JsString("Sell"),
            "data" -> SellTradeHistory.format.writes(o)
          )
        )
    }
  )
}


case class StockHolding(stock: Stock, amount: Int
                        , avgPrice: BigDecimal, realizedProfitBalance: BigDecimal
                        , tradeHistories: List[TradeHistory]){
  def containHistory(history: TradeHistory): Boolean = tradeHistories.find(_ == history).fold(false)(_=>true)
  def findHistory(tradeHistoryId: String): Option[TradeHistory] = tradeHistories.find(_.id == tradeHistoryId)
  def isValid: Boolean = tradeHistories.reverse.scanLeft(0){ (r, h) => h match {
    case h: BuyTradeHistory => h.amount + r
    case h: SellTradeHistory => r - h.amount }}.find(_ < 0).fold(true)(_=>false)
  def calcSummary: StockHolding = {
    var (buyAmount, buyBalance, totalRealizedProfitBalance) = (0 ,BigDecimal(0), BigDecimal(0))
    val histories = tradeHistories.reverse.map{
      case history: BuyTradeHistory =>
        buyAmount += history.amount
        buyBalance += history.price * history.amount
        history
      case history: SellTradeHistory =>
        if(buyAmount == 0 || buyBalance == 0) history.copy(realizedProfitBalance = BigDecimal(0), realizedProfitRate = BigDecimal(0))
        else {
          val taxRatio = stock.country match {
            case Country.KOREA => AssetSettings.koreaStockTax
            case Country.USA => BigDecimal(0)
          }
          val tax = taxRatio * history.price
          val diff = history.price - tax - buyBalance/buyAmount
          val realizedProfitBalance = Country.setScale(diff * history.amount)(stock.country)
          totalRealizedProfitBalance += realizedProfitBalance
          val newHistory = history.copy(realizedProfitBalance = realizedProfitBalance
            , realizedProfitRate = ((diff/(buyBalance/buyAmount)) * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP))
          buyBalance *= BigDecimal(buyAmount - history.amount)/buyAmount
          buyAmount -= history.amount
          newHistory
        }
    }.reverse
    if(buyAmount == 0) copy(amount = 0, avgPrice = BigDecimal(0)
      , realizedProfitBalance = totalRealizedProfitBalance, tradeHistories = histories)
    else copy(amount = buyAmount, avgPrice = Country.setScale(buyBalance/buyAmount)(stock.country)
      , realizedProfitBalance = totalRealizedProfitBalance, tradeHistories = histories)
  }
  private def removeHistory(histories: List[TradeHistory], history: TradeHistory): List[TradeHistory] =
    histories.filterNot(_ == history)
  private def addHistory(histories: List[TradeHistory], history: TradeHistory): List[TradeHistory] = {
    @tailrec
    def add(history: TradeHistory, list: List[TradeHistory], preList: ListBuffer[TradeHistory]): List[TradeHistory] =
      list match {
        case elem :: rest =>
          elem.timestamp < history.timestamp match {
            case true =>
              preList.toList ::: history :: elem :: rest
            case false if elem.timestamp == history.timestamp =>
              (history, elem) match {
                case (_: SellTradeHistory, _) =>  preList.toList ::: history :: elem :: rest
                case (_, _: SellTradeHistory) => add(history, rest, preList += elem)
                case _ => preList.toList ::: history :: elem :: rest
              }
            case false =>
              add(history, rest, preList += elem)
          }
        case Nil => (preList += history).toList
      }
    add(history, histories, ListBuffer.empty)
  }
  def removeHistories(histories: TradeHistory *): Either[Throwable, StockHolding] = {
    val res = copy(tradeHistories = histories.foldLeft(this.tradeHistories){
      (newHistories, history) => this.removeHistory(newHistories, history)
    })
    res.isValid match {
      case true =>
        Right(res.calcSummary)
      case false =>
        Left(new Exception("invalid trade history"))
    }
  }
  def addHistories(histories: TradeHistory *): Either[Throwable, StockHolding] = {
    val res = copy(tradeHistories = histories.foldLeft(this.tradeHistories){
      (newHistories, history) => addHistory(newHistories, history)
    })
    res.isValid match {
      case true =>
        Right(res.calcSummary)
      case false =>
        Left(new Exception("invalid trade history"))
    }
  }
  def updateHistory(lastHistory: TradeHistory, newHistory: TradeHistory): Either[Throwable, StockHolding] = {
    val res = copy(tradeHistories =
      this.addHistory(this.removeHistory(this.tradeHistories, lastHistory), newHistory))
    res.isValid match {
      case true =>
        Right(res.calcSummary)
      case false =>
        Left(new Exception("invalid trade history"))
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
  def addHistories(stock: Stock, histories: TradeHistory *): StockHoldingMap =
    copy(map + (stock -> map.get(stock).get.addHistories(histories: _*).fold(throw _, i => i)))
  def removeHistories(stock: Stock, histories: TradeHistory *): StockHoldingMap =
    copy(map + (stock -> map.get(stock).get.removeHistories(histories: _*).fold(throw _, i => i)))
  def updateHistory(stock: Stock, lastHistory: TradeHistory, newHistory: TradeHistory): StockHoldingMap =
    copy(map + (stock -> map.get(stock).get.updateHistory(lastHistory, newHistory).fold(throw _, i => i)))
  def addStockHolding(stockHolding: StockHolding): StockHoldingMap =
    copy(map + (stockHolding.stock -> stockHolding))
  def removeStockHolding(stock: Stock): StockHoldingMap =
    copy(map - stock)
}
object StockHoldingMap {

  implicit val stockHoldingsReads: Reads[Map[Stock, StockHolding]] =
    MapFormat.read((_, v) => v.stock)
  implicit val stockHoldingsWrites: Writes[Map[Stock, StockHolding]] =
    MapFormat.write((k, _) => k.code)

  implicit val format:Format[StockHoldingMap] = Json.format

  def empty: StockHoldingMap = StockHoldingMap(Map.empty)
}
class StockHoldingMapSerializer extends StdSerializer[StockHoldingMap](classOf[StockHoldingMap]) {
  override def serialize(value: StockHoldingMap, gen: JsonGenerator, provider: SerializerProvider): Unit =
    gen.writeString(Json.toJson(value).toString())
}
class StockHoldingMapDeserializer extends StdDeserializer[StockHoldingMap](classOf[StockHoldingMap]) {
  override def deserialize(p: JsonParser, ctxt: DeserializationContext): StockHoldingMap =
    Json.parse(p.getText).as[StockHoldingMap]
}
sealed trait CashFlowHistory extends Equals with Ordered[CashFlowHistory]{
  val id: String
  val country: Country
  val balance: BigDecimal
  val timestamp: Long

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

  override def compare(that: CashFlowHistory): Int = {
    val c = Ordering.Long.compare(this.timestamp, that.timestamp)
    if(c == 0){
      (that, this) match {
        case (_: WithdrawHistory | _: BoughtStockCashHistory, _) => 1
        case (_, _: WithdrawHistory | _: BoughtStockCashHistory) => -1
        case _ => -1
      }
    } else c * -1
  }
}
object CashFlowHistory {
  implicit val format = Format[CashFlowHistory](
    Reads { js =>
      val cashFlowType = (JsPath \ "cashFlowType").read[String].reads(js)
      cashFlowType.fold(
        errors => JsError("cashFlowType undefined or incorrect"), {
          case "Deposit"   => (JsPath \ "data").read[DepositHistory].reads(js)
          case "Withdraw"  => (JsPath \ "data").read[WithdrawHistory].reads(js)
          case "SoldStockCash"  => (JsPath \ "data").read[SoldStockCashHistory].reads(js)
          case "BoughtStockCash"  => (JsPath \ "data").read[BoughtStockCashHistory].reads(js)
        }
      )
    },
    Writes {
      case o: DepositHistory  =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("Deposit"),
            "data"      -> DepositHistory.format.writes(o)
          )
        )
      case o: WithdrawHistory =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("Withdraw"),
            "data"      -> WithdrawHistory.format.writes(o)
          )
        )
      case o: SoldStockCashHistory =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("SoldStockCash"),
            "data"      -> SoldStockCashHistory.format.writes(o)
          )
        )
      case o: BoughtStockCashHistory =>
        JsObject(
          Seq(
            "cashFlowType" -> JsString("BoughtStockCash"),
            "data"      -> BoughtStockCashHistory.format.writes(o)
          )
        )
    }
  )
  def apply(tradeHistory: TradeHistory): CashFlowHistory = tradeHistory match {
    case tradeHistory: BuyTradeHistory => new BoughtStockCashHistory(tradeHistory.cashHistoryId
      , tradeHistory.stock.country, tradeHistory.price * tradeHistory.amount, tradeHistory.timestamp)
    case tradeHistory: SellTradeHistory => new SoldStockCashHistory(tradeHistory.cashHistoryId
      , tradeHistory.stock.country, tradeHistory.price * tradeHistory.amount, tradeHistory.timestamp)
  }
}
case class DepositHistory(id: String
                          , country: Country, balance: BigDecimal
                          , timestamp: Long) extends CashFlowHistory
object DepositHistory {
  implicit val format:Format[DepositHistory] = Json.format
}
case class WithdrawHistory(id: String
                           , country: Country, balance: BigDecimal
                           , timestamp: Long) extends CashFlowHistory
object WithdrawHistory {
  implicit val format:Format[WithdrawHistory] = Json.format
}
case class SoldStockCashHistory(id: String
                                , country: Country, balance: BigDecimal
                                , timestamp: Long) extends CashFlowHistory
object SoldStockCashHistory {
  implicit val format:Format[SoldStockCashHistory] = Json.format
}
case class BoughtStockCashHistory(id: String
                                  , country: Country, balance: BigDecimal
                                  , timestamp: Long) extends CashFlowHistory
object BoughtStockCashHistory {
  implicit val format:Format[BoughtStockCashHistory] = Json.format
}
case class CashHolding(country: Country, balance: BigDecimal, cashFlowHistories: List[CashFlowHistory]) {
  def containHistory(history: CashFlowHistory): Boolean =
    cashFlowHistories.find(_ == history).fold(false)(_=>true)
  def findHistory(id: String): Option[CashFlowHistory] = cashFlowHistories.find(_.id == id)
  def isValid: Boolean = cashFlowHistories.reverse.scanLeft(BigDecimal(0)){ (r, h) => h match {
    case _: WithdrawHistory | _: BoughtStockCashHistory => r - h.balance
    case _: DepositHistory | _: SoldStockCashHistory => r + h.balance
  }}.find(_ < 0).fold(true)(_=>false)
  private def addHistory(balance: BigDecimal, histories: List[CashFlowHistory]
                         , history: CashFlowHistory): (BigDecimal, List[CashFlowHistory]) = {
    @tailrec
    def add(history: CashFlowHistory, list: List[CashFlowHistory], preList: ListBuffer[CashFlowHistory]): List[CashFlowHistory] =
      list match {
        case elem :: rest =>
          if(elem.timestamp < history.timestamp) preList.toList ::: history :: list
          else if(elem.timestamp == history.timestamp){
            (history, elem) match {
              case (_: WithdrawHistory | _: BoughtStockCashHistory, _) => preList.toList ::: history :: list
              case (_, _: WithdrawHistory | _: BoughtStockCashHistory) => add(history, rest, preList += elem)
              case _ => preList.toList ::: history :: list
            }
          }
          else add(history, rest, preList += elem)
        case Nil => (preList += history).toList
      }
    (history match {
      case _: WithdrawHistory | _: BoughtStockCashHistory => balance - history.balance
      case _: DepositHistory | _: SoldStockCashHistory => balance + history.balance
    }, add(history, histories, ListBuffer.empty))
  }
  private def removeHistory(balance: BigDecimal, histories: List[CashFlowHistory]
                           , history: CashFlowHistory): (BigDecimal, List[CashFlowHistory]) =
    (history match {
      case _: WithdrawHistory | _: BoughtStockCashHistory => balance + history.balance
      case _: DepositHistory | _: SoldStockCashHistory => balance - history.balance
    }, histories.filterNot(_ == history))
  def addHistories(histories: CashFlowHistory *): Either[Throwable, CashHolding] = {

    val (balance, newHistories) = histories.foldLeft((this.balance, this.cashFlowHistories)){
      case ((balance, newHistories), history) => this.addHistory(balance, newHistories, history)
    }
    val res = copy(balance = balance, cashFlowHistories = newHistories)
    res.isValid match {
      case true => Right(res)
      case false => Left(new Exception("invalid cash history"))
    }
  }
  def removeHistories(histories: CashFlowHistory *): Either[Throwable, CashHolding] = {
    val (balance, newHistories) = histories.foldLeft((this.balance, this.cashFlowHistories)){
      case ((balance, newHistories), history) =>
        newHistories.find(_ == history) match {
          case Some(history) => this.removeHistory(balance, newHistories, history)
          case None => (balance, newHistories)
        }
    }
    val res = copy(balance = balance, cashFlowHistories = newHistories)
    res.isValid match {
      case true => Right(res)
      case false => Left(new Exception("invalid cash history"))
    }
  }
  def updateHistory(lastHistory: CashFlowHistory, newHistory: CashFlowHistory): Either[Throwable, CashHolding] = {
    this.cashFlowHistories.find(_ == lastHistory) match {
      case Some(lastHistory) =>
        var (balance, newHistories) = this.removeHistory(this.balance, cashFlowHistories, lastHistory)
        var (balance1, newHistories1) = this.addHistory(balance, newHistories, newHistory)
        val res = copy(balance = balance1, cashFlowHistories = newHistories1)
        res.isValid match {
          case true => Right(res)
          case false => Left(new Exception("invalid cash history"))
        }
      case None => Left(new Exception("invalid cash history"))
    }
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
  def removeHistories(country: Country, histories: CashFlowHistory *): CashHoldingMap =
    copy(map + (country -> map.getOrElse(country, CashHolding.empty(country)).removeHistories(histories: _*).fold(throw _, i => i)))
  def addHistories(country: Country, histories: CashFlowHistory *): CashHoldingMap =
    copy(map + (country -> map.getOrElse(country, CashHolding.empty(country)).addHistories(histories: _*).fold(throw _, i => i)))
  def updateHistory(country: Country, lastHistory: CashFlowHistory, newHistory: CashFlowHistory): CashHoldingMap =
    copy(map + (country -> map.getOrElse(country, CashHolding.empty(country)).updateHistory(lastHistory, newHistory).fold(throw _, i => i)))
  def addCashHolding(cashHolding: CashHolding): CashHoldingMap =
    copy(map + (cashHolding.country -> cashHolding))
}
object CashHoldingMap {

  implicit val cashHoldingsReads: Reads[Map[Country, CashHolding]] =
    MapFormat.read((_, v) => v.country)
  implicit val cashHoldingsWrites: Writes[Map[Country, CashHolding]] =
    MapFormat.write((k, _) => k.toString)
//  implicit val cashHoldingsReads: Reads[Map[Country, CashHolding]] =
//    new Reads[Map[Country, CashHolding]] {
//      def reads(jv: JsValue): JsResult[Map[Country, CashHolding]] =
//        JsSuccess(jv.as[Map[String, CashHolding]].map{case (k, v) =>
//          v.country -> v .asInstanceOf[CashHolding]
//        })}
//
//  implicit val cashHoldingsWrites: Writes[Map[Country, CashHolding]] =
//    new Writes[Map[Country, CashHolding]] {
//      override def writes(o: Map[Country, CashHolding]): JsValue =
//        Json.obj(o.map{case (k, v) =>
//          k.toString -> Json.toJsFieldJsValueWrapper(v)
//        }.toSeq:_*)
//    }
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
  def removeCashHistories(country: Country, histories: CashFlowHistory *): Holdings =
    copy(cashHoldingMap = cashHoldingMap.removeHistories(country, histories: _*))
  def addCashHistories(country: Country, histories: CashFlowHistory *): Holdings =
    copy(cashHoldingMap = cashHoldingMap.addHistories(country, histories: _*))
  def updateCashHistory(country: Country, lastHistory: CashFlowHistory, newHistory: CashFlowHistory): Holdings =
    copy(cashHoldingMap = cashHoldingMap.updateHistory(country, lastHistory, newHistory))
  def removeStockHistories(stock: Stock, histories: TradeHistory *): Holdings =
    copy(stockHoldingMap = stockHoldingMap.removeHistories(stock, histories: _*))
  def addStockHistories(stock: Stock, histories: TradeHistory *): Holdings =
    copy(stockHoldingMap = stockHoldingMap.addHistories(stock, histories: _*))
  def updateStockHistory(stock: Stock, lastHistory: TradeHistory, newHistory: TradeHistory): Holdings =
    copy(stockHoldingMap = stockHoldingMap.updateHistory(stock, lastHistory, newHistory))
  def addStockHolding(stockHolding: StockHolding): Holdings =
    copy(stockHoldingMap = stockHoldingMap.addStockHolding(stockHolding))
  def addCashHolding(cashHolding: CashHolding): Holdings =
    copy(cashHoldingMap = cashHoldingMap.addCashHolding(cashHolding))
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
  def addGoalAssetRatio(category: Category, stock: Stock): PortfolioState = copy(goalAssetRatio = goalAssetRatio.addStock(category, stock).get)
  def changeGoalAssetRatioCategory(stock: Stock, lastCategory: Category, newCategory: Category): PortfolioState = copy(goalAssetRatio = goalAssetRatio.changeCategory(stock, lastCategory, newCategory).get)
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
  def getHoldingCash(country: Country): CashHolding = holdings.getCash(country).get
  def removeCashHistories(country: Country, cashFlowHistories: CashFlowHistory *): PortfolioState = copy(holdings = holdings.removeCashHistories(country, cashFlowHistories: _*))
  def addCashHistories(country: Country, cashFlowHistories: CashFlowHistory *): PortfolioState = copy(holdings = holdings.addCashHistories(country, cashFlowHistories: _*))
  def updateCashHistory(country: Country, lastCashFlowHistory: CashFlowHistory, newCashFlowHistory: CashFlowHistory): PortfolioState = copy(holdings = holdings.updateCashHistory(country, lastCashFlowHistory, newCashFlowHistory))
  def containStock(stock: Stock): Boolean = holdings.containStock(stock)
  def removeTradeHistories(stock: Stock, tradeHistories: TradeHistory *): PortfolioState = copy(holdings = holdings.removeStockHistories(stock, tradeHistories: _*))
  def addTradeHistories(stock: Stock, tradeHistories: TradeHistory *): PortfolioState = copy(holdings = holdings.addStockHistories(stock, tradeHistories: _*))
  def updateTradeHistory(stock: Stock, lastTradeHistory: TradeHistory, newTradeHistory: TradeHistory): PortfolioState = copy(holdings = holdings.updateStockHistory(stock, lastTradeHistory, newTradeHistory))
  def addStockHolding(stockHolding: StockHolding): PortfolioState = copy(holdings = holdings.addStockHolding(stockHolding))
  def addCashHolding(cashHolding: CashHolding): PortfolioState = copy(holdings = holdings.addCashHolding(cashHolding))
  def removeStockHolding(stock: Stock): PortfolioState = copy(holdings = holdings.removeStockHolding(stock))
  def isLimitStockSize: Boolean = holdings.isLimitStockSize
}
object PortfolioState {
  implicit val format:Format[PortfolioState] = Json.format
  def empty: PortfolioState = PortfolioState(PortfolioId.empty, "", 0, UserId.empty
    , GoalAssetRatio.empty, AssetCategory.empty, Holdings.empty)
}