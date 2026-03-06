package com.wealthview.core.importservice.dto;

public record ImportResult(int successCount, int skippedDuplicates, int failedCount) {}
