package com.wealthview.importmodule.csv;

import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.TransactionType;

@Component("vanguardCsvParser")
public class VanguardCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Trade Date";
    private static final Map<String, TransactionType> ACTION_MAP = Map.ofEntries(
            Map.entry("BUY", TransactionType.BUY),
            Map.entry("SELL", TransactionType.SELL),
            Map.entry("DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("REINVESTMENT", TransactionType.BUY),
            Map.entry("CAPITAL GAIN (LT)", TransactionType.DIVIDEND),
            Map.entry("CAPITAL GAIN (ST)", TransactionType.DIVIDEND),
            Map.entry("TRANSFER (INCOMING)", TransactionType.DEPOSIT),
            Map.entry("TRANSFER (OUTGOING)", TransactionType.WITHDRAWAL),
            Map.entry("SWEEP IN", TransactionType.DEPOSIT),
            Map.entry("SWEEP OUT", TransactionType.WITHDRAWAL)
    );

    @Override
    protected String getHeaderMarker() {
        return HEADER_MARKER;
    }

    @Override
    protected DateTimeFormatter getDateFormat() {
        return DATE_FORMAT;
    }

    @Override
    protected String getActionColumn() {
        return "Transaction Type";
    }

    @Override
    protected String getAmountColumn() {
        return "Net Amount";
    }

    @Override
    protected String getQuantityColumn() {
        return "Shares";
    }

    @Override
    protected Map<String, TransactionType> getActionMap() {
        return ACTION_MAP;
    }
}
