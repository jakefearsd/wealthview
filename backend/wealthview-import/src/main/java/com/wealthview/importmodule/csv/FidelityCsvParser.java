package com.wealthview.importmodule.csv;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.TransactionType;

@Component("fidelityCsvParser")
public class FidelityCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Run Date";
    private static final Map<String, TransactionType> ACTION_MAP = Map.of(
            "YOU BOUGHT", TransactionType.BUY,
            "REINVESTMENT", TransactionType.BUY,
            "YOU SOLD", TransactionType.SELL,
            "DIVIDEND RECEIVED", TransactionType.DIVIDEND
    );
    private static final Set<String> SIGN_DEPENDENT_ACTIONS = Set.of("ELECTRONIC FUNDS TRANSFER");

    @Override
    protected String getHeaderMarker() {
        return HEADER_MARKER;
    }

    @Override
    protected DateTimeFormatter getDateFormat() {
        return DATE_FORMAT;
    }

    @Override
    protected String getAmountColumn() {
        return "Amount ($)";
    }

    @Override
    protected Map<String, TransactionType> getActionMap() {
        return ACTION_MAP;
    }

    @Override
    protected Set<String> getSignDependentActions() {
        return SIGN_DEPENDENT_ACTIONS;
    }
}
