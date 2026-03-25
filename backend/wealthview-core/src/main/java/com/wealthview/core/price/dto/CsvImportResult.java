package com.wealthview.core.price.dto;

import java.util.List;

public record CsvImportResult(int imported, List<String> errors) {
}
