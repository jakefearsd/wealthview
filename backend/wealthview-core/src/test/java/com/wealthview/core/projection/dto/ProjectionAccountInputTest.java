package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProjectionAccountInputTest {

    @Test
    void hypothetical_carriesAllocationAndOptionalOverride() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.of(new BigDecimal("0.07")), "taxable");

        assertThat(acct.allocation()).isEqualTo(alloc);
        assertThat(acct.expectedReturnOverride()).contains(new BigDecimal("0.07"));
    }

    @Test
    void hypothetical_legacyShapeConstructor_defaultsCostBasisToInitialBalance() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.of(new BigDecimal("0.07")), "taxable");

        assertThat(acct.costBasis()).isEqualByComparingTo("1000");
    }

    @Test
    void hypothetical_canonicalConstructor_carriesExplicitCostBasis() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.of(new BigDecimal("0.07")), new BigDecimal("400"), "taxable");

        assertThat(acct.costBasis()).isEqualByComparingTo("400");
    }

    @Test
    void linked_defaultsToEmptyOverride() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.BOND, 1.0));
        ProjectionAccountInput acct = new LinkedAccountInput(
                UUID.randomUUID(), new BigDecimal("1000"), new BigDecimal("0"),
                alloc, Optional.empty(), "traditional");

        assertThat(acct.expectedReturnOverride()).isEmpty();
    }

    @Test
    void linked_legacyShapeConstructor_defaultsCostBasisToInitialBalance() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.BOND, 1.0));
        ProjectionAccountInput acct = new LinkedAccountInput(
                UUID.randomUUID(), new BigDecimal("1000"), new BigDecimal("0"),
                alloc, Optional.empty(), "traditional");

        assertThat(acct.costBasis()).isEqualByComparingTo("1000");
    }

    @Test
    void linked_canonicalConstructor_carriesExplicitCostBasis() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.BOND, 1.0));
        ProjectionAccountInput acct = new LinkedAccountInput(
                UUID.randomUUID(), new BigDecimal("1000"), new BigDecimal("0"),
                alloc, Optional.empty(), new BigDecimal("650"), "traditional");

        assertThat(acct.costBasis()).isEqualByComparingTo("650");
    }

    @Test
    void hypothetical_legacyExpectedReturnConstructor_mapsToAllUsAllocationAndOverride() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.06"), "taxable");

        assertThat(acct.allocation()).isEqualTo(AssetAllocation.ALL_US);
        assertThat(acct.expectedReturnOverride()).contains(new BigDecimal("0.06"));
    }

    @Test
    void hypothetical_legacyExpectedReturnConstructor_defaultsCostBasisToInitialBalance() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.06"), "taxable");

        assertThat(acct.costBasis()).isEqualByComparingTo("1000");
    }

    @Test
    void poolType_taxableAccountType_returnsTaxable() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.empty(), "taxable");

        assertThat(acct.poolType()).isEqualTo(PoolType.TAXABLE);
    }

    @Test
    void poolType_traditionalAccountType_returnsTraditional() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.BOND, 1.0));
        ProjectionAccountInput acct = new LinkedAccountInput(
                UUID.randomUUID(), new BigDecimal("1000"), new BigDecimal("0"),
                alloc, Optional.empty(), "traditional");

        assertThat(acct.poolType()).isEqualTo(PoolType.TRADITIONAL);
    }

    @Test
    void poolType_unrecognizedAccountType_throws() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.empty(), new BigDecimal("1000"), "bogus", "primary");

        assertThatIllegalArgumentException().isThrownBy(acct::poolType);
    }

    @Test
    void ownerType_defaultPrimary_returnsPrimary() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.empty(), "taxable");

        assertThat(acct.ownerType()).isEqualTo(LotOwner.PRIMARY);
    }

    @Test
    void ownerType_spouseOwner_returnsSpouse() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.empty(), new BigDecimal("1000"), "taxable", "spouse");

        assertThat(acct.ownerType()).isEqualTo(LotOwner.SPOUSE);
    }

    @Test
    void ownerType_unrecognizedOwner_throws() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.empty(), new BigDecimal("1000"), "taxable", "bogus");

        assertThatIllegalArgumentException().isThrownBy(acct::ownerType);
    }
}
