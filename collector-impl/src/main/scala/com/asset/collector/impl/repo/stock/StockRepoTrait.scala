package com.asset.collector.impl.repo.stock

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.asset.collector.api.Country.Country
import com.asset.collector.api.Market.Market
import com.asset.collector.api.{ClosePrice, KrwUsd, NowPrice, Price, Stock}

trait StockRepoTrait[F[_]] {
  def createStockTable(country:Country):F[Done]
  def selectStocks(country: Country):F[Seq[Stock]]
  def insertStock(country: Country, stock:Stock):F[Done]
  def insertBatchStock(country: Country, stocks:Seq[Stock]):F[Done]
  def deleteStock(country: Country, stock:Stock):F[Done]

  def createPriceTable(country:Country):F[Done]
  def insertPrice(country:Country, price:Price):F[Done]
  def insertBatchPrice(country: Country, prices:Seq[Price]):F[Done]
  def selectClosePricesAfterDate(stock: Stock, date:String): Source[ClosePrice, NotUsed]
  def selectClosePricesAfterDate1(stock: Stock, date:String): F[Seq[ClosePrice]]

  def createNowPriceTable(country: Country):F[Done]
  def selectNowPrices(country: Country):F[Seq[NowPrice]]
  def insertBatchNowPrice(country: Country, prices:Seq[NowPrice]):F[Done]

  def createKrwUsdTable:F[Done]
  def insertBatchKrwUsd(krwUsds:Seq[KrwUsd]):F[Done]
  def selectKrwUsdsAfterDate(date: String): F[Seq[KrwUsd]]
}
