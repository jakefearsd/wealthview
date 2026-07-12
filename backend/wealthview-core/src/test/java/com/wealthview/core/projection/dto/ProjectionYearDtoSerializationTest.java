package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-contract guard for {@link ProjectionYearDto}.
 *
 * <p>The DTO is serialized to JSON in two shapes: camelCase (the plain-mapper form the
 * projection golden files are captured in) and snake_case (the real API form driven by
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE} and consumed by the frontend
 * {@code ProjectionYear} type, the CSV export, and every chart).
 *
 * <p>This test pins the shape as a <em>flat</em> object with exactly its documented set of
 * top-level keys and their values. It exists so the field grouping (nested records unwrapped
 * back to the top level) cannot silently change the wire format: any dropped, renamed, or
 * nested key fails here immediately, independently of the realistic-run golden files.
 */
class ProjectionYearDtoSerializationTest {

    private static final ObjectMapper CAMEL = new ObjectMapper();
    private static final ObjectMapper SNAKE = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private static final Set<String> CAMEL_KEYS = Set.of(
            "year", "age", "startBalance", "contributions", "growth", "withdrawals", "endBalance",
            "retired", "traditionalBalance", "rothBalance", "taxableBalance", "rothConversionAmount",
            "taxLiability", "essentialExpenses", "discretionaryExpenses", "incomeStreamsTotal",
            "netSpendingNeed", "spendingSurplus", "discretionaryAfterCuts", "rentalIncomeGross",
            "rentalExpensesTotal", "depreciationTotal", "rentalLossApplied", "suspendedLossCarryforward",
            "socialSecurityTaxable", "selfEmploymentTax", "incomeBySource", "propertyEquity",
            "totalNetWorth", "surplusReinvested", "taxableGrowth", "traditionalGrowth", "rothGrowth",
            "taxPaidFromTaxable", "taxPaidFromTraditional", "taxPaidFromRoth", "withdrawalFromTaxable",
            "withdrawalFromTraditional", "withdrawalFromRoth", "rentalPropertyDetails", "federalTax",
            "stateTax", "saltDeduction", "usedItemizedDeduction", "irmaaWarning", "rmdAmount",
            "capitalGainsTax", "irmaaSurcharge", "earlyWithdrawalPenalty");

    private static final Set<String> SNAKE_KEYS = Set.of(
            "year", "age", "start_balance", "contributions", "growth", "withdrawals", "end_balance",
            "retired", "traditional_balance", "roth_balance", "taxable_balance", "roth_conversion_amount",
            "tax_liability", "essential_expenses", "discretionary_expenses", "income_streams_total",
            "net_spending_need", "spending_surplus", "discretionary_after_cuts", "rental_income_gross",
            "rental_expenses_total", "depreciation_total", "rental_loss_applied",
            "suspended_loss_carryforward", "social_security_taxable", "self_employment_tax",
            "income_by_source", "property_equity", "total_net_worth", "surplus_reinvested",
            "taxable_growth", "traditional_growth", "roth_growth", "tax_paid_from_taxable",
            "tax_paid_from_traditional", "tax_paid_from_roth", "withdrawal_from_taxable",
            "withdrawal_from_traditional", "withdrawal_from_roth", "rental_property_details",
            "federal_tax", "state_tax", "salt_deduction", "used_itemized_deduction", "irmaa_warning",
            "rmd_amount", "capital_gains_tax", "irmaa_surcharge", "early_withdrawal_penalty");

    /** A year with every field populated to a distinct value, so a swapped field is detectable. */
    private ProjectionYearDto fullyPopulated() {
        var detail = new RentalPropertyYearDetail(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "123 Oak", "rental_passive",
                new BigDecimal("20000"), new BigDecimal("12000"), new BigDecimal("3000"),
                new BigDecimal("2000"), new BigDecimal("5000"), new BigDecimal("-2000"),
                new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("8000"));
        return ProjectionYearDto.builder()
                .year(2040).age(70)
                .startBalance(new BigDecimal("500000")).contributions(new BigDecimal("1000"))
                .growth(new BigDecimal("30000")).withdrawals(new BigDecimal("20000"))
                .endBalance(new BigDecimal("510000")).retired(true)
                .traditionalBalance(new BigDecimal("200000")).rothBalance(new BigDecimal("150000"))
                .taxableBalance(new BigDecimal("160000")).rothConversionAmount(new BigDecimal("10000"))
                .taxLiability(new BigDecimal("2500")).essentialExpenses(new BigDecimal("25000"))
                .discretionaryExpenses(new BigDecimal("10000")).incomeStreamsTotal(new BigDecimal("15000"))
                .netSpendingNeed(new BigDecimal("20000")).spendingSurplus(new BigDecimal("5000"))
                .discretionaryAfterCuts(new BigDecimal("8000")).rentalIncomeGross(new BigDecimal("20000"))
                .rentalExpensesTotal(new BigDecimal("12000")).depreciationTotal(new BigDecimal("5000"))
                .rentalLossApplied(new BigDecimal("1000")).suspendedLossCarryforward(new BigDecimal("2000"))
                .socialSecurityTaxable(new BigDecimal("3000")).selfEmploymentTax(new BigDecimal("500"))
                .incomeBySource(Map.of("rental", new BigDecimal("15000")))
                .propertyEquity(new BigDecimal("350000")).totalNetWorth(new BigDecimal("860000"))
                .surplusReinvested(new BigDecimal("5000")).taxableGrowth(new BigDecimal("11000"))
                .traditionalGrowth(new BigDecimal("14000")).rothGrowth(new BigDecimal("5000"))
                .taxPaidFromTaxable(new BigDecimal("1000")).taxPaidFromTraditional(new BigDecimal("800"))
                .taxPaidFromRoth(new BigDecimal("300")).withdrawalFromTaxable(new BigDecimal("5000"))
                .withdrawalFromTraditional(new BigDecimal("10000")).withdrawalFromRoth(new BigDecimal("5000"))
                .rentalPropertyDetails(List.of(detail)).federalTax(new BigDecimal("1500"))
                .stateTax(new BigDecimal("700")).saltDeduction(new BigDecimal("10000"))
                .usedItemizedDeduction(true).irmaaWarning(true)
                .rmdAmount(new BigDecimal("6000")).capitalGainsTax(new BigDecimal("900"))
                .irmaaSurcharge(new BigDecimal("2643.60"))
                .earlyWithdrawalPenalty(new BigDecimal("450"))
                .build();
    }

