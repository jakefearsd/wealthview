package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests targeting the conditional branches in entity methods and constructors
 * that are not exercised by the reflection-based EntityAccessorRoundtripTest.
 *
 * Branches covered:
 *   - AccountEntity(TenantEntity, String, String, String, String): null-currency else-branch
 *   - SpendingProfileEntity(TenantEntity, String, BigDecimal, BigDecimal, String): null-tiers else-branch
 *   - ProjectionAccountEntity(…, String accountType): null-accountType else-branch
 *   - InviteCodeEntity.isConsumed(): true and false paths
 *   - PropertyEntity.hasLoanDetails(): fully populated (true) and missing fields (false)
 */
class EntityBranchCoverageTest {

    // -------------------------------------------------------------------------
    // AccountEntity — null currency branch
    // -------------------------------------------------------------------------

    @Test
    void accountEntity_nullCurrency_defaultsToUsd() {
        var account = new AccountEntity(null, "Savings", "investment", "Fidelity", null);

        assertThat(account.getCurrency()).isEqualTo("USD");
    }

    @Test
    void accountEntity_nonNullCurrency_usesProvidedValue() {
        var account = new AccountEntity(null, "Savings", "investment", "Fidelity", "EUR");

        assertThat(account.getCurrency()).isEqualTo("EUR");
    }

    // -------------------------------------------------------------------------
    // SpendingProfileEntity — null spendingTiers branch
    // -------------------------------------------------------------------------

    @Test
    void spendingProfileEntity_nullSpendingTiers_defaultsToEmptyJson() {
        var profile = new SpendingProfileEntity(null, "Basic", BigDecimal.TEN, BigDecimal.ONE, null);

        assertThat(profile.getSpendingTiers()).isEqualTo("[]");
    }

    @Test
    void spendingProfileEntity_nonNullSpendingTiers_usesProvidedValue() {
        var profile = new SpendingProfileEntity(null, "Advanced", BigDecimal.TEN, BigDecimal.ONE,
                "[{\"startAge\":65}]");

        assertThat(profile.getSpendingTiers()).isEqualTo("[{\"startAge\":65}]");
    }

    // -------------------------------------------------------------------------
    // ProjectionAccountEntity — null accountType branch
    // -------------------------------------------------------------------------

    @Test
    void projectionAccountEntity_nullAccountType_defaultsToTaxable() {
        var entity = new ProjectionAccountEntity(
                null, null,
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(6_000),
                new BigDecimal("0.07"),
                null);

        assertThat(entity.getAccountType()).isEqualTo("taxable");
    }

    @Test
    void projectionAccountEntity_nonNullAccountType_usesProvidedValue() {
        var entity = new ProjectionAccountEntity(
                null, null,
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(6_000),
                new BigDecimal("0.07"),
                "roth");

        assertThat(entity.getAccountType()).isEqualTo("roth");
    }

    // -------------------------------------------------------------------------
    // InviteCodeEntity.isConsumed() — both branches
    // -------------------------------------------------------------------------

    @Test
    void inviteCodeEntity_isConsumed_returnsFalse_whenConsumedByIsNull() {
        // A freshly constructed invite has no consumer yet
        var invite = new InviteCodeEntity(null, "CODE1", null, OffsetDateTime.now().plusDays(7));

        assertThat(invite.isConsumed()).isFalse();
    }

    @Test
    void inviteCodeEntity_isConsumed_returnsTrue_whenConsumedByIsSet() {
        var invite = new InviteCodeEntity(null, "CODE2", null, OffsetDateTime.now().plusDays(7));
        // Simulate consumption by setting consumedBy via the setter
        invite.setConsumedBy(new UserEntity());

        assertThat(invite.isConsumed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // PropertyEntity.hasLoanDetails() — both branches
    // -------------------------------------------------------------------------

    @Test
    void propertyEntity_hasLoanDetails_returnsTrue_whenAllFieldsPresent() {
        var property = buildMinimalProperty();
        property.setLoanAmount(new BigDecimal("300000.00"));
        property.setAnnualInterestRate(new BigDecimal("0.0650"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));

        assertThat(property.hasLoanDetails()).isTrue();
    }

    @Test
    void propertyEntity_hasLoanDetails_returnsFalse_whenLoanAmountMissing() {
        var property = buildMinimalProperty();
        // loanAmount is null, rest are set
        property.setAnnualInterestRate(new BigDecimal("0.0650"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));

        assertThat(property.hasLoanDetails()).isFalse();
    }

    @Test
    void propertyEntity_hasLoanDetails_returnsFalse_whenInterestRateMissing() {
        var property = buildMinimalProperty();
        property.setLoanAmount(new BigDecimal("300000.00"));
        // annualInterestRate is null
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));

        assertThat(property.hasLoanDetails()).isFalse();
    }

    @Test
    void propertyEntity_hasLoanDetails_returnsFalse_whenLoanTermMissing() {
        var property = buildMinimalProperty();
        property.setLoanAmount(new BigDecimal("300000.00"));
        property.setAnnualInterestRate(new BigDecimal("0.0650"));
        // loanTermMonths is null
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));

        assertThat(property.hasLoanDetails()).isFalse();
    }

    @Test
    void propertyEntity_hasLoanDetails_returnsFalse_whenLoanStartDateMissing() {
        var property = buildMinimalProperty();
        property.setLoanAmount(new BigDecimal("300000.00"));
        property.setAnnualInterestRate(new BigDecimal("0.0650"));
        property.setLoanTermMonths(360);
        // loanStartDate is null

        assertThat(property.hasLoanDetails()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PropertyEntity buildMinimalProperty() {
        return new PropertyEntity(
                null,
                "123 Main St",
                new BigDecimal("400000.00"),
                LocalDate.of(2020, 1, 1),
                new BigDecimal("450000.00"),
                new BigDecimal("280000.00"));
    }
}
