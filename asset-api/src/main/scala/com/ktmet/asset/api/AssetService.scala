package com.ktmet.asset.api

import akka.{Done, NotUsed}
import com.asset.collector.api.{KrwUsd, NowPrice, Stock}
import com.ktmet.asset.api.message.{AddingCategoryMessage, AddingStockMessage, AddingTradeHistoryMessage, CashFlowHistoryAddedMessage, CashFlowHistoryDeletedMessage, CashFlowHistoryMessage, CashFlowHistoryUpdatedMessage, CreatingPortfolioMessage, DeletingCashFlowHistory, DeletingStockMessage, DeletingTradeHistoryMessage, LoginMessage, NowPrices, PortfolioCreatedMessage, PortfolioMessage, PortfolioStatusMessage, PortfolioStockMessage, RefreshingToken, SocialLoggingIn, StockAddedMessage, StockCategoryUpdatedMessage, StockDeletedMessage, TimestampMessage, TokenMessage, TradeHistoryAddedMessage, TradeHistoryDeletedMessage, TradeHistoryUpdatedMessage, UpdatingCashFlowHistory, UpdatingGoalAssetRatioMessage, UpdatingStockCategory, UpdatingTradeHistoryMessage, UserMessage}
import com.ktmet.asset.common.api.ClientExceptionSerializer
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceAcl, ServiceCall}
import play.api.Environment


trait AssetService extends Service{



  def login: ServiceCall[SocialLoggingIn, LoginMessage]
  def logout: ServiceCall[NotUsed, Done]
  def deleteUser: ServiceCall[NotUsed, Done]
  def refreshToken: ServiceCall[RefreshingToken, TokenMessage]
  def getUser: ServiceCall[NotUsed, UserMessage]


  def autoCompleteStock(prefix:String): ServiceCall[NotUsed, AutoCompleteMessage]
  def getNowPrice(code:String): ServiceCall[NotUsed, NowPrice]
  def getNowPrices(codes: List[String]): ServiceCall[NotUsed, NowPrices]
  def getNowKrwUsd: ServiceCall[NotUsed, KrwUsd]

  def createPortfolio: ServiceCall[CreatingPortfolioMessage, PortfolioCreatedMessage]
  def deletePortfolio(portfolioId: String): ServiceCall[NotUsed, Done]
  def getPortfolio(portfolioId: String): ServiceCall[NotUsed, PortfolioMessage]
  def addCategory(portfolioId: String): ServiceCall[AddingCategoryMessage, TimestampMessage]
  def updateGoalAssetRatio(portfolioId: String): ServiceCall[UpdatingGoalAssetRatioMessage, TimestampMessage]
  def addStock(portfolioId: String): ServiceCall[AddingStockMessage, StockAddedMessage]
  def deleteStock(portfolioId: String): ServiceCall[DeletingStockMessage, StockDeletedMessage]
  def addTradeHistory(portfolioId: String): ServiceCall[AddingTradeHistoryMessage, TradeHistoryAddedMessage]
  def deleteTradeHistory(portfolioId: String): ServiceCall[DeletingTradeHistoryMessage, TradeHistoryDeletedMessage]
  def updateTradeHistory(portfolioId: String): ServiceCall[UpdatingTradeHistoryMessage, TradeHistoryUpdatedMessage]
  def addCashFlowHistory(portfolioId: String): ServiceCall[CashFlowHistoryMessage, CashFlowHistoryAddedMessage]
  def deleteCashFlowHistory(portfolioId: String): ServiceCall[DeletingCashFlowHistory, CashFlowHistoryDeletedMessage]
  def updateCashFlowHistory(portfolioId: String): ServiceCall[UpdatingCashFlowHistory, CashFlowHistoryUpdatedMessage]
  def updateStockCategory(portfolioId: String): ServiceCall[UpdatingStockCategory, StockCategoryUpdatedMessage]
  def getPortfolioStatus(portfolioId: String): ServiceCall[NotUsed, PortfolioStatusMessage]
  def getPortfolioStock(portfolioId: String, stock: String): ServiceCall[NotUsed, PortfolioStockMessage]



  def test: ServiceCall[NotUsed, Done]


  override def descriptor: Descriptor = {

    import Service._
    named("asset")
      .withCalls(

        restCall(Method.POST, "/user/login", login),
        restCall(Method.DELETE, "/user/logout", logout),
        restCall(Method.DELETE, "/user", deleteUser),
        restCall(Method.POST, "/user/refresh", refreshToken),
        restCall(Method.GET, "/user", getUser),

        restCall(Method.GET, "/search/prefix/:prefix", autoCompleteStock _),
        restCall(Method.GET, "/stock/now/:code", getNowPrice _),
        restCall(Method.GET, "/krwusd", getNowKrwUsd _),
        restCall(Method.GET, "/stock/now?codes", getNowPrices _),

        restCall(Method.POST, "/portfolio", createPortfolio),
        restCall(Method.DELETE, "/portfolio/:portfolioId", deletePortfolio _),
        restCall(Method.GET, "/portfolio/:portfolioId", getPortfolio _),
        restCall(Method.POST, "/portfolio/:portfolioId/category", addCategory _),
        restCall(Method.POST, "/portfolio/:portfolioId/goal", updateGoalAssetRatio _),
        restCall(Method.POST, "/portfolio/:portfolioId/stock", addStock _),
        restCall(Method.DELETE, "/portfolio/:portfolioId/stock", deleteStock _),
        restCall(Method.PUT, "/portfolio/:portfolioId/stock/history", addTradeHistory _),
        restCall(Method.DELETE, "/portfolio/:portfolioId/stock/history", deleteTradeHistory _),
        restCall(Method.POST, "/portfolio/:portfolioId/stock/history", updateTradeHistory _),
        restCall(Method.PUT, "/portfolio/:portfolioId/cash/history", addCashFlowHistory _),
        restCall(Method.DELETE, "/portfolio/:portfolioId/cash/history", deleteCashFlowHistory _),
        restCall(Method.POST, "/portfolio/:portfolioId/cash/history", updateCashFlowHistory _),
        restCall(Method.POST, "/portfolio/:portfolioId/stock/category", updateStockCategory _),
        restCall(Method.GET, "/portfolio/:portfolioId/status", getPortfolioStatus _),
        restCall(Method.GET, "/portfolio/:portfolioId/stock/info/:stock", getPortfolioStock _),

        restCall(Method.GET, "/test", test)

      ).withAutoAcl(true)
      .withExceptionSerializer(new ClientExceptionSerializer(Environment.simple()))
  }


}
