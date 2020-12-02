package com.ktmet.asset.api.message

import com.asset.collector.api.Stock
import com.ktmet.asset.api.{AssetCategory, BoughtStockCashHistory, BuyTradeHistory, CashFlowHistory, Category, DepositHistory, GoalAssetRatio, HistorySet, SellTradeHistory, SoldStockCashHistory, WithdrawHistory}

object PortfolioMessageConverter {
  def toObject(message: TradeHistoryMessage, stock: Stock, tradeId: String, cashId: String): HistorySet =
    message match {
      case BuyTradeHistoryMessage(amount, price, timestamp) =>
        HistorySet(BuyTradeHistory(tradeId, stock, amount, price, timestamp, cashId, None, None))
      case SellTradeHistoryMessage(amount, price, timestamp) =>
        HistorySet(SellTradeHistory(tradeId, stock, amount, price, timestamp, cashId
          , BigDecimal(0), BigDecimal(0)))
    }
  def toObject(message: CashFlowHistoryMessage, cashId: String): CashFlowHistory =
    message match {
      case DepositHistoryMessage(balance, country, timestamp) =>
        DepositHistory(cashId, country, balance, timestamp)
      case WithdrawHistoryMessage(balance, country, timestamp) =>
        WithdrawHistory(cashId, country, balance, timestamp)
      case SoldStockCashHistoryMessage(balance, country, timestamp) =>
        SoldStockCashHistory(cashId, country, balance, timestamp)
      case BoughtStockCashHistoryMessage(balance, country, timestamp) =>
        BoughtStockCashHistory(cashId, country, balance, timestamp)
    }
  def toObject(message: RatiosMessage): GoalAssetRatio =
    GoalAssetRatio(message.stockRatios.map{ case (k, v) => Category(k)->v}
      , message.cashRatios.map{ case (k, v) => Category(k)->v})
  def toObject(message: CategoriesMessage): AssetCategory =
    AssetCategory(message.stockCategory.map{ case (k, v) => Category(k)->v}
      , message.cashCategory.map{ case (k, v) => Category(k)->v})
}
