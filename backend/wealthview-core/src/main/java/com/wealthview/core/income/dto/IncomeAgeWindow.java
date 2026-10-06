package com.wealthview.core.income.dto;

import jakarta.validation.constraints.AssertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The age window shared by the create and update income-source requests, with the one rule that
 * spans both fields (kept here so the two otherwise-identical records do not each repeat it).
 */
interface IncomeAgeWindow {

    int startAge();

    Integer endAge();

    /** An income window that ends before it starts is meaningless. */
    @JsonIgnore
    @AssertTrue(message = "end_age must not be before start_age")
    default boolean isEndAgeNotBeforeStartAge() {
        return endAge() == null || endAge() >= startAge();
    }
}
