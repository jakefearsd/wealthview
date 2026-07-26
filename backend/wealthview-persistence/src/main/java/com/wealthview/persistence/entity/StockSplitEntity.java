package com.wealthview.persistence.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Global stock split row. NOT scoped to a tenant — splits are market events
 * that apply to every tenant holding the symbol. Deliberately no
 * {@code @Filter("tenantFilter")}: see {@link MobileAppVersionEntity} for the
 * same pattern.
 */
@Entity
@Table(name = "stock_splits")
public class StockSplitEntity extends UuidAuditable {

    @Column(nullable = false)
    private String symbol;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false)
    private int numerator;

    @Column(nullable = false)
    private int denominator;

    @Column(nullable = false)
    private String source;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt = OffsetDateTime.now();

    @Column
    private String notes;

    protected StockSplitEntity() {
    }

    public StockSplitEntity(String symbol, LocalDate effectiveDate, int numerator, int denominator, String source) {
        this.symbol = symbol;
        this.effectiveDate = effectiveDate;
        this.numerator = numerator;
        this.denominator = denominator;
        this.source = source;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public int getNumerator() {
        return numerator;
    }

    public int getDenominator() {
        return denominator;
    }

    public String getSource() {
        return source;
    }

    public OffsetDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(OffsetDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

}
