package com.wealthview.core.holding;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.holding.dto.HoldingRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldingServiceTest {

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private HoldingService holdingService;

    private TenantEntity tenant;
    private AccountEntity account;
    private UUID tenantId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
    }

    @Test
    void listByAccount_returnsHoldings() {
        var holding = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding));

        var result = holdingService.listByAccount(tenantId, accountId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
    }

    @Test
    void createManual_setsOverrideFlag() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(holdingRepository.save(any(HoldingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new HoldingRequest(accountId, "MSFT",
                new BigDecimal("50"), new BigDecimal("15000"));
        var result = holdingService.createManual(tenantId, request);

        assertThat(result.isManualOverride()).isTrue();
        assertThat(result.symbol()).isEqualTo("MSFT");
    }

    @Test
    void update_existingHolding_updatesFields() {
        var holdingId = UUID.randomUUID();
        var holding = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(holdingRepository.findByIdAndTenant_Id(holdingId, tenantId))
                .thenReturn(Optional.of(holding));
        when(holdingRepository.save(any(HoldingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new HoldingRequest(accountId, "AAPL",
                new BigDecimal("20"), new BigDecimal("3000"));
        var result = holdingService.update(tenantId, holdingId, request);

        assertThat(result.quantity()).isEqualByComparingTo("20");
        assertThat(result.isManualOverride()).isTrue();
    }

    @Test
    void getById_found_returnsHolding() {
        var holdingId = UUID.randomUUID();
        var holding = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(holdingRepository.findByIdAndTenant_Id(holdingId, tenantId))
                .thenReturn(Optional.of(holding));

        var result = holdingService.getById(tenantId, holdingId);

        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.quantity()).isEqualByComparingTo("10");
    }

    @Test
    void getById_notFound_throwsEntityNotFoundException() {
        var holdingId = UUID.randomUUID();
        when(holdingRepository.findByIdAndTenant_Id(holdingId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdingService.getById(tenantId, holdingId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void update_nonExistent_throwsNotFound() {
        var holdingId = UUID.randomUUID();
        when(holdingRepository.findByIdAndTenant_Id(holdingId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdingService.update(tenantId, holdingId,
                new HoldingRequest(accountId, "AAPL", BigDecimal.TEN, BigDecimal.TEN)))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
