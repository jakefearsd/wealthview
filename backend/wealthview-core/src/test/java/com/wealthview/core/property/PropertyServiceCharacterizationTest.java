package com.wealthview.core.property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.persistence.entity.PropertyEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Golden-master characterization test for {@link PropertyService}.
 *
 * <p>{@code PropertyService} is fully deterministic — every output is pure arithmetic
 * over the input request and persisted entity (no randomness, no clock-sensitive math
 * in the paths exercised here; the {@code create}/{@code get} fixture uses no computed
 * mortgage balance so {@code LocalDate.now()} is not consulted). So no seed seam is
 * needed.
 *
 * <p>This test complements the broad behavior-focused {@code PropertyServiceTest} by
 * pinning two things that test does not cover directly — the {@code get} read path and
 * the static {@code parseCostSegAllocations} helper — plus one end-to-end golden-master
 * (full {@code PropertyRequest} round-tripped through {@code create}, every response
 * field asserted). Every value below was produced by {@code PropertyService} itself and
 * sanity-checked (equity = currentValue - mortgage, positive balances, allocations
 * reconciling to the depreciable basis). These assertions are the behavior contract the
 * Phase 3 decomposition of this God-class must preserve.
 */
class PropertyServiceCharacterizationTest extends PropertyServiceTestSupport {

    /**
     * A realistic investment property: cost-segregated depreciation, financial fields,
     * no computed mortgage balance so the result is calendar-independent.
     */
    private PropertyRequest goldenRequest() {
        var allocations = List.of(
                new CostSegAllocation("5yr", new BigDecimal("40000")),
                new CostSegAllocation("15yr", new BigDecimal("60000")),
                new CostSegAllocation("27_5yr", new BigDecimal("400000")));
        return new PropertyRequest(
                "742 Evergreen Terrace", new BigDecimal("550000"),
                LocalDate.of(2021, 3, 1), new BigDecimal("680000"), new BigDecimal("310000"),
                null, null, null, null, null, "investment",
                new BigDecimal("0.035"), new BigDecimal("8400"), new BigDecimal("2100"),
                new BigDecimal("3600"),
                LocalDate.of(2021, 3, 1), new BigDecimal("50000"), "cost_segregation",
                new BigDecimal("27.5"), allocations, new BigDecimal("0.80"), 2021);
    }

    @Test
    void create_fullInvestmentPropertyRequest_pinsEveryResponseField() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = propertyService.create(tenantId, goldenRequest());

        assertThat(result.address()).isEqualTo("742 Evergreen Terrace");
        assertThat(result.purchasePrice()).isEqualByComparingTo("550000");
        assertThat(result.purchaseDate()).isEqualTo(LocalDate.of(2021, 3, 1));
        assertThat(result.currentValue()).isEqualByComparingTo("680000");
        assertThat(result.mortgageBalance()).isEqualByComparingTo("310000");
        assertThat(result.equity()).isEqualByComparingTo("370000");
        assertThat(result.hasLoanDetails()).isFalse();
        assertThat(result.useComputedBalance()).isFalse();
        assertThat(result.propertyType()).isEqualTo("investment");
        assertThat(result.annualAppreciationRate()).isEqualByComparingTo("0.035");
        assertThat(result.annualPropertyTax()).isEqualByComparingTo("8400");
        assertThat(result.annualInsuranceCost()).isEqualByComparingTo("2100");
        assertThat(result.annualMaintenanceCost()).isEqualByComparingTo("3600");
        assertThat(result.inServiceDate()).isEqualTo(LocalDate.of(2021, 3, 1));
        assertThat(result.landValue()).isEqualByComparingTo("50000");
        assertThat(result.depreciationMethod()).isEqualTo("cost_segregation");
        assertThat(result.usefulLifeYears()).isEqualByComparingTo("27.5");
        assertThat(result.bonusDepreciationRate()).isEqualByComparingTo("0.80");
        assertThat(result.costSegStudyYear()).isEqualTo(2021);
        assertThat(result.costSegAllocations()).hasSize(3);
    }

    @Test
    void get_existingProperty_pinsResponse() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "100 Oak Ave", new BigDecimal("400000"),
                LocalDate.of(2019, 6, 1), new BigDecimal("520000"), new BigDecimal("180000"));
        property.setPropertyType("vacation");
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var result = propertyService.get(tenantId, propertyId);

        assertThat(result.address()).isEqualTo("100 Oak Ave");
        assertThat(result.currentValue()).isEqualByComparingTo("520000");
        assertThat(result.mortgageBalance()).isEqualByComparingTo("180000");
        assertThat(result.equity()).isEqualByComparingTo("340000");
        assertThat(result.propertyType()).isEqualTo("vacation");
        assertThat(result.hasLoanDetails()).isFalse();
    }

    @Test
    void getDepreciationSchedule_costSegFixture_pinsClassBreakdownsAndFirstYear() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "742 Evergreen Terrace", new BigDecimal("550000"),
                LocalDate.of(2021, 3, 1), new BigDecimal("680000"), new BigDecimal("310000"));
        property.setDepreciationMethod("cost_segregation");
        property.setLandValue(new BigDecimal("50000"));
        property.setInServiceDate(LocalDate.of(2021, 1, 1));
        property.setUsefulLifeYears(new BigDecimal("27.5"));
        property.setBonusDepreciationRate(new BigDecimal("0.80"));
        property.setCostSegStudyYear(2021);
        property.setCostSegAllocations("""
                [{"assetClass":"5yr","allocation":40000},\
                {"assetClass":"15yr","allocation":60000},\
                {"assetClass":"27_5yr","allocation":400000}]""");
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var result = propertyService.getDepreciationSchedule(tenantId, propertyId);

        assertThat(result.depreciationMethod()).isEqualTo("cost_segregation");
        assertThat(result.depreciableBasis()).isEqualByComparingTo("500000");
        assertThat(result.bonusDepreciationRate()).isEqualByComparingTo("0.80");
        assertThat(result.costSegAllocations()).hasSize(3);
        assertThat(result.classBreakdowns()).hasSize(3);
        // 5yr class: 40000 * 0.80 bonus = 32000.
        var fiveYr = result.classBreakdowns().get(0);
        assertThat(fiveYr.assetClass()).isEqualTo("5yr");
        assertThat(fiveYr.bonusAmount()).isEqualByComparingTo("32000.0000");
        // Allocations sum exactly to the depreciable basis (structural invariant).
        var allocSum = result.costSegAllocations().stream()
                .map(CostSegAllocation::allocation)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocSum).isEqualByComparingTo(result.depreciableBasis());
    }

    @Test
    void parseCostSegAllocations_validJson_returnsAllocations() {
        var json = """
                [{"assetClass":"5yr","allocation":15000},{"assetClass":"27_5yr","allocation":235000}]""";

        var result = PropertyService.parseCostSegAllocations(json);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).assetClass()).isEqualTo("5yr");
        assertThat(result.get(0).allocation()).isEqualByComparingTo("15000");
        assertThat(result.get(1).assetClass()).isEqualTo("27_5yr");
        assertThat(result.get(1).allocation()).isEqualByComparingTo("235000");
    }

    @Test
    void parseCostSegAllocations_nullOrEmpty_returnsEmptyList() {
        assertThat(PropertyService.parseCostSegAllocations(null)).isEmpty();
        assertThat(PropertyService.parseCostSegAllocations("")).isEmpty();
        assertThat(PropertyService.parseCostSegAllocations("[]")).isEmpty();
    }
}
