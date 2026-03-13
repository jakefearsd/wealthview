package com.wealthview.persistence.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class PriceId implements Serializable {

    private String symbol;
    private LocalDate date;

    public PriceId() {
    }

    public PriceId(String symbol, LocalDate date) {
        this.symbol = symbol;
        this.date = date;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PriceId priceId = (PriceId) o;
        return Objects.equals(symbol, priceId.symbol) && Objects.equals(date, priceId.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, date);
    }
}
