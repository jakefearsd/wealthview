package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

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
