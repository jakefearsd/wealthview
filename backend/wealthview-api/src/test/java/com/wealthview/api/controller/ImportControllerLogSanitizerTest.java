package com.wealthview.api.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportControllerLogSanitizerTest {

    @Test
    void sanitizeForLog_withNewline_stripsNewline() {
        assertThat(ImportController.sanitizeForLog("evil.csv\nWARN fake"))
                .isEqualTo("evil.csv_WARN fake");
    }

    @Test
    void sanitizeForLog_withCarriageReturn_stripsCarriageReturn() {
        assertThat(ImportController.sanitizeForLog("evil.csv\rINFO fake"))
                .isEqualTo("evil.csv_INFO fake");
    }

    @Test
    void sanitizeForLog_withCrlf_stripsBoth() {
        assertThat(ImportController.sanitizeForLog("a\r\nb")).isEqualTo("a__b");
    }

    @Test
    void sanitizeForLog_null_returnsNull() {
        assertThat(ImportController.sanitizeForLog(null)).isNull();
    }

    @Test
    void sanitizeForLog_cleanString_unchanged() {
        assertThat(ImportController.sanitizeForLog("transactions.csv")).isEqualTo("transactions.csv");
    }
}
