package com.wealthview.core.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.wealthview.core.account.dto.AccountRequest;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.tenant.TenantLookup;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TenantLookup tenantLookup;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PriceRepository priceRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

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
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = accountService.create(tenantId, new AccountRequest("My IRA", "ira", "Vanguard", null));

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
                new AccountRequest("New Name", "ira", "New Inst", null));

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.type()).isEqualTo("ira");
    }

    @Test
    void update_existingAccount_updatesEveryRequestedField() {
        // Pins each individual setter — a mutant that drops setInstitution,
        // setName, setType or setCurrency would otherwise survive.
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Old Name", "brokerage", "Old Inst");
        ReflectionTestUtils.setField(account, "currency", "USD");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = accountService.update(tenantId, accountId,
                new AccountRequest("New Name", "ira", "New Inst", "EUR"));

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.type()).isEqualTo("ira");
        assertThat(result.institution()).isEqualTo("New Inst");
        assertThat(result.currency()).isEqualTo("EUR");
    }

    @Test
    void update_withNullCurrency_keepsExistingCurrency() {
        // A null currency in the request must NOT overwrite the stored value.
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Acct", "brokerage", "Inst");
        ReflectionTestUtils.setField(account, "currency", "GBP");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = accountService.update(tenantId, accountId,
                new AccountRequest("Acct", "brokerage", "Inst", null));

        assertThat(result.currency()).isEqualTo("GBP");
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
        when(transactionRepository.computeBalance(account.getId(), tenantId))
                .thenReturn(new BigDecimal("3500.00"));

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
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL")))
                .thenReturn(List.of(price));

        var result = accountService.get(tenantId, accountId);

        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void get_investmentAccount_multipleHoldings_batchFetchesPrices() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));

        var holdingAapl = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.00"));
        var holdingGoog = new HoldingEntity(account, tenant, "GOOG",
                new BigDecimal("5"), new BigDecimal("2000.00"));
        when(holdingRepository.findByAccount_IdAndTenant_Id(account.getId(), tenantId))
                .thenReturn(List.of(holdingAapl, holdingGoog));

        var priceAapl = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        var priceGoog = new PriceEntity("GOOG", LocalDate.of(2025, 3, 1), new BigDecimal("150.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL", "GOOG")))
                .thenReturn(List.of(priceAapl, priceGoog));

        var result = accountService.get(tenantId, accountId);

        // AAPL: 10 * 200 = 2000, GOOG: 5 * 150 = 750, total = 2750
        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("2750.00"));
        // Should NOT call findFirstBySymbolOrderByDateDesc (the N+1 method)
        verify(priceRepository, never()).findFirstBySymbolOrderByDateDesc(any());
    }

    @Test
    void get_bankAccount_usesRepositoryAggregation() {
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Checking", "bank", "Chase");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.computeBalance(account.getId(), tenantId))
                .thenReturn(new BigDecimal("3500.00"));

        var result = accountService.get(tenantId, accountId);

        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("3500.00"));
        // Should NOT load all transactions via Pageable.unpaged()
        verify(transactionRepository, never()).findByAccount_IdAndTenant_Id(any(), any(), any());
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

        when(priceRepository.findLatestBySymbolIn(List.of("XYZ")))
                .thenReturn(List.of());

        var result = accountService.get(tenantId, accountId);

        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("1500.00"));
    }

    @Test
    void create_withCurrency_setsAccountCurrency() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = accountService.create(tenantId, new AccountRequest("Euro IRA", "ira", "Degiro", "EUR"));

        assertThat(result.currency()).isEqualTo("EUR");
    }

    @Test
    void create_withNullCurrency_defaultsToUsd() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = accountService.create(tenantId, new AccountRequest("My IRA", "ira", "Vanguard", null));

        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    void computeAllBalances_mixedAccountTypes_returnsAllBalances() {
        var bankAccount = new AccountEntity(tenant, "Checking", "bank", "Chase");
        var brokerageAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var bankId = UUID.randomUUID();
        var brokerageId = UUID.randomUUID();
        ReflectionTestUtils.setField(bankAccount, "id", bankId);
        ReflectionTestUtils.setField(brokerageAccount, "id", brokerageId);

        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(bankAccount, brokerageAccount));

        Object[] bankRow = {bankId, new BigDecimal("5000.00")};
        when(transactionRepository.computeBalancesByAccountIds(eq(tenantId), any()))
                .thenReturn(List.<Object[]>of(bankRow));

        var holding = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.00"));
        when(holdingRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(holding));
        var price = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL")))
                .thenReturn(List.of(price));

        var result = accountService.computeAllBalances(tenantId);

        assertThat(result).hasSize(2);
        assertThat(result.get(bankId)).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.get(brokerageId)).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void computeAllBalances_bulkBankQuery_includesOnlyBankAccountIds() {
        // The bulkBankBalances lambda must filter to bank accounts only — a
        // brokerage account id must NOT be passed to the bank-balance query.
        var bankAccount = new AccountEntity(tenant, "Checking", "bank", "Chase");
        var brokerageAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var bankId = UUID.randomUUID();
        var brokerageId = UUID.randomUUID();
        ReflectionTestUtils.setField(bankAccount, "id", bankId);
        ReflectionTestUtils.setField(brokerageAccount, "id", brokerageId);
        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(bankAccount, brokerageAccount));
        when(holdingRepository.findByTenant_Id(tenantId)).thenReturn(List.of());
        when(transactionRepository.computeBalancesByAccountIds(eq(tenantId), any()))
                .thenReturn(List.of());

        accountService.computeAllBalances(tenantId);

        var idsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).computeBalancesByAccountIds(eq(tenantId), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(bankId);
    }

    @Test
    void computeAllBalances_noBankAccounts_skipsBankBalanceQuery() {
        // When there is no bank account the bank-balance query must not run.
        var brokerageAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        ReflectionTestUtils.setField(brokerageAccount, "id", UUID.randomUUID());
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(brokerageAccount));
        when(holdingRepository.findByTenant_Id(tenantId)).thenReturn(List.of());

        accountService.computeAllBalances(tenantId);

        verify(transactionRepository, never()).computeBalancesByAccountIds(any(), any());
    }

    @Test
    void computeBalance_investmentWithUnpricedSymbol_usesCostBasisForThatHolding() {
        // computeInvestmentValue's unpriced-symbol branch: a holding with a
        // priced symbol contributes quantity*price, an unpriced one contributes
        // its cost basis — both must be summed.
        var accountId = UUID.randomUUID();
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        var priced = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.00"));
        var unpriced = new HoldingEntity(account, tenant, "SPAXX",
                new BigDecimal("100"), new BigDecimal("777.00"));
        when(holdingRepository.findByAccount_IdAndTenant_Id(account.getId(), tenantId))
                .thenReturn(List.of(priced, unpriced));
        var price = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL", "SPAXX")))
                .thenReturn(List.of(price));

        var result = accountService.get(tenantId, accountId);

        // AAPL 10*200 = 2000, SPAXX falls to cost basis 777 -> 2777
        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("2777.00"));
    }

    @Test
    void computeCostBasis_investmentAccount_sumsHoldingsCostBasis() {
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var holdingAapl = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.00"));
        var holdingGoog = new HoldingEntity(account, tenant, "GOOG",
                new BigDecimal("5"), new BigDecimal("900.00"));
        when(holdingRepository.findByAccount_IdAndTenant_Id(account.getId(), tenantId))
                .thenReturn(List.of(holdingAapl, holdingGoog));

        var result = accountService.computeCostBasis(account, tenantId);

        assertThat(result).isEqualByComparingTo(new BigDecimal("2400.00"));
    }

    @Test
    void computeCostBasis_investmentAccountNoHoldings_returnsZero() {
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        when(holdingRepository.findByAccount_IdAndTenant_Id(account.getId(), tenantId))
                .thenReturn(List.of());

        var result = accountService.computeCostBasis(account, tenantId);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeCostBasis_bankAccount_returnsTransactionBalance() {
        var account = new AccountEntity(tenant, "Checking", "bank", "Chase");
        when(transactionRepository.computeBalance(account.getId(), tenantId))
                .thenReturn(new BigDecimal("3500.00"));

        var result = accountService.computeCostBasis(account, tenantId);

        // Cash has no capital-gains character: basis == balance, no embedded gain.
        assertThat(result).isEqualByComparingTo(new BigDecimal("3500.00"));
        verify(holdingRepository, never()).findByAccount_IdAndTenant_Id(any(), any());
    }

    @Test
    void computeAllBalances_noAccounts_returnsEmptyMap() {
        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of());

        var result = accountService.computeAllBalances(tenantId);

        assertThat(result).isEmpty();
    }

    @Test
    void computeAllBalances_investmentWithNoPrice_fallsToCostBasis() {
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var accountId = UUID.randomUUID();
        ReflectionTestUtils.setField(account, "id", accountId);

        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(account));

        var holding = new HoldingEntity(account, tenant, "XYZ",
                new BigDecimal("10"), new BigDecimal("1500.00"));
        when(holdingRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(holding));
        when(priceRepository.findLatestBySymbolIn(List.of("XYZ")))
                .thenReturn(List.of());

        var result = accountService.computeAllBalances(tenantId);

        assertThat(result.get(accountId)).isEqualByComparingTo(new BigDecimal("1500.00"));
    }
}
