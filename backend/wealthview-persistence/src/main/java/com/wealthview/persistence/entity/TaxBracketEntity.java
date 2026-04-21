package com.wealthview.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "tax_brackets")
public class TaxBracketEntity extends AbstractTaxBracketEntity {

    protected TaxBracketEntity() {
        super();
    }

    public TaxBracketEntity(int taxYear, String filingStatus, BigDecimal bracketFloor,
                             BigDecimal bracketCeiling, BigDecimal rate) {
        super(taxYear, filingStatus, bracketFloor, bracketCeiling, rate);
    }
}
