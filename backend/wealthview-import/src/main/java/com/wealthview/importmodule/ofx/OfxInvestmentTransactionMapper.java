package com.wealthview.importmodule.ofx;

import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseBuyInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseSellInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.IncomeTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.ReinvestIncomeTransaction;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.wealthview.core.importservice.dto.ParsedTransaction;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Maps OFX4J investment transactions (buy/sell/income/reinvest) to {@link ParsedTransaction}.
 * Extracted from OfxTransactionParser to keep that class focused on orchestration.
 */
final class OfxInvestmentTransactionMapper {

    private OfxInvestmentTransactionMapper() {
    }

    /** Returns a parsed transaction, or null if the investment type is not supported. */
    static ParsedTransaction map(BaseInvestmentTransaction invTxn, Map<String, String> tickerMap) {
        if (invTxn instanceof BaseBuyInvestmentTransaction buy) {
            return buildBuyOrSell(buy.getSecurityId(), tickerMap, buy.getTradeDate(),
                    "buy", buy.getUnits(), buy.getTotal());
        }
        if (invTxn instanceof BaseSellInvestmentTransaction sell) {
            return buildBuyOrSell(sell.getSecurityId(), tickerMap, sell.getTradeDate(),
                    "sell", sell.getUnits(), sell.getTotal());
        }
        if (invTxn instanceof IncomeTransaction income) {
            return new ParsedTransaction(
                    OfxDateUtils.toLocalDate(income.getTradeDate()),
                    "dividend",
                    resolveSymbol(income.getSecurityId(), tickerMap),
                    null,
                    absOrNull(income.getTotal()));
        }
        if (invTxn instanceof ReinvestIncomeTransaction reinvest) {
            return buildBuyOrSell(reinvest.getSecurityId(), tickerMap, reinvest.getTradeDate(),
                    "buy", reinvest.getUnits(), reinvest.getTotal());
        }
        return null;
    }

    private static ParsedTransaction buildBuyOrSell(SecurityId secId, Map<String, String> tickerMap,
                                                    java.util.Date tradeDate, String type,
                                                    Double units, Double total) {
        return new ParsedTransaction(
                OfxDateUtils.toLocalDate(tradeDate),
                type,
                resolveSymbol(secId, tickerMap),
                absOrNull(units),
                absOrNull(total));
    }

    private static String resolveSymbol(SecurityId secId, Map<String, String> tickerMap) {
        if (secId == null) {
            return null;
        }
        var ticker = tickerMap.get(secId.getUniqueId());
        return ticker != null ? ticker : secId.getUniqueId();
    }

    private static BigDecimal absOrNull(Double value) {
        return value != null ? BigDecimal.valueOf(Math.abs(value)) : null;
    }
}
