package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

/**
 * Audit/undo trail for a stock split application. Per-tenant — one split
 * row triggers many adjustment rows, one per affected transaction field and
 * one per affected price row (recorded against the first tenant that
 * triggered the apply, since prices are global). Filtered by tenant via
 * Hibernate filter so unrelated tenants never see each other's adjustments.
 */
@Entity
@Table(name = "stock_split_adjustments")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class StockSplitAdjustmentEntity extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "split_id", nullable = false)
    private StockSplitEntity split;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "target_table", nullable = false)
    private String targetTable;

    @Column(name = "target_row_id", nullable = false)
    private UUID targetRowId;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Column(name = "old_value", nullable = false, precision = 19, scale = 8)
    private BigDecimal oldValue;

    @Column(name = "new_value", nullable = false, precision = 19, scale = 8)
    private BigDecimal newValue;

    protected StockSplitAdjustmentEntity() {
    }

    public StockSplitAdjustmentEntity(StockSplitEntity split, UUID tenantId, String targetTable,
                                      UUID targetRowId, String fieldName,
                                      BigDecimal oldValue, BigDecimal newValue) {
        this.split = split;
        this.tenantId = tenantId;
        this.targetTable = targetTable;
        this.targetRowId = targetRowId;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public UUID getId() {
        return id;
    }

    public StockSplitEntity getSplit() {
        return split;
    }

    public UUID getSplitId() {
        return split.getId();
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public UUID getTargetRowId() {
        return targetRowId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public BigDecimal getOldValue() {
        return oldValue;
    }

    public BigDecimal getNewValue() {
        return newValue;
    }

}
