package com.wealthview.importmodule.ofx;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

final class OfxDateUtils {
    private OfxDateUtils() {
    }

    /**
     * Converts an {@link Instant} to a {@link LocalDate} in UTC. The OFX spec defines a date with
     * no time-zone suffix as GMT, so OFX4J yields GMT midnight for the date-only DTPOSTED/DTTRADE
     * values banks send. Reading that in the JVM's default zone would shift every transaction one
     * day early on any host west of UTC.
     * Callers receiving a {@code java.util.Date} from the OFX4J library should convert via
     * {@code date.toInstant()} before passing here, keeping {@code java.util.Date} confined
     * to the OFX4J boundary.
     */
    static LocalDate toLocalDate(Instant instant) {
        if (instant == null) {
            return LocalDate.now();
        }
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
