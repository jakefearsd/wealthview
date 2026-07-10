package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ltcg_brackets")
public class LtcgBracketEntity extends AbstractTaxBracketEntity {

    protected LtcgBracketEntity() {
        super();
    }

    public LtcgBracketEntity(int taxYear, String filingStatus, BigDecimal bracketFloor,
                              BigDecimal bracketCeiling, BigDecimal rate) {
        super(taxYear, filingStatus, bracketFloor, bracketCeiling, rate);
    }
}
