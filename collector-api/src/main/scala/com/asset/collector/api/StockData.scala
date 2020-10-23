package com.asset.collector.api

import com.asset.collector.api.Country.Country
import com.asset.collector.api.Market.Market
import play.api.libs.json.{Format, JsPath, JsResult, JsValue, Json, Reads, Writes}
import play.api.libs.functional.syntax._


sealed trait Category
case class Apartment(name1:String) extends Category
object Apartment{
  implicit val format: Format[Apartment] = Json.format
}
case class MultiRoom(name1:String) extends Category
object MultiRoom{
  implicit val format: Format[MultiRoom] = Json.format
}

case class Test(category:String, content:Category)
object Test{


  val reads: Reads[Test] =
    (JsPath \ "category").read[String].flatMap{ category =>
      category match {
        case "MultiRoom" => ((JsPath \ "category").read[String] and (JsPath \ "content").read[MultiRoom])(Test.apply _)
        case "Apartment" => ((JsPath \ "category").read[String] and (JsPath \ "content").read[Apartment])(Test.apply _)
      }
    }

  val writes = new Writes[Test] {
    def writes(o: Test) =
      o.category match {
        case "MultiRoom" => Json.obj("category"->o.category, "content"->o.content.asInstanceOf[MultiRoom])
        case "Apartment" => Json.obj("category"->o.category, "content"->o.content.asInstanceOf[Apartment])
      }
  }
  implicit val format: Format[Test] = Format(reads, writes)
}


object Country extends Enumeration {
  type Country = Value

  val KOREA = Value("korea")
  val USA = Value("usa")

  implicit val format1: Format[Country] = Json.formatEnum(Country)

  def toCountry(value:String):Option[Country] = if(value=="korea") Some(KOREA) else if(value=="usa") Some(USA) else None
}


object Market extends Enumeration {
  type Market = Value

  val KOSPI = Value("kospi")
  val KOSDAQ = Value("kosdaq")
  val NASDAQ = Value("nasdaq")
  val DOW = Value("dow")
  val SP500 = Value("sp500")
  val NYSE = Value("nyse")
  val AMEX = Value("amex")
  val ETF = Value("etf")
  val NONE = Value("none")


  implicit val format1: Format[Market] = Json.formatEnum(Market)

  def toMarket(value:String):Option[Market] = value match {
    case "kospi" => Some(KOSPI)
    case "kosdaq" => Some(KOSDAQ)
    case "nasdaq" => Some(NASDAQ)
    case "dow" => Some(DOW)
    case "sp500" => Some(SP500)
    case "nyse" => Some(NYSE)
    case "amex" => Some(AMEX)
    case "etf" => Some(ETF)
    case "none" => Some(NONE)
    case _ => None
  }
}


case class Stock(market:Market, name:String, code:String) {
  override def canEqual(a: Any) = a.isInstanceOf[Stock]

  override def equals(that: Any): Boolean =
    that match {
      case that: Stock =>
        that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode:Int = {
    code.hashCode
  }

  def setMarket(market: Market) = copy(market = market)
}
object Stock {
  implicit val format :Format[Stock]= Json.format
}

case class Price(code:String, date:String, close:BigDecimal, open:BigDecimal, high:BigDecimal, low:BigDecimal, volume:Long)
object Price {
  implicit val format :Format[Price]= Json.format
}

case class NowPrice(code:String, price:BigDecimal, changePercent:BigDecimal)
object NowPrice {
  implicit val format :Format[NowPrice]= Json.format
}

case class KrwUsd(date:String, rate:BigDecimal)
object KrwUsd{
  def empty = KrwUsd("", BigDecimal(1150))
  implicit val format :Format[KrwUsd]= Json.format
}