package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One (tax_year, filing_status) MAGI bracket of the IRMAA (Income-Related Monthly Adjustment
 * Amount) sliding scale. {@code partBSurcharge} is ALREADY NETTED against the standard Part B
 * premium (i.e., it is the extra monthly amount over standard, zero for the below-threshold
 * bracket) so callers never need a separate "standard premium" lookup -- see migration V075.
 */
@Entity
@Table(name = "irmaa_tiers")
public class IrmaaTierEntity extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "filing_status", nullable = false)
    private String filingStatus;

    @Column(name = "magi_floor", nullable = false, precision = 19, scale = 4)
    private BigDecimal magiFloor;

    @Column(name = "magi_ceiling", precision = 19, scale = 4)
    private BigDecimal magiCeiling;

    @Column(name = "part_b_surcharge", nullable = false, precision = 19, scale = 4)
    private BigDecimal partBSurcharge;

    @Column(name = "part_d_surcharge", nullable = false, precision = 19, scale = 4)
    private BigDecimal partDSurcharge;

    protected IrmaaTierEntity() {
        super();
    }

    public IrmaaTierEntity(int taxYear, String filingStatus, BigDecimal magiFloor, BigDecimal magiCeiling,
                            BigDecimal partBSurcharge, BigDecimal partDSurcharge) {
        this.taxYear = taxYear;
        this.filingStatus = filingStatus;
        this.magiFloor = magiFloor;
        this.magiCeiling = magiCeiling;
        this.partBSurcharge = partBSurcharge;
        this.partDSurcharge = partDSurcharge;
    }

    public UUID getId() {
        return id;
    }

    public int getTaxYear() {
        return taxYear;
    }

    public String getFilingStatus() {
        return filingStatus;
    }

    public BigDecimal getMagiFloor() {
        return magiFloor;
    }

    public BigDecimal getMagiCeiling() {
        return magiCeiling;
    }

    public BigDecimal getPartBSurcharge() {
        return partBSurcharge;
    }

    public BigDecimal getPartDSurcharge() {
        return partDSurcharge;
    }
}
