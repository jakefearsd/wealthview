package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "property_expenses")
public class PropertyExpenseEntity extends AbstractPropertyCashFlowEntity {

    protected PropertyExpenseEntity() {
        super();
    }

    public PropertyExpenseEntity(PropertyEntity property, TenantEntity tenant,
                                 LocalDate date, BigDecimal amount, String category, String description) {
        this(property, tenant, date, amount, category, description, "monthly");
    }

    public PropertyExpenseEntity(PropertyEntity property, TenantEntity tenant,
                                 LocalDate date, BigDecimal amount, String category,
                                 String description, String frequency) {
        super(property, tenant, date, amount, category, description, frequency);
    }
}
