package com.wealthview.core.projection.household;

/**
 * Identifies a member of a {@link HouseholdContext}. A single-person household only ever uses
 * {@link #PRIMARY}; {@link #SPOUSE} is only meaningful when {@link HouseholdContext#isHousehold()}.
 */
public enum PersonId {
    PRIMARY,
    SPOUSE
}
