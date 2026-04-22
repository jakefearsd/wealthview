package com.wealthview.api.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void sanitize_null_returnsEmptyString() {
        assertThat(LogSanitizer.sanitize(null)).isEmpty();
    }

    @Test
    void sanitize_plainText_returnsUnchanged() {
        assertThat(LogSanitizer.sanitize("admin@wealthview.local"))
                .isEqualTo("admin@wealthview.local");
    }

    @Test
    void sanitize_carriageReturn_stripped() {
        assertThat(LogSanitizer.sanitize("evil\rINJECTED-LINE"))
                .isEqualTo("evilINJECTED-LINE");
    }

    @Test
    void sanitize_newline_stripped() {
        assertThat(LogSanitizer.sanitize("evil\nINJECTED-LINE"))
                .isEqualTo("evilINJECTED-LINE");
    }

    @Test
    void sanitize_crlf_stripped() {
        assertThat(LogSanitizer.sanitize("attacker\r\n2025-01-01 FAKE LOG LINE"))
                .isEqualTo("attacker2025-01-01 FAKE LOG LINE");
    }

    @Test
    void sanitize_tab_stripped() {
        assertThat(LogSanitizer.sanitize("value\twith\ttabs"))
                .isEqualTo("valuewithtabs");
    }

    @Test
    void sanitize_mixedControlChars_allStripped() {
        assertThat(LogSanitizer.sanitize("a\rb\nc\td"))
                .isEqualTo("abcd");
    }
}
