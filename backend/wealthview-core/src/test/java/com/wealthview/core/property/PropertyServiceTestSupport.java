package com.wealthview.core.property;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.wealthview.core.tenant.TenantLookup;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;

/**
 * Shared Mockito harness for {@link PropertyServiceTest} and
 * {@link PropertyServiceCharacterizationTest} (precedent:
 * {@code DeterministicProjectionEngineTestSupport} in wealthview-projection).
 *
 * <p>The {@code @Spy} fields below are consumed reflectively by {@code @InjectMocks} when Mockito
 * wires {@link PropertyService}'s constructor. This trips a SpotBugs unused-field false positive
 * on {@code depreciationService} / {@code cashFlowService} in classes that never call them
 * directly (they are wired into {@code propertyService}, not exercised standalone) -- a known,
 * verified false positive. Do not "fix" it by removing the {@code @Spy} wiring.
 */
@ExtendWith(MockitoExtension.class)
abstract class PropertyServiceTestSupport {

    @Mock
    protected PropertyRepository propertyRepository;

    @Mock
    protected PropertyExpenseRepository expenseRepository;

    @Mock
    protected PropertyIncomeRepository incomeRepository;

    @Mock
    protected IncomeSourceRepository incomeSourceRepository;

    @Mock
    protected TenantLookup tenantLookup;

    @Mock
    protected ApplicationEventPublisher eventPublisher;

    @Spy
    protected PropertyDepreciationService depreciationService =
            new PropertyDepreciationService(new DepreciationCalculator());

    @Spy
    protected PropertyCashFlowService cashFlowService = new PropertyCashFlowService();

    @InjectMocks
    protected PropertyService propertyService;

    protected TenantEntity tenant;
    protected UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }
}
