package com.wealthview.importmodule.ofx;

import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseBuyInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseSellInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.IncomeTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.ReinvestIncomeTransaction;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.wealthview.core.importservice.dto.ParsedTransaction;

import java.math.BigDecimal;
import java.time.Instant;
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
        return switch (invTxn) {
            case BaseBuyInvestmentTransaction buy -> buildBuyOrSell(buy.getSecurityId(), tickerMap,
                    tradeDateAsInstant(buy), "buy", buy.getUnits(), buy.getTotal());
            case BaseSellInvestmentTransaction sell -> buildBuyOrSell(sell.getSecurityId(), tickerMap,
                    tradeDateAsInstant(sell), "sell", sell.getUnits(), sell.getTotal());
            case ReinvestIncomeTransaction reinvest -> buildBuyOrSell(reinvest.getSecurityId(), tickerMap,
                    tradeDateAsInstant(reinvest), "buy", reinvest.getUnits(), reinvest.getTotal());
            case IncomeTransaction income -> new ParsedTransaction(
                    OfxDateUtils.toLocalDate(tradeDateAsInstant(income)),
                    "dividend",
                    resolveSymbol(income.getSecurityId(), tickerMap),
                    null,
                    absOrNull(income.getTotal()));
            case null, default -> null;
        };
    }

    private static ParsedTransaction buildBuyOrSell(SecurityId secId, Map<String, String> tickerMap,
                                                    Instant tradeDate, String type,
                                                    Double units, Double total) {
        return new ParsedTransaction(
                OfxDateUtils.toLocalDate(tradeDate),
                type,
                resolveSymbol(secId, tickerMap),
                absOrNull(units),
                absOrNull(total));
    }

    /**
     * Reads the trade date from an OFX4J investment transaction, converting the
     * library's {@code java.util.Date} to {@link Instant} at this boundary only.
     */
    private static Instant tradeDateAsInstant(BaseInvestmentTransaction txn) {
        var d = txn.getTradeDate();
        return d != null ? d.toInstant() : null;
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
