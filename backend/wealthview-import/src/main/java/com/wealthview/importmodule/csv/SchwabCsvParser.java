package com.wealthview.importmodule.csv;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.persistence.entity.TransactionType;

@Component("schwabCsvParser")
public class SchwabCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Date";
    private static final Map<String, TransactionType> ACTION_MAP = Map.ofEntries(
            Map.entry("BUY", TransactionType.BUY),
            Map.entry("SELL", TransactionType.SELL),
            Map.entry("BUY TO COVER", TransactionType.BUY),
            Map.entry("SELL SHORT", TransactionType.SELL),
            Map.entry("CASH DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("QUALIFIED DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("NON-QUALIFIED DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("SPECIAL DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("PRIOR YEAR CASH DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("REINVEST DIVIDEND", TransactionType.BUY),
            Map.entry("QUAL DIV REINVEST", TransactionType.BUY),
            Map.entry("PRIOR YEAR DIV REINVEST", TransactionType.BUY),
            Map.entry("LONG TERM CAP GAIN REINVEST", TransactionType.BUY),
            Map.entry("SHORT TERM CAP GAIN REINVEST", TransactionType.BUY),
            Map.entry("BANK INTEREST", TransactionType.DIVIDEND),
            Map.entry("CREDIT INTEREST", TransactionType.DIVIDEND),
            Map.entry("MARGIN INTEREST", TransactionType.WITHDRAWAL),
            Map.entry("CASH IN LIEU", TransactionType.DIVIDEND)
    );

    private static final Set<String> SIGN_DEPENDENT_ACTIONS = Set.of(
            "MONEYLINK TRANSFER",
            "WIRE FUNDS",
            "JOURNAL"
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
    protected boolean isHeaderLine(String line) {
        var stripped = line.replace("\"", "").trim();
        return stripped.startsWith(HEADER_MARKER + ",") || stripped.equals(HEADER_MARKER);
    }

    @Override
    protected Map<String, TransactionType> getActionMap() {
        return ACTION_MAP;
    }

    @Override
    protected Set<String> getSignDependentActions() {
        return SIGN_DEPENDENT_ACTIONS;
    }

    @Override
    protected boolean isSkippableRow(String dateValue) {
        return dateValue.contains("Total") || dateValue.contains("total");
    }

    /**
     * Schwab exports are known to include malformed/truncated trailing rows; unlike Fidelity and
     * Vanguard, an unparseable date here is silently skipped rather than reported as a CsvRowError.
     * This is a pre-existing, intentional divergence — not an oversight to be "fixed" into consistency.
     */
    @Override
    protected void handleInvalidDate(int rowNum, String rawDate, List<CsvRowError> errors) {
        // Intentionally silent — see method Javadoc.
    }
}
