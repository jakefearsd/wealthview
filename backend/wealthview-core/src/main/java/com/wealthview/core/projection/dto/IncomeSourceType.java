package com.wealthview.core.projection.dto;

/**
 * Enumeration of the valid income source categories used by the retirement projection engine.
 *
 * <p>These values correspond to the {@code income_type} column on the {@code income_sources} table.
 * The string representations are the canonical API / database values; use {@link #fromString(String)}
 * to convert inbound strings (e.g. from JSON deserialization or entity mapping) to this type.
 *
 * <p>Each value drives dispatch in {@code IncomeSourceProcessor}: rental properties receive
 * expense netting and passive-loss treatment; Social Security receives the provisional-income
 * taxability formula; part-time work optionally triggers self-employment tax; and the remaining
 * types (pension, annuity, other) are treated as fully taxable unless the {@code taxTreatment}
 * field overrides them.
 */
public enum IncomeSourceType {

    RENTAL_PROPERTY("rental_property"),
    SOCIAL_SECURITY("social_security"),
    PENSION("pension"),
    PART_TIME_WORK("part_time_work"),
    ANNUITY("annuity"),
    OTHER("other");

    private final String value;

    IncomeSourceType(String value) {
        this.value = value;
    }

    /** Returns the canonical database / API string representation. */
    public String getValue() {
        return value;
    }

    /**
     * Converts a raw string (from the database or JSON) to the corresponding enum constant.
     *
     * @param value the canonical string value (e.g. {@code "rental_property"})
     * @return the matching enum constant
     * @throws IllegalArgumentException if the value is not a recognised income type
     */
    public static IncomeSourceType fromString(String value) {
        for (IncomeSourceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown income source type: '" + value + "'");
    }
}
