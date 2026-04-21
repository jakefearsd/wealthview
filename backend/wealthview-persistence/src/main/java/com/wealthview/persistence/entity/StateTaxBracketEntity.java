package com.wealthview.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "state_tax_brackets")
public class StateTaxBracketEntity extends AbstractTaxBracketEntity {

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    protected StateTaxBracketEntity() {
        super();
    }

    public StateTaxBracketEntity(String stateCode, int taxYear, String filingStatus,
                                  BigDecimal bracketFloor, BigDecimal bracketCeiling, BigDecimal rate) {
        super(taxYear, filingStatus, bracketFloor, bracketCeiling, rate);
        this.stateCode = stateCode;
    }

    public String getStateCode() { return stateCode; }
}
