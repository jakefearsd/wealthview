package com.wealthview.importmodule.ofx;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

final class OfxDateUtils {
    private OfxDateUtils() {
    }

    static LocalDate toLocalDate(Date date) {
        if (date == null) {
            return LocalDate.now();
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
