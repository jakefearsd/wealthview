package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.household.HouseholdContext;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.retiredAt66BirthYear;
import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProjectionEngineTerminalValueTest extends DeterministicProjectionEngineTestSupport {

    private static ProjectionInput threePoolRetiree(String extraParams) {
        String params = """
                {"birth_year": %d, "withdrawal_rate": 0.04, "fee_rate": 0%s}
                """.formatted(retiredAt66BirthYear(), extraParams);
        List<ProjectionAccountInput> accounts = List.of(
                acct("400000", "0", "0.04", "traditional"),
                acct("100000", "0", "0.04", "roth"),
                acct("100000", "0", "0.04", "taxable"));
        return createRetiredInput(params, accounts);
    }

    @Test
    void runDetailed_explicitHeirRate_valuesLastRowPoolsForHeirs() {
        var detail = engine.runDetailed(threePoolRetiree(", \"heir_tax_rate\": 0.30"));

        var last = detail.result().yearlyData().getLast();
        var tv = detail.terminalValue();
        assertThat(tv).isNotNull();
        assertThat(tv.year()).isEqualTo(last.year());
        assertThat(tv.traditional()).isEqualByComparingTo(last.traditionalBalance());
        assertThat(tv.roth()).isEqualByComparingTo(last.rothBalance());
        assertThat(tv.taxable()).isEqualByComparingTo(last.taxableBalance());
        assertThat(tv.heirTaxRate()).isEqualByComparingTo("0.30");
        BigDecimal expected = last.traditionalBalance().multiply(bd("0.70"))
                .add(last.rothBalance()).add(last.taxableBalance())
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(tv.afterTaxLegacy()).isEqualByComparingTo(expected);
        assertThat(tv.atSecondDeath()).isFalse();
    }

    @Test
    void runDetailed_heirRateAbsent_usesDefaultPoint24() {
        var detail = engine.runDetailed(threePoolRetiree(""));

        assertThat(detail.terminalValue().heirTaxRate()).isEqualByComparingTo("0.24");
    }

    @Test
    void runDetailed_noTaxSpaceCalculator_taxSpaceEmpty() {
        var detail = engine.runDetailed(threePoolRetiree(""));

        assertThat(detail.taxSpace()).isNotNull().isEmpty();
    }

    @Test
    void runDetailed_noProjectedYears_terminalValueIsNullAndResultIsEmpty() {
        // birth 1930 + end age 90 = end year 2020, before the 2030 reference year: zero loop iterations.
        var input = createInput(LocalDate.of(2000, 1, 1), 90, BigDecimal.ZERO,
                "{\"birth_year\": 1930, \"withdrawal_rate\": 0.04}",
                List.of(acct("100000", "0", "0.04", "taxable")), null, 2030, List.of());

        var detail = engine.runDetailed(input);

        assertThat(detail.result().yearlyData()).isEmpty();
        assertThat(detail.terminalValue()).isNull();
    }

    @Test
    void runDetailed_secondDeathWithinHorizon_flagsAtSecondDeathInThatYear() {
        // Primary (1958) dies 2040 at 82; spouse (1966) dies 2050 at 84; horizon end 2053.
        var household = HouseholdContext.of(1958, 82, 1966, 84, 1958 + 95);
        ProjectionAccountInput traditional = new HypotheticalAccountInput(bd("1500000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.empty(), bd("0"), "traditional", "primary");
        var input = new ProjectionInput(UUID.randomUUID(), "Second death", LocalDate.of(2020, 1, 1), 95,
                BigDecimal.ZERO,
                """
                {"birth_year": 1958, "filing_status": "married_filing_jointly", "withdrawal_rate": 0.04,
                 "withdrawal_order": "taxable_first", "fee_rate": 0}
                """,
                List.of(traditional), new SpendingProfileInput(bd("50000"), bd("10000"), null),
                2035, List.of(), null, List.of(), household);

        var detail = engine.runDetailed(input);

        var tv = detail.terminalValue();
        assertThat(tv.year()).isEqualTo(2050);
        assertThat(tv.atSecondDeath()).isTrue();
    }
}
