package com.wealthview.core.projection.tax;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

    private ListAppender<ILoggingEvent> appender;
    private Logger factoryLogger;

    @BeforeEach
    void setUp() {
        factory = new StateTaxCalculatorFactory(bracketRepo, deductionRepo, surchargeRepo);

        appender = new ListAppender<>();
        appender.start();
        factoryLogger = (Logger) LoggerFactory.getLogger(StateTaxCalculatorFactory.class);
        factoryLogger.addAppender(appender);
        factoryLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        factoryLogger.detachAppender(appender);
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

    // --- audit C3: unsupported-state warning ---

    @Test
    void unsupportedStateWarning_unsupportedTaxableState_returnsMessageNamingTheState() {
        Optional<String> warning = factory.unsupportedStateWarning("NY");

        assertThat(warning).isPresent();
        assertThat(warning.get()).contains("NY").contains("not modeled");
    }

    @Test
    void unsupportedStateWarning_supportedState_returnsEmpty() {
        assertThat(factory.unsupportedStateWarning("CA")).isEmpty();
        assertThat(factory.unsupportedStateWarning("AZ")).isEmpty();
        assertThat(factory.unsupportedStateWarning("OR")).isEmpty();
    }

    @Test
    void unsupportedStateWarning_noIncomeTaxState_returnsEmpty() {
        // TX genuinely has no state income tax -- not "unsupported", nothing to warn about.
        assertThat(factory.unsupportedStateWarning("TX")).isEmpty();
    }

    @Test
    void unsupportedStateWarning_blankOrNullState_returnsEmpty() {
        assertThat(factory.unsupportedStateWarning(null)).isEmpty();
        assertThat(factory.unsupportedStateWarning("")).isEmpty();
        assertThat(factory.unsupportedStateWarning("  ")).isEmpty();
    }

    @Test
    void unsupportedStateWarning_caseInsensitive_matchesSupportedState() {
        assertThat(factory.unsupportedStateWarning("ca")).isEmpty();
    }

    @Test
    void forState_unsupportedState_logsWarnExactlyOnce() {
        factory.forState("NY");

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("NY").contains("not modeled");
    }

    @Test
    void forState_supportedState_logsNoWarning() {
        factory.forState("CA");

        assertThat(appender.list).isEmpty();
    }

    @Test
    void forState_noIncomeTaxState_logsNoWarning() {
        factory.forState("TX");

        assertThat(appender.list).isEmpty();
    }

    @Test
    void forState_blankState_logsNoWarning() {
        factory.forState(null);

        assertThat(appender.list).isEmpty();
    }
}
