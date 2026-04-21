package com.wealthview.core.importservice.dto;

import java.util.List;

public record ImportParseResult(
        List<ParsedTransaction> transactions,
        List<CsvRowError> errors
) {
}
