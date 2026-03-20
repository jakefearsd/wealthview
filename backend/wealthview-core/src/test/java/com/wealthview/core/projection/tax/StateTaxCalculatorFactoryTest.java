package com.wealthview.core.projection.tax;

import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class StateTaxCalculatorFactoryTest {

    @Mock
    private StateTaxBracketRepository bracketRepo;

    @Mock
    private StateStandardDeductionRepository deductionRepo;

    @Mock
    private StateTaxSurchargeRepository surchargeRepo;

    private StateTaxCalculatorFactory factory;

    @BeforeEach
    void setUp() {
        factory = new StateTaxCalculatorFactory(bracketRepo, deductionRepo, surchargeRepo);
    }

    @Test
    void forState_california_returnsCaliforniaCalculator() {
        StateTaxCalculator calc = factory.forState("CA");

        assertThat(calc).isInstanceOf(CaliforniaStateTaxCalculator.class);
        assertThat(calc.stateCode()).isEqualTo("CA");
    }

    @Test
    void forState_null_returnsNullCalculator() {
        StateTaxCalculator calc = factory.forState(null);

        assertThat(calc).isInstanceOf(NullStateTaxCalculator.class);
    }

    @Test
    void forState_empty_returnsNullCalculator() {
        StateTaxCalculator calc = factory.forState("");

        assertThat(calc).isInstanceOf(NullStateTaxCalculator.class);
    }

    @Test
    void forState_noIncomeTaxState_returnsNullCalculator() {
        StateTaxCalculator calc = factory.forState("TX");

        assertThat(calc).isInstanceOf(NullStateTaxCalculator.class);
    }

    @Test
    void forState_florida_returnsNullCalculator() {
        StateTaxCalculator calc = factory.forState("FL");

        assertThat(calc).isInstanceOf(NullStateTaxCalculator.class);
    }

    @Test
    void forState_washington_returnsNullCalculator() {
        StateTaxCalculator calc = factory.forState("WA");

        assertThat(calc).isInstanceOf(NullStateTaxCalculator.class);
    }

    @Test
    void forState_caseInsensitive_returnsCaliforniaCalculator() {
        StateTaxCalculator calc = factory.forState("ca");

        assertThat(calc).isInstanceOf(CaliforniaStateTaxCalculator.class);
    }

    @Test
    void forState_arizona_returnsBracketBasedCalculator() {
        StateTaxCalculator calc = factory.forState("AZ");

        assertThat(calc).isInstanceOf(BracketBasedStateTaxCalculator.class);
        assertThat(calc.stateCode()).isEqualTo("AZ");
    }

    @Test
    void forState_oregon_returnsBracketBasedCalculator() {
        StateTaxCalculator calc = factory.forState("OR");

        assertThat(calc).isInstanceOf(BracketBasedStateTaxCalculator.class);
        assertThat(calc.stateCode()).isEqualTo("OR");
    }

    @Test
    void forState_nevada_returnsNullCalculator() {
        StateTaxCalculator calc = factory.forState("NV");

        assertThat(calc).isInstanceOf(NullStateTaxCalculator.class);
    }
}
