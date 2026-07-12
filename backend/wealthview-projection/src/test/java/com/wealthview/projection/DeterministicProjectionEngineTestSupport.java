package com.wealthview.projection;

import org.junit.jupiter.api.BeforeEach;

import com.wealthview.persistence.repository.IrmaaTierRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static org.mockito.Mockito.mock;

abstract class DeterministicProjectionEngineTestSupport {

    protected DeterministicProjectionEngine engine;
    protected TaxBracketRepository taxBracketRepository;
    protected StandardDeductionRepository standardDeductionRepository;
    protected IrmaaTierRepository irmaaTierRepository;

    @BeforeEach
    void setUp() {
        engine = new DeterministicProjectionEngine(null, null);
        taxBracketRepository = mock(TaxBracketRepository.class);
        standardDeductionRepository = mock(StandardDeductionRepository.class);
        irmaaTierRepository = mock(IrmaaTierRepository.class);
    }
}
