package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.core.projection.tax.TaxSpaceCalculator;
import com.wealthview.projection.testutil.GoldenScenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineTaxPictureTest {

    private static DeterministicProjectionEngine engine(TaxSpaceCalculator taxSpaceCalculator) {
        var calcs = GoldenScenarios.calculators();
        return new DeterministicProjectionEngine(calcs.federal(), null, calcs.capitalGains(), calcs.irmaa(),
                null, null, taxSpaceCalculator);
    }

    private static TaxSpaceYear stubYear(int year) {
        return new TaxSpaceYear(year, 70, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, BigDecimal.ZERO,
                null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void runDetailed_ssRmdRetiree_emitsOnePicturePerRetiredYearInOrder() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");

        var detail = engine(null).runDetailed(input);

        var retiredYears = detail.result().yearlyData().stream()
                .filter(y -> y.retired()).map(y -> y.year()).toList();
        assertThat(detail.taxPictures()).extracting(YearTaxPicture::year).containsExactlyElementsOf(retiredYears);
    }

    @Test
    void runDetailed_ssRmdRetiree_pictureCarriesGrossBenefitAndYearsFromBase() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");

        var detail = engine(null).runDetailed(input);

        assertThat(detail.taxPictures()).allSatisfy(p -> {
            assertThat(p.socialSecurityBenefit()).isGreaterThanOrEqualTo(p.socialSecurityTaxable());
            assertThat(p.yearsFromBase()).isGreaterThanOrEqualTo(0);
        });
        assertThat(detail.taxPictures()).anySatisfy(p ->
                assertThat(p.socialSecurityBenefit()).isPositive());
    }

    @Test
    void runDetailed_noTaxSpaceCalculator_taxSpaceEmpty() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");

        var detail = engine(null).runDetailed(input);

        assertThat(detail.taxSpace()).isEmpty();
    }

    @Test
    void runDetailed_withTaxSpaceCalculator_computesOneTaxSpaceYearPerPicture() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");
        var calculator = mock(TaxSpaceCalculator.class);
        when(calculator.compute(any(YearTaxPicture.class), any(TaxCalculationStrategy.class)))
                .thenAnswer(inv -> stubYear(inv.<YearTaxPicture>getArgument(0).year()));

        var detail = engine(calculator).runDetailed(input);

        assertThat(detail.taxSpace()).extracting(TaxSpaceYear::year)
                .containsExactlyElementsOf(detail.taxPictures().stream().map(YearTaxPicture::year).toList());
        verify(calculator, times(detail.taxPictures().size())).compute(any(), any());
    }

    @Test
    void run_matchesRunDetailedResult() throws Exception {
        var input = GoldenScenarios.loadInput("household-survivor");
        var eng = engine(null);

        var viaRun = eng.run(input);
        var viaDetailed = eng.runDetailed(input).result();

        assertThat(GoldenScenarios.MAPPER.writeValueAsString(viaRun))
                .isEqualTo(GoldenScenarios.MAPPER.writeValueAsString(viaDetailed));
    }
}
