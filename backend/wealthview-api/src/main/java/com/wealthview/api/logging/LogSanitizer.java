package com.wealthview.api.logging;

/**
 * Strips line-break and tab characters from user-controlled strings before they
 * hit the logger. Prevents an attacker from forging synthetic log lines by
 * embedding CRLF in filenames, emails, URLs, or validation field names.
 */
public final class LogSanitizer {

    private LogSanitizer() {}

    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "").replace("\t", "");
    }
}
