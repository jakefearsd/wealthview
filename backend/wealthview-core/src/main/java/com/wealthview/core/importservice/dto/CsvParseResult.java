package com.wealthview.core.importservice.dto;

import java.util.List;

public record CsvParseResult(
        List<ParsedTransaction> transactions,
        List<CsvRowError> errors
) {
}
