package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ProjectionYearDto, its Builder, and the family of with*() copy-helpers.
 * Each helper is intentionally exercised end-to-end: the builder round-trip proves
 * that every setter writes to its own field, and each with*() verifies that non-listed
 * fields are preserved from the source DTO.
 */
class ProjectionYearDtoTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private ProjectionYearDto minimalDto() {
        return ProjectionYearDto.simple(
                2035, 65,
                new BigDecimal("1000000"), ZERO, new BigDecimal("40000"),
                new BigDecimal("45000"), new BigDecimal("995000"),
                true
        );
    }

    @Test
    void simple_populatesPrimaryFieldsAndDefaultsOthersToNull() {
        var dto = minimalDto();

        assertThat(dto.year()).isEqualTo(2035);
        assertThat(dto.age()).isEqualTo(65);
        assertThat(dto.startBalance()).isEqualByComparingTo("1000000");
        assertThat(dto.growth()).isEqualByComparingTo("40000");
        assertThat(dto.withdrawals()).isEqualByComparingTo("45000");
        assertThat(dto.endBalance()).isEqualByComparingTo("995000");
        assertThat(dto.retired()).isTrue();
        // fields not in simple() default to null / zero / default
        assertThat(dto.traditionalBalance()).isNull();
        assertThat(dto.incomeBySource()).isNull();
    }

    @Test
    void builder_withAllSettersChained_populatesEveryField() {
        var detail = new RentalPropertyYearDetail(
                UUID.randomUUID(), "123 Oak", "rental_passive",
                new BigDecimal("20000"), new BigDecimal("12000"), new BigDecimal("3000"),
                new BigDecimal("2000"), new BigDecimal("5000"), new BigDecimal("-2000"),
                new BigDecimal("1000"), new BigDecimal("1000"), ZERO, new BigDecimal("8000"));

        var dto = ProjectionYearDto.builder()
                .year(2040).age(70)
                .startBalance(new BigDecimal("500000"))
                .contributions(new BigDecimal("0"))
                .growth(new BigDecimal("30000"))
                .withdrawals(new BigDecimal("20000"))
                .endBalance(new BigDecimal("510000"))
                .retired(true)
                .traditionalBalance(new BigDecimal("200000"))
                .rothBalance(new BigDecimal("150000"))
                .taxableBalance(new BigDecimal("160000"))
                .rothConversionAmount(new BigDecimal("10000"))
                .taxLiability(new BigDecimal("2500"))
                .essentialExpenses(new BigDecimal("25000"))
                .discretionaryExpenses(new BigDecimal("10000"))
                .incomeStreamsTotal(new BigDecimal("15000"))
                .netSpendingNeed(new BigDecimal("20000"))
                .spendingSurplus(new BigDecimal("5000"))
                .discretionaryAfterCuts(new BigDecimal("8000"))
                .rentalIncomeGross(new BigDecimal("20000"))
                .rentalExpensesTotal(new BigDecimal("12000"))
                .depreciationTotal(new BigDecimal("5000"))
                .rentalLossApplied(new BigDecimal("1000"))
                .suspendedLossCarryforward(new BigDecimal("2000"))
                .socialSecurityTaxable(new BigDecimal("3000"))
                .selfEmploymentTax(new BigDecimal("500"))
                .incomeBySource(Map.of("rental", new BigDecimal("15000")))
                .propertyEquity(new BigDecimal("350000"))
                .totalNetWorth(new BigDecimal("860000"))
                .surplusReinvested(new BigDecimal("5000"))
                .taxableGrowth(new BigDecimal("11000"))
                .traditionalGrowth(new BigDecimal("14000"))
                .rothGrowth(new BigDecimal("5000"))
                .taxPaidFromTaxable(new BigDecimal("1000"))
                .taxPaidFromTraditional(new BigDecimal("800"))
                .taxPaidFromRoth(ZERO)
                .withdrawalFromTaxable(new BigDecimal("5000"))
                .withdrawalFromTraditional(new BigDecimal("10000"))
                .withdrawalFromRoth(new BigDecimal("5000"))
                .rentalPropertyDetails(List.of(detail))
                .federalTax(new BigDecimal("1500"))
                .stateTax(new BigDecimal("700"))
                .saltDeduction(new BigDecimal("10000"))
                .usedItemizedDeduction(true)
                .irmaaWarning(false)
                .build();

        assertThat(dto.year()).isEqualTo(2040);
        assertThat(dto.age()).isEqualTo(70);
        assertThat(dto.traditionalBalance()).isEqualByComparingTo("200000");
        assertThat(dto.rothBalance()).isEqualByComparingTo("150000");
        assertThat(dto.taxableBalance()).isEqualByComparingTo("160000");
        assertThat(dto.rothConversionAmount()).isEqualByComparingTo("10000");
        assertThat(dto.incomeBySource()).containsEntry("rental", new BigDecimal("15000"));
        assertThat(dto.rentalPropertyDetails()).containsExactly(detail);
        assertThat(dto.propertyEquity()).isEqualByComparingTo("350000");
        assertThat(dto.totalNetWorth()).isEqualByComparingTo("860000");
        assertThat(dto.taxableGrowth()).isEqualByComparingTo("11000");
        assertThat(dto.traditionalGrowth()).isEqualByComparingTo("14000");
        assertThat(dto.rothGrowth()).isEqualByComparingTo("5000");
        assertThat(dto.taxPaidFromTaxable()).isEqualByComparingTo("1000");
        assertThat(dto.taxPaidFromTraditional()).isEqualByComparingTo("800");
        assertThat(dto.taxPaidFromRoth()).isEqualByComparingTo("0");
        assertThat(dto.withdrawalFromTaxable()).isEqualByComparingTo("5000");
        assertThat(dto.withdrawalFromTraditional()).isEqualByComparingTo("10000");
        assertThat(dto.withdrawalFromRoth()).isEqualByComparingTo("5000");
        assertThat(dto.federalTax()).isEqualByComparingTo("1500");
        assertThat(dto.stateTax()).isEqualByComparingTo("700");
        assertThat(dto.saltDeduction()).isEqualByComparingTo("10000");
        assertThat(dto.usedItemizedDeduction()).isTrue();
        assertThat(dto.irmaaWarning()).isFalse();
        assertThat(dto.socialSecurityTaxable()).isEqualByComparingTo("3000");
        assertThat(dto.selfEmploymentTax()).isEqualByComparingTo("500");
        assertThat(dto.rentalIncomeGross()).isEqualByComparingTo("20000");
        assertThat(dto.rentalExpensesTotal()).isEqualByComparingTo("12000");
        assertThat(dto.depreciationTotal()).isEqualByComparingTo("5000");
        assertThat(dto.rentalLossApplied()).isEqualByComparingTo("1000");
        assertThat(dto.suspendedLossCarryforward()).isEqualByComparingTo("2000");
        assertThat(dto.surplusReinvested()).isEqualByComparingTo("5000");
        assertThat(dto.discretionaryAfterCuts()).isEqualByComparingTo("8000");
    }

    @Test
    void builderFrom_copiesEveryFieldFromSource() {
        var original = ProjectionYearDto.builder()
                .year(2030).age(60)
                .startBalance(new BigDecimal("700000"))
                .endBalance(new BigDecimal("720000"))
                .retired(false)
                .traditionalBalance(new BigDecimal("100000"))
                .rothBalance(new BigDecimal("50000"))
                .taxableBalance(new BigDecimal("570000"))
                .federalTax(new BigDecimal("8000"))
                .build();

        // Start from the original, mutate one field, and assert all others are preserved.
        var copy = ProjectionYearDto.Builder.from(original).year(2031).build();

        assertThat(copy.year()).isEqualTo(2031);
        assertThat(copy.age()).isEqualTo(original.age());
        assertThat(copy.startBalance()).isEqualByComparingTo(original.startBalance());
        assertThat(copy.endBalance()).isEqualByComparingTo(original.endBalance());
        assertThat(copy.traditionalBalance()).isEqualByComparingTo(original.traditionalBalance());
        assertThat(copy.rothBalance()).isEqualByComparingTo(original.rothBalance());
        assertThat(copy.taxableBalance()).isEqualByComparingTo(original.taxableBalance());
        assertThat(copy.federalTax()).isEqualByComparingTo(original.federalTax());
        assertThat(copy.retired()).isEqualTo(original.retired());
    }

    @Test
    void withViability_populatesViabilityFieldsAndPreservesCore() {
        var base = minimalDto();

        var updated = base.withViability(
                new BigDecimal("30000"), new BigDecimal("10000"),
                new BigDecimal("12000"), new BigDecimal("28000"),
                new BigDecimal("2000"), new BigDecimal("9500"));

        assertThat(updated.essentialExpenses()).isEqualByComparingTo("30000");
        assertThat(updated.discretionaryExpenses()).isEqualByComparingTo("10000");
        assertThat(updated.incomeStreamsTotal()).isEqualByComparingTo("12000");
        assertThat(updated.netSpendingNeed()).isEqualByComparingTo("28000");
        assertThat(updated.spendingSurplus()).isEqualByComparingTo("2000");
        assertThat(updated.discretionaryAfterCuts()).isEqualByComparingTo("9500");
        // core fields unchanged
        assertThat(updated.year()).isEqualTo(base.year());
        assertThat(updated.startBalance()).isEqualByComparingTo(base.startBalance());
    }

    @Test
    void withIncomeSourceFields_populatesIncomeFieldsAndPreservesCore() {
        var base = minimalDto();

        var updated = base.withIncomeSourceFields(
                new BigDecimal("10000"), new BigDecimal("20000"), new BigDecimal("8000"),
                new BigDecimal("3000"), new BigDecimal("500"), new BigDecimal("0"),
                new BigDecimal("6000"), new BigDecimal("1200"),
                Map.of("ss", new BigDecimal("10000")),
                List.of());

        assertThat(updated.incomeStreamsTotal()).isEqualByComparingTo("10000");
        assertThat(updated.rentalIncomeGross()).isEqualByComparingTo("20000");
        assertThat(updated.rentalExpensesTotal()).isEqualByComparingTo("8000");
        assertThat(updated.depreciationTotal()).isEqualByComparingTo("3000");
        assertThat(updated.rentalLossApplied()).isEqualByComparingTo("500");
        assertThat(updated.suspendedLossCarryforward()).isEqualByComparingTo("0");
        assertThat(updated.socialSecurityTaxable()).isEqualByComparingTo("6000");
        assertThat(updated.selfEmploymentTax()).isEqualByComparingTo("1200");
        assertThat(updated.incomeBySource()).containsEntry("ss", new BigDecimal("10000"));
        assertThat(updated.rentalPropertyDetails()).isEmpty();
        assertThat(updated.endBalance()).isEqualByComparingTo(base.endBalance());
    }

    @Test
    void withSurplusReinvested_withNonNullValue_populatesField() {
        var base = minimalDto();

        var updated = base.withSurplusReinvested(new BigDecimal("1500"));

        assertThat(updated.surplusReinvested()).isEqualByComparingTo("1500");
    }

    @Test
    void withSurplusReinvested_withNullValue_returnsSameInstance() {
        var base = minimalDto();

        var updated = base.withSurplusReinvested(null);

        // Guard clause returns `this` unchanged — important so callers can
        // conditionally annotate without recreating the DTO each loop.
        assertThat(updated).isSameAs(base);
    }

    @Test
    void withPropertyEquity_populatesBothEquityAndNetWorth() {
        var base = minimalDto();

        var updated = base.withPropertyEquity(new BigDecimal("300000"), new BigDecimal("1295000"));

        assertThat(updated.propertyEquity()).isEqualByComparingTo("300000");
        assertThat(updated.totalNetWorth()).isEqualByComparingTo("1295000");
    }

    @Test
    void withTaxBreakdown_populatesTaxFields() {
        var base = minimalDto();

        var updated = base.withTaxBreakdown(
                new BigDecimal("5000"), new BigDecimal("1500"),
                new BigDecimal("10000"), true);

        assertThat(updated.federalTax()).isEqualByComparingTo("5000");
        assertThat(updated.stateTax()).isEqualByComparingTo("1500");
        assertThat(updated.saltDeduction()).isEqualByComparingTo("10000");
        assertThat(updated.usedItemizedDeduction()).isTrue();
    }

    @Test
    void withIrmaaWarning_setsTheFlag() {
        var base = minimalDto();

        var updated = base.withIrmaaWarning(true);

        assertThat(updated.irmaaWarning()).isTrue();
    }
}
