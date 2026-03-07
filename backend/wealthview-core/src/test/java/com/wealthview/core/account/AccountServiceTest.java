package com.wealthview.core.account;

import com.wealthview.core.account.dto.AccountRequest;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private AccountService accountService;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void create_validRequest_returnsAccountResponseWithZeroBalance() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = accountService.create(tenantId, new AccountRequest("My IRA", "ira", "Vanguard"));

        assertThat(result.name()).isEqualTo("My IRA");
        assertThat(result.type()).isEqualTo("ira");
        assertThat(result.institution()).isEqualTo("Vanguard");
        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void list_tenantScoped_returnsPageResponse() {
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var page = new PageImpl<>(List.of(account));
        when(accountRepository.findByTenant_Id(tenantId, PageRequest.of(0, 25))).thenReturn(page);

        var result = accountService.list(tenantId, PageRequest.of(0, 25));

        assertThat(result.data()).hasSize(1);
        assertThat(result.total()).isEqualTo(1);
    }

    @Test
    void get_existingAccount_returnsResponse() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "401k", "401k", "Employer");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));

        var result = accountService.get(tenantId, accountId);

        assertThat(result.name()).isEqualTo("401k");
    }

    @Test
    void get_wrongTenant_throwsNotFound() {
        var accountId = UUID.randomUUID();
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.get(tenantId, accountId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void update_existingAccount_updatesFields() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Old Name", "brokerage", "Old");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = accountService.update(tenantId, accountId,
                new AccountRequest("New Name", "ira", "New Inst"));

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.type()).isEqualTo("ira");
    }

    @Test
    void delete_existingAccount_deletesSuccessfully() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Delete Me", "bank", null);
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));

        accountService.delete(tenantId, accountId);

        verify(accountRepository).delete(account);
    }

    @Test
    void get_bankAccount_returnsBalanceFromTransactions() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Checking", "bank", "Chase");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));

        var deposit = new TransactionEntity(account, tenant,
                LocalDate.of(2025, 1, 1), "deposit", null, null, new BigDecimal("5000.00"));
        var withdrawal = new TransactionEntity(account, tenant,
                LocalDate.of(2025, 1, 15), "withdrawal", null, null, new BigDecimal("1500.00"));
        when(transactionRepository.findByAccount_IdAndTenant_Id(eq(account.getId()), eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deposit, withdrawal)));

        var result = accountService.get(tenantId, accountId);

        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("3500.00"));
    }

    @Test
    void get_investmentAccount_returnsBalanceFromHoldingsAndPrices() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));

        var holding = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.00"));
        when(holdingRepository.findByAccount_IdAndTenant_Id(account.getId(), tenantId))
                .thenReturn(List.of(holding));

        var price = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        when(priceRepository.findFirstBySymbolOrderByDateDesc("AAPL"))
                .thenReturn(Optional.of(price));

        var result = accountService.get(tenantId, accountId);

        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void get_investmentAccount_noPrice_fallsToCostBasis() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));

        var holding = new HoldingEntity(account, tenant, "XYZ",
                new BigDecimal("10"), new BigDecimal("1500.00"));
        when(holdingRepository.findByAccount_IdAndTenant_Id(account.getId(), tenantId))
                .thenReturn(List.of(holding));

        when(priceRepository.findFirstBySymbolOrderByDateDesc("XYZ"))
                .thenReturn(Optional.empty());

        var result = accountService.get(tenantId, accountId);

        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("1500.00"));
    }
}
