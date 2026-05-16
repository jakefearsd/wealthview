package com.wealthview.importmodule.ofx;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class OfxDateUtils {
    private OfxDateUtils() {
    }

    /**
     * Converts an {@link Instant} to a {@link LocalDate} using the system default time zone.
     * Callers receiving a {@code java.util.Date} from the OFX4J library should convert via
     * {@code date.toInstant()} before passing here, keeping {@code java.util.Date} confined
     * to the OFX4J boundary.
     */
    static LocalDate toLocalDate(Instant instant) {
        if (instant == null) {
            return LocalDate.now();
        }
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
