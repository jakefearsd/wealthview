package com.wealthview.core.projection.tax;

import com.wealthview.persistence.entity.StateStandardDeductionEntity;
import com.wealthview.persistence.entity.StateTaxBracketEntity;
import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BracketBasedStateTaxCalculatorTest {

    @Mock
    private StateTaxBracketRepository bracketRepo;

    @Mock
    private StateStandardDeductionRepository deductionRepo;

    @Mock
    private StateTaxSurchargeRepository surchargeRepo;

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    @Nested
    class Arizona {

        private BracketBasedStateTaxCalculator calculator;

        @BeforeEach
        void setUp() {
            calculator = new BracketBasedStateTaxCalculator(
                    "AZ", true, bracketRepo, deductionRepo, surchargeRepo);

            // AZ 2024: flat 2.5%, federal-conforming standard deduction
            lenient().when(bracketRepo.findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
                    eq("AZ"), anyInt(), eq("single")))
                    .thenReturn(List.of(
                            new StateTaxBracketEntity("AZ", 2024, "single", bd("0"), null, bd("0.0250"))));
            lenient().when(deductionRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("AZ"), anyInt(), eq("single")))
                    .thenReturn(Optional.of(new StateStandardDeductionEntity("AZ", 2024, "single", bd("14600"))));
            lenient().when(surchargeRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("AZ"), anyInt(), eq("single")))
                    .thenReturn(List.of());
        }

        @Test
        void stateCode_returnsAZ() {
            assertThat(calculator.stateCode()).isEqualTo("AZ");
        }

        @Test
        void taxesCapitalGainsAsOrdinaryIncome_returnsTrue() {
            assertThat(calculator.taxesCapitalGainsAsOrdinaryIncome()).isTrue();
        }

        @Test
        void computeTax_flatRate_computesCorrectly() {
            // Gross: 75000, deduction: 14600, taxable: 60400
            // Flat 2.5%: 60400 * 0.025 = 1510.00
            BigDecimal tax = calculator.computeTax(bd("75000"), 2024, FilingStatus.SINGLE);

            assertThat(tax).isEqualByComparingTo(bd("1510.0000"));
        }

        @Test
        void computeTax_incomeBelowDeduction_returnsZero() {
            BigDecimal tax = calculator.computeTax(bd("10000"), 2024, FilingStatus.SINGLE);

            assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void computeTax_highIncome_flatRate() {
            // Gross: 500000, deduction: 14600, taxable: 485400
            // 485400 * 0.025 = 12135.00
            BigDecimal tax = calculator.computeTax(bd("500000"), 2024, FilingStatus.SINGLE);

            assertThat(tax).isEqualByComparingTo(bd("12135.0000"));
        }

        @Test
        void getStandardDeduction_returnsFederalConformingAmount() {
            BigDecimal deduction = calculator.getStandardDeduction(2024, FilingStatus.SINGLE);

            assertThat(deduction).isEqualByComparingTo(bd("14600"));
        }
    }

    @Nested
    class Oregon {

        private BracketBasedStateTaxCalculator calculator;

        @BeforeEach
        void setUp() {
            calculator = new BracketBasedStateTaxCalculator(
                    "OR", true, bracketRepo, deductionRepo, surchargeRepo);

            // OR 2024 single brackets
            lenient().when(bracketRepo.findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
                    eq("OR"), anyInt(), eq("single")))
                    .thenReturn(List.of(
                            new StateTaxBracketEntity("OR", 2024, "single", bd("0"), bd("4050"), bd("0.0475")),
                            new StateTaxBracketEntity("OR", 2024, "single", bd("4050"), bd("10200"), bd("0.0675")),
                            new StateTaxBracketEntity("OR", 2024, "single", bd("10200"), bd("125000"), bd("0.0875")),
                            new StateTaxBracketEntity("OR", 2024, "single", bd("125000"), null, bd("0.0990"))));
            lenient().when(deductionRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("OR"), anyInt(), eq("single")))
                    .thenReturn(Optional.of(new StateStandardDeductionEntity("OR", 2024, "single", bd("2745"))));
            lenient().when(surchargeRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("OR"), anyInt(), eq("single")))
                    .thenReturn(List.of());
        }

        @Test
        void stateCode_returnsOR() {
            assertThat(calculator.stateCode()).isEqualTo("OR");
        }

        @Test
        void computeTax_moderateIncome_progressiveBrackets() {
            // Gross: 80000, deduction: 2745, taxable: 77255
            // 4.75%: 4050 * 0.0475 = 192.375
            // 6.75%: (10200-4050) * 0.0675 = 6150 * 0.0675 = 415.125
            // 8.75%: (77255-10200) * 0.0875 = 67055 * 0.0875 = 5867.3125
            // Total: 192.375 + 415.125 + 5867.3125 = 6474.8125
            BigDecimal tax = calculator.computeTax(bd("80000"), 2024, FilingStatus.SINGLE);

            assertThat(tax).isEqualByComparingTo(bd("6474.8125"));
        }

        @Test
        void computeTax_highIncome_hitsTopBracket() {
            // Gross: 200000, deduction: 2745, taxable: 197255
            // 4.75%: 4050 * 0.0475 = 192.375
            // 6.75%: 6150 * 0.0675 = 415.125
            // 8.75%: (125000-10200) * 0.0875 = 114800 * 0.0875 = 10045.00
            // 9.9%: (197255-125000) * 0.099 = 72255 * 0.099 = 7153.245
            // Total: 192.375 + 415.125 + 10045.00 + 7153.245 = 17805.745
            BigDecimal tax = calculator.computeTax(bd("200000"), 2024, FilingStatus.SINGLE);

            assertThat(tax).isEqualByComparingTo(bd("17805.7450"));
        }

        @Test
        void computeTax_lowIncome_firstBracketOnly() {
            // Gross: 5000, deduction: 2745, taxable: 2255
            // 4.75%: 2255 * 0.0475 = 107.1125
            BigDecimal tax = calculator.computeTax(bd("5000"), 2024, FilingStatus.SINGLE);

            assertThat(tax).isEqualByComparingTo(bd("107.1125"));
        }
    }
}
