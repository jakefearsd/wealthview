package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * {@code ltcg_brackets.rate} is {@code numeric(6,4)} (V071), whereas the {@code rate}
 * column mapped by {@link AbstractTaxBracketEntity} is {@code numeric(5,4)} — the shape of
 * {@code tax_brackets} (V013) and {@code state_tax_brackets} (V043). The override below
 * realigns the mapping with the actual column; the migrations are correct and unchanged.
 */
@Entity
@Table(name = "ltcg_brackets")
@AttributeOverride(name = "rate", column = @Column(name = "rate", nullable = false, precision = 6, scale = 4))
public class LtcgBracketEntity extends AbstractTaxBracketEntity {

    protected LtcgBracketEntity() {
        super();
    }

    public LtcgBracketEntity(int taxYear, String filingStatus, BigDecimal bracketFloor,
                              BigDecimal bracketCeiling, BigDecimal rate) {
        super(taxYear, filingStatus, bracketFloor, bracketCeiling, rate);
    }
}