    @Test
    void serialize_camelCase_isFlatWithExactlyTheDocumentedKeys() throws Exception {
        JsonNode tree = CAMEL.readTree(CAMEL.writeValueAsString(fullyPopulated()));

        assertThat(tree.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(CAMEL_KEYS);
    }

    @Test
    void serialize_snakeCase_isFlatWithExactlyTheDocumentedKeys() throws Exception {
        JsonNode tree = SNAKE.readTree(SNAKE.writeValueAsString(fullyPopulated()));

        assertThat(tree.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(SNAKE_KEYS);
    }

    @Test
    void serialize_snakeCase_carriesEveryGroupValueAtTopLevel() throws Exception {
        JsonNode t = SNAKE.readTree(SNAKE.writeValueAsString(fullyPopulated()));

        // one representative field per prospective group, to catch a whole group being
        // dropped or mis-wired by unwrapping
        assertThat(t.get("start_balance").decimalValue()).isEqualByComparingTo("500000");   // flow
        assertThat(t.get("traditional_balance").decimalValue()).isEqualByComparingTo("200000"); // pools
        assertThat(t.get("traditional_growth").decimalValue()).isEqualByComparingTo("14000");   // pool growth
        assertThat(t.get("tax_paid_from_roth").decimalValue()).isEqualByComparingTo("300");     // pool tax paid
        assertThat(t.get("withdrawal_from_roth").decimalValue()).isEqualByComparingTo("5000");  // pool withdrawals
        assertThat(t.get("net_spending_need").decimalValue()).isEqualByComparingTo("20000");    // viability
        assertThat(t.get("self_employment_tax").decimalValue()).isEqualByComparingTo("500");    // income detail
        assertThat(t.get("federal_tax").decimalValue()).isEqualByComparingTo("1500");           // tax breakdown
        assertThat(t.get("total_net_worth").decimalValue()).isEqualByComparingTo("860000");     // net worth
        assertThat(t.get("used_itemized_deduction").booleanValue()).isTrue();
        assertThat(t.get("irmaa_warning").booleanValue()).isTrue();
        assertThat(t.get("rmd_amount").decimalValue()).isEqualByComparingTo("6000");            // tax breakdown
        assertThat(t.get("capital_gains_tax").decimalValue()).isEqualByComparingTo("900");       // tax breakdown
        assertThat(t.get("irmaa_surcharge").decimalValue()).isEqualByComparingTo("2643.60");     // tax breakdown
        assertThat(t.get("early_withdrawal_penalty").decimalValue()).isEqualByComparingTo("450"); // tax breakdown
    }

    @Test
    void serialize_snakeCase_keepsMapAndListAsNestedContainers() throws Exception {
        JsonNode t = SNAKE.readTree(SNAKE.writeValueAsString(fullyPopulated()));

        assertThat(t.get("income_by_source").isObject()).isTrue();
        assertThat(t.get("income_by_source").get("rental").decimalValue()).isEqualByComparingTo("15000");
        assertThat(t.get("rental_property_details").isArray()).isTrue();
        assertThat(t.get("rental_property_details").get(0).get("property_name").asText()).isEqualTo("123 Oak");
    }

    @Test
    void serialize_nullGroupFields_stillEmitEveryKeyAsNull() throws Exception {
        // A minimal (pre-retirement-style) year: only core flow set, everything else null.
        // Unwrapped groups must still contribute their keys (as null), never omit them.
        var minimal = ProjectionYearDto.simple(
                2025, 35, new BigDecimal("500000"), new BigDecimal("20000"),
                new BigDecimal("36400"), BigDecimal.ZERO, new BigDecimal("556400"), false);

        JsonNode t = SNAKE.readTree(SNAKE.writeValueAsString(minimal));

        assertThat(t.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(SNAKE_KEYS);
        assertThat(t.get("traditional_balance").isNull()).isTrue();
        assertThat(t.get("federal_tax").isNull()).isTrue();
        assertThat(t.get("income_by_source").isNull()).isTrue();
    }
}
