# Multi-Currency Account Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow accounts to hold a non-USD currency with manual exchange rates, converting to USD at aggregation points (dashboard, portfolio history, projections).

**Architecture:** Add `currency` column to `accounts` table (default `'USD'`). New `exchange_rates` table stores one rate per currency per tenant. `ExchangeRateService.convertToUsd()` multiplies at four aggregation boundaries: `DashboardService`, `CombinedPortfolioHistoryService`, `TheoreticalPortfolioService`, and `ProjectionInputBuilder`. `AccountService.computeBalance()` stays unchanged — it returns native-currency values so account detail views show EUR amounts for EUR accounts. Frontend adds currency to account form and a new Exchange Rates section in Admin.

**Tech Stack:** Java 21 / Spring Boot / JPA, PostgreSQL, Flyway, React 18, Vitest

**Spec:** `docs/superpowers/specs/2026-03-28-multi-currency-design.md`

---

### Task 1: Database Migrations

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V053__add_currency_to_accounts.sql`
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V054__create_exchange_rates_table.sql`

- [ ] **Step 1: Create V053 migration — add currency to accounts**

```sql
-- Add currency column to accounts table (ISO 4217 code, defaults to USD)
ALTER TABLE accounts ADD COLUMN currency text NOT NULL DEFAULT 'USD';
```

- [ ] **Step 2: Create V054 migration — exchange_rates table**

```sql
-- Exchange rates table: one rate per currency per tenant
CREATE TABLE exchange_rates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    currency_code text NOT NULL,
    rate_to_usd numeric(19,8) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_exchange_rates_tenant_currency UNIQUE (tenant_id, currency_code)
);
CREATE INDEX idx_exchange_rates_tenant_id ON exchange_rates(tenant_id);
```

- [ ] **Step 3: Verify migrations apply cleanly**

Run: `cd backend && mvn compile -pl wealthview-persistence`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-persistence/src/main/resources/db/migration/V053__add_currency_to_accounts.sql \
      backend/wealthview-persistence/src/main/resources/db/migration/V054__create_exchange_rates_table.sql
git commit -m "db(persistence): add currency column and exchange_rates table

V053 adds currency text column to accounts with default 'USD'.
V054 creates exchange_rates table with tenant_id, currency_code,
rate_to_usd (numeric 19,8), and unique constraint per tenant+currency."
```

---

### Task 2: ExchangeRateEntity and Repository

**Files:**
- Create: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/ExchangeRateEntity.java`
- Create: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/ExchangeRateRepository.java`
- Modify: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/AccountEntity.java`

- [ ] **Step 1: Create ExchangeRateEntity**

```java
package com.wealthview.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "exchange_rates")
public class ExchangeRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "currency_code", nullable = false)
    private String currencyCode;

    @Column(name = "rate_to_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal rateToUsd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected ExchangeRateEntity() {
    }

    public ExchangeRateEntity(TenantEntity tenant, String currencyCode, BigDecimal rateToUsd) {
        this.tenant = tenant;
        this.currencyCode = currencyCode;
        this.rateToUsd = rateToUsd;
    }

    public UUID getId() {
        return id;
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getRateToUsd() {
        return rateToUsd;
    }

    public void setRateToUsd(BigDecimal rateToUsd) {
        this.rateToUsd = rateToUsd;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

- [ ] **Step 2: Create ExchangeRateRepository**

```java
package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.ExchangeRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRateEntity, UUID> {

    Optional<ExchangeRateEntity> findByTenant_IdAndCurrencyCode(UUID tenantId, String currencyCode);

    List<ExchangeRateEntity> findByTenant_Id(UUID tenantId);
}
```

- [ ] **Step 3: Add currency field to AccountEntity**

In `AccountEntity.java`, add a `currency` field after the existing `institution` field:

```java
@Column(nullable = false)
private String currency = "USD";
```

Update the constructor to accept currency:

```java
public AccountEntity(TenantEntity tenant, String name, String type, String institution, String currency) {
    this.tenant = tenant;
    this.name = name;
    this.type = type;
    this.institution = institution;
    this.currency = currency != null ? currency : "USD";
}
```

Keep the old 4-arg constructor for backward compatibility with existing tests:

```java
public AccountEntity(TenantEntity tenant, String name, String type, String institution) {
    this(tenant, name, type, institution, "USD");
}
```

Add getter and setter:

```java
public String getCurrency() {
    return currency;
}

public void setCurrency(String currency) {
    this.currency = currency;
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd backend && mvn compile -pl wealthview-persistence`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/ExchangeRateEntity.java \
      backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/ExchangeRateRepository.java \
      backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/AccountEntity.java
git commit -m "feat(persistence): add ExchangeRateEntity, repository, and currency to AccountEntity

ExchangeRateEntity maps to exchange_rates table with tenant-scoped
currency_code and rate_to_usd. AccountEntity gets currency field
defaulting to USD with backward-compatible constructor."
```

---

### Task 3: ExchangeRateService with Tests (TDD)

**Files:**
- Create: `backend/wealthview-core/src/test/java/com/wealthview/core/exchangerate/ExchangeRateServiceTest.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/exchangerate/ExchangeRateService.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/exchangerate/dto/ExchangeRateRequest.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/exchangerate/dto/ExchangeRateResponse.java`

- [ ] **Step 1: Create ExchangeRateRequest DTO**

```java
package com.wealthview.core.exchangerate.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record ExchangeRateRequest(
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rateToUsd
) {
}
```

- [ ] **Step 2: Create ExchangeRateResponse DTO**

```java
package com.wealthview.core.exchangerate.dto;

import com.wealthview.persistence.entity.ExchangeRateEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ExchangeRateResponse(
        String currencyCode,
        BigDecimal rateToUsd,
        OffsetDateTime updatedAt
) {
    public static ExchangeRateResponse from(ExchangeRateEntity entity) {
        return new ExchangeRateResponse(
                entity.getCurrencyCode(),
                entity.getRateToUsd(),
                entity.getUpdatedAt());
    }
}
```

- [ ] **Step 3: Write failing tests for ExchangeRateService**

```java
package com.wealthview.core.exchangerate;

import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.ExchangeRateEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ExchangeRateRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private ExchangeRateService exchangeRateService;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void create_validRequest_returnsResponse() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.empty());
        when(exchangeRateRepository.save(any(ExchangeRateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = exchangeRateService.create(tenantId,
                new ExchangeRateRequest("EUR", new BigDecimal("1.08")));

        assertThat(result.currencyCode()).isEqualTo("EUR");
        assertThat(result.rateToUsd()).isEqualByComparingTo(new BigDecimal("1.08"));
    }

    @Test
    void create_duplicateCurrency_throwsDuplicateEntity() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"))));

        assertThatThrownBy(() -> exchangeRateService.create(tenantId,
                new ExchangeRateRequest("EUR", new BigDecimal("1.10"))))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void create_usdCurrency_throwsIllegalArgument() {
        assertThatThrownBy(() -> exchangeRateService.create(tenantId,
                new ExchangeRateRequest("USD", BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_existingRate_updatesValue() {
        var entity = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(entity));
        when(exchangeRateRepository.save(any(ExchangeRateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = exchangeRateService.update(tenantId, "EUR", new BigDecimal("1.12"));

        assertThat(result.rateToUsd()).isEqualByComparingTo(new BigDecimal("1.12"));
    }

    @Test
    void update_nonExistentCurrency_throwsNotFound() {
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "GBP"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeRateService.update(tenantId, "GBP", new BigDecimal("1.25")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_noAccountsUsingCurrency_succeeds() {
        var entity = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(entity));
        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of());

        exchangeRateService.delete(tenantId, "EUR");

        verify(exchangeRateRepository).delete(entity);
    }

    @Test
    void delete_accountsUsingCurrency_throwsIllegalState() {
        var entity = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(entity));
        var account = new AccountEntity(tenant, "Euro Account", "brokerage", null, "EUR");
        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(account));

        assertThatThrownBy(() -> exchangeRateService.delete(tenantId, "EUR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 account");
    }

    @Test
    void list_returnsAllRatesForTenant() {
        var eur = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        var gbp = new ExchangeRateEntity(tenant, "GBP", new BigDecimal("1.27"));
        when(exchangeRateRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(eur, gbp));

        var result = exchangeRateService.list(tenantId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting("currencyCode").containsExactlyInAnyOrder("EUR", "GBP");
    }

    @Test
    void convertToUsd_usdCurrency_returnsAmountUnchanged() {
        var result = exchangeRateService.convertToUsd(new BigDecimal("100.00"), "USD", tenantId);

        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void convertToUsd_eurCurrency_multipliesByRate() {
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"))));

        var result = exchangeRateService.convertToUsd(new BigDecimal("1000.00"), "EUR", tenantId);

        assertThat(result).isEqualByComparingTo(new BigDecimal("1080.00"));
    }

    @Test
    void convertToUsd_missingRate_throwsEntityNotFound() {
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "JPY"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeRateService.convertToUsd(
                new BigDecimal("10000"), "JPY", tenantId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("JPY");
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=ExchangeRateServiceTest`
Expected: FAIL — ExchangeRateService class does not exist

- [ ] **Step 5: Implement ExchangeRateService**

```java
package com.wealthview.core.exchangerate;

import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.core.exchangerate.dto.ExchangeRateResponse;
import com.wealthview.persistence.entity.ExchangeRateEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ExchangeRateRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private final ExchangeRateRepository exchangeRateRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository,
                               TenantRepository tenantRepository,
                               AccountRepository accountRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public ExchangeRateResponse create(UUID tenantId, ExchangeRateRequest request) {
        if ("USD".equals(request.currencyCode())) {
            throw new IllegalArgumentException("Cannot create exchange rate for USD — it is always 1.0");
        }

        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        var existing = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, request.currencyCode());
        if (existing.isPresent()) {
            throw new DuplicateEntityException(
                    "Exchange rate for " + request.currencyCode() + " already exists");
        }

        var entity = new ExchangeRateEntity(tenant, request.currencyCode(), request.rateToUsd());
        entity = exchangeRateRepository.save(entity);
        log.info("Exchange rate created: {} = {} USD for tenant {}", request.currencyCode(),
                request.rateToUsd(), tenantId);
        return ExchangeRateResponse.from(entity);
    }

    @Transactional
    public ExchangeRateResponse update(UUID tenantId, String currencyCode, BigDecimal rateToUsd) {
        var entity = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, currencyCode)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Exchange rate not found for currency: " + currencyCode));

        entity.setRateToUsd(rateToUsd);
        entity.setUpdatedAt(OffsetDateTime.now());
        entity = exchangeRateRepository.save(entity);
        log.info("Exchange rate updated: {} = {} USD for tenant {}", currencyCode, rateToUsd, tenantId);
        return ExchangeRateResponse.from(entity);
    }

    @Transactional
    public void delete(UUID tenantId, String currencyCode) {
        var entity = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, currencyCode)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Exchange rate not found for currency: " + currencyCode));

        var accountsUsingCurrency = accountRepository.findByTenant_Id(tenantId).stream()
                .filter(a -> currencyCode.equals(a.getCurrency()))
                .count();

        if (accountsUsingCurrency > 0) {
            throw new IllegalStateException("Cannot delete " + currencyCode + " rate: "
                    + accountsUsingCurrency + " account(s) use this currency");
        }

        exchangeRateRepository.delete(entity);
        log.info("Exchange rate deleted: {} for tenant {}", currencyCode, tenantId);
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateResponse> list(UUID tenantId) {
        return exchangeRateRepository.findByTenant_Id(tenantId).stream()
                .map(ExchangeRateResponse::from)
                .toList();
    }

    public BigDecimal convertToUsd(BigDecimal amount, String currency, UUID tenantId) {
        if ("USD".equals(currency)) {
            return amount;
        }

        var entity = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, currency)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No exchange rate found for " + currency
                                + " — add one in Settings before using this currency"));

        return amount.multiply(entity.getRateToUsd()).setScale(4, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=ExchangeRateServiceTest`
Expected: All 10 tests PASS

- [ ] **Step 7: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/exchangerate/ \
      backend/wealthview-core/src/test/java/com/wealthview/core/exchangerate/
git commit -m "feat(core): add ExchangeRateService with CRUD and convertToUsd

ExchangeRateService supports create, update, delete, list, and
convertToUsd. Blocks deletion when accounts use the currency.
Rejects USD as a manageable currency. convertToUsd returns amount
unchanged for USD, multiplies by rate for other currencies.

10 unit tests covering happy paths, duplicates, missing rates,
delete-with-accounts guard, and conversion logic."
```

---

### Task 4: Update AccountRequest/Response DTOs and AccountService

`computeBalance()` stays unchanged — it returns native-currency values. This means account detail views show EUR amounts for EUR accounts. Conversion to USD happens at aggregation points (Tasks 6 and 6b).

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/account/dto/AccountRequest.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/account/dto/AccountResponse.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/account/AccountService.java`
- Modify: `backend/wealthview-core/src/test/java/com/wealthview/core/account/AccountServiceTest.java`

- [ ] **Step 1: Update AccountRequest to include currency**

Replace the record in `AccountRequest.java`:

```java
package com.wealthview.core.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "brokerage|ira|401k|roth|bank") String type,
        String institution,
        String currency
) {
}
```

- [ ] **Step 2: Update AccountResponse to include currency**

Replace the record in `AccountResponse.java`:

```java
package com.wealthview.core.account.dto;

import com.wealthview.persistence.entity.AccountEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String type,
        String institution,
        String currency,
        BigDecimal balance,
        OffsetDateTime createdAt
) {
    public static AccountResponse from(AccountEntity entity, BigDecimal balance) {
        return new AccountResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getInstitution(),
                entity.getCurrency(),
                balance,
                entity.getCreatedAt()
        );
    }
}
```

- [ ] **Step 3: Update AccountService — pass currency on create/update (do NOT change computeBalance)**

In `AccountService.java`:

Update `create()` — pass currency from request:

```java
var currency = request.currency() != null ? request.currency() : "USD";
var account = new AccountEntity(tenant, request.name(), request.type(), request.institution(), currency);
```

Update `update()` — set currency:

```java
account.setCurrency(request.currency() != null ? request.currency() : account.getCurrency());
```

`computeBalance()` is NOT changed — it continues to return the native-currency balance.

- [ ] **Step 4: Write new tests for currency behavior in AccountServiceTest**

Add these tests:

```java
@Test
void create_withCurrency_setsAccountCurrency() {
    when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
    when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    var result = accountService.create(tenantId, new AccountRequest("Euro IRA", "ira", "Degiro", "EUR"));

    assertThat(result.currency()).isEqualTo("EUR");
}

@Test
void create_withNullCurrency_defaultsToUsd() {
    when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
    when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    var result = accountService.create(tenantId, new AccountRequest("My IRA", "ira", "Vanguard", null));

    assertThat(result.currency()).isEqualTo("USD");
}
```

- [ ] **Step 5: Fix existing tests that construct AccountRequest with 3 args**

Update all existing `new AccountRequest(...)` calls in the test class to add a 4th `null` argument for currency:

```java
// Before:
new AccountRequest("My IRA", "ira", "Vanguard")
// After:
new AccountRequest("My IRA", "ira", "Vanguard", null)
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=AccountServiceTest`
Expected: All tests PASS (existing + 2 new)

- [ ] **Step 7: Run all core tests to catch regressions**

Run: `cd backend && mvn test -pl wealthview-core`
Expected: All tests PASS. If any fail because of the AccountRequest 4th parameter, fix those call sites too.

- [ ] **Step 8: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/account/ \
      backend/wealthview-core/src/test/java/com/wealthview/core/account/
git commit -m "feat(core): add currency to AccountRequest/Response DTOs

AccountRequest accepts optional currency (defaults to USD).
AccountResponse includes currency field. computeBalance() unchanged —
returns native-currency values. Conversion happens at aggregation
boundaries (dashboard, portfolio history, projections)."
```

---

### Task 5: ExchangeRateController with Tests (TDD)

**Files:**
- Create: `backend/wealthview-api/src/test/java/com/wealthview/api/controller/ExchangeRateControllerTest.java`
- Create: `backend/wealthview-api/src/main/java/com/wealthview/api/controller/ExchangeRateController.java`

- [ ] **Step 1: Write failing controller tests**

```java
package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.core.exchangerate.dto.ExchangeRateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExchangeRateController.class)
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExchangeRateService exchangeRateService;

    // NOTE: The actual test class will need the same security setup as other
    // controller tests in this project. Check existing tests (e.g., AccountControllerTest)
    // for how @WithMockUser or custom security is configured, and replicate it.
    // The tests below show the logical assertions; adjust security setup to match.

    @Test
    void list_returnsAllRates() throws Exception {
        var rates = List.of(
                new ExchangeRateResponse("EUR", new BigDecimal("1.08"), OffsetDateTime.now()),
                new ExchangeRateResponse("GBP", new BigDecimal("1.27"), OffsetDateTime.now()));
        when(exchangeRateService.list(any())).thenReturn(rates);

        mockMvc.perform(get("/api/v1/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].currency_code").value("EUR"));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        var response = new ExchangeRateResponse("EUR", new BigDecimal("1.08"), OffsetDateTime.now());
        when(exchangeRateService.create(any(), any(ExchangeRateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/exchange-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "EUR", "rate_to_usd": 1.08}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency_code").value("EUR"));
    }

    @Test
    void create_invalidCurrencyCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "eu", "rate_to_usd": 1.08}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_zeroRate_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "EUR", "rate_to_usd": 0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_existingCurrency_returns200() throws Exception {
        var response = new ExchangeRateResponse("EUR", new BigDecimal("1.12"), OffsetDateTime.now());
        when(exchangeRateService.update(any(), eq("EUR"), any(BigDecimal.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/exchange-rates/EUR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "EUR", "rate_to_usd": 1.12}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate_to_usd").value(1.12));
    }

    @Test
    void delete_existingCurrency_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/exchange-rates/EUR"))
                .andExpect(status().isNoContent());

        verify(exchangeRateService).delete(any(), eq("EUR"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -pl wealthview-api -Dtest=ExchangeRateControllerTest`
Expected: FAIL — ExchangeRateController does not exist

- [ ] **Step 3: Implement ExchangeRateController**

```java
package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.core.exchangerate.dto.ExchangeRateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/exchange-rates")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @GetMapping
    public List<ExchangeRateResponse> list(@AuthenticationPrincipal TenantUserPrincipal principal) {
        return exchangeRateService.list(principal.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExchangeRateResponse create(@AuthenticationPrincipal TenantUserPrincipal principal,
                                       @Valid @RequestBody ExchangeRateRequest request) {
        return exchangeRateService.create(principal.tenantId(), request);
    }

    @PutMapping("/{currencyCode}")
    public ExchangeRateResponse update(@AuthenticationPrincipal TenantUserPrincipal principal,
                                       @PathVariable String currencyCode,
                                       @Valid @RequestBody ExchangeRateRequest request) {
        return exchangeRateService.update(principal.tenantId(), currencyCode, request.rateToUsd());
    }

    @DeleteMapping("/{currencyCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TenantUserPrincipal principal,
                       @PathVariable String currencyCode) {
        exchangeRateService.delete(principal.tenantId(), currencyCode);
    }
}
```

- [ ] **Step 4: Run tests — adjust security config if needed**

Run: `cd backend && mvn test -pl wealthview-api -Dtest=ExchangeRateControllerTest`

If tests fail due to security (401), check how other controller tests handle authentication. Look at existing controller tests for `@Import` or security test config annotations and replicate them. The TenantUserPrincipal mock may need a custom `@WithMockUser` or `SecurityMockMvcConfigurers` setup.

Expected after fixes: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-api/src/main/java/com/wealthview/api/controller/ExchangeRateController.java \
      backend/wealthview-api/src/test/java/com/wealthview/api/controller/ExchangeRateControllerTest.java
git commit -m "feat(api): add ExchangeRateController with CRUD endpoints

GET/POST /api/v1/exchange-rates, PUT/DELETE /api/v1/exchange-rates/{code}.
All endpoints tenant-scoped via TenantUserPrincipal. Input validated with
Jakarta Bean Validation (3-letter uppercase code, positive rate).
6 controller tests covering CRUD operations and validation."
```

---

### Task 6: Add Currency Conversion at Aggregation Boundaries

Since `computeBalance()` returns native-currency values, conversion must happen at each aggregation boundary: DashboardService (cross-account sums), ProjectionInputBuilder (linked account → projection), TheoreticalPortfolioService (single-account history for non-USD), and CombinedPortfolioHistoryService (multi-account portfolio history).

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/DashboardService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionInputBuilder.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/portfolio/TheoreticalPortfolioService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/CombinedPortfolioHistoryService.java`

- [ ] **Step 1: Update DashboardService — convert account balances to USD before summing**

Add `ExchangeRateService` as a constructor dependency.

In `getSummary()`, after computing `accountBalance` (line 56), convert to USD before adding to totals:

```java
var accountBalance = accountService.computeBalance(account, tenantId);
var accountBalanceUsd = exchangeRateService.convertToUsd(accountBalance, account.getCurrency(), tenantId);
```

Use `accountBalanceUsd` for the totals (`totalCash`/`totalInvestments`) and `allocationMap`. The `AccountSummary` should also use the USD-converted value so dashboard displays are consistent.

Add import: `import com.wealthview.core.exchangerate.ExchangeRateService;`

- [ ] **Step 2: Update ProjectionInputBuilder — convert linked account balance to USD**

Add `ExchangeRateService` as a constructor dependency.

In `toAccountInput()` (line 155-161), after `computeBalance()` returns the native balance, convert to USD:

```java
if (entity.getLinkedAccount() != null) {
    var nativeBalance = accountService.computeBalance(entity.getLinkedAccount(), tenantId);
    var liveBalance = exchangeRateService.convertToUsd(
            nativeBalance, entity.getLinkedAccount().getCurrency(), tenantId);
    return new LinkedAccountInput(
            entity.getLinkedAccount().getId(), liveBalance,
            entity.getAnnualContribution(), entity.getExpectedReturn(),
            entity.getAccountType());
}
```

Add import: `import com.wealthview.core.exchangerate.ExchangeRateService;`

- [ ] **Step 3: Update TheoreticalPortfolioService — add ExchangeRateService dependency and convert**

Add `ExchangeRateService` constructor parameter and field.

In `computeHistory()`, after computing the data points, convert each data point's value if the account has a non-USD currency. The account entity is already loaded at line 52 — read its currency.

After line 95 where `computeWeeklyValuesWithMoneyMarket()` returns `dataPoints`, add conversion:

```java
var currency = account.getCurrency();
if (!"USD".equals(currency)) {
    // Convert all data point values from account currency to USD
    var convertedPoints = new ArrayList<PortfolioDataPointDto>();
    for (var dp : dataPoints) {
        var convertedValue = exchangeRateService.convertToUsd(dp.totalValue(), currency, tenantId);
        convertedPoints.add(new PortfolioDataPointDto(dp.date(), convertedValue));
    }
    dataPoints = convertedPoints;
    if (hasMoneyMarket) {
        moneyMarketTotal = exchangeRateService.convertToUsd(moneyMarketTotal, currency, tenantId);
    }
}
```

Add necessary imports for `ExchangeRateService` and `ArrayList`.

- [ ] **Step 4: Update CombinedPortfolioHistoryService — convert per-account holdings**

This is more complex because holdings from multiple accounts are aggregated. The cleanest approach: group holdings by currency, compute values per currency group, then convert to USD before summing.

Add `ExchangeRateService` as a constructor dependency.

In `computeHistory()`, instead of aggregating all holdings into a single `quantityBySymbol` map, track which currency each account uses. Then in `computeInvestmentValue()`, pass the currency and convert.

The simplest approach: after computing `investmentValue` in the data-point loop (line 149-151), split investment value by account currency. Since accounts are already loaded, create a map from account ID to currency, then group holdings by currency.

Replace the investment value computation section. Before the friday loop:

```java
// Group accounts by currency
var currencyByAccountId = investmentAccounts.stream()
        .collect(Collectors.toMap(AccountEntity::getId, AccountEntity::getCurrency));

// Group regular holdings by currency
var holdingsByCurrency = new HashMap<String, List<HoldingEntity>>();
for (var h : regularHoldings) {
    var currency = currencyByAccountId.getOrDefault(h.getAccountId(), "USD");
    holdingsByCurrency.computeIfAbsent(currency, k -> new ArrayList<>()).add(h);
}

// Group money market totals by currency
var mmTotalByCurrency = new HashMap<String, BigDecimal>();
for (var h : moneyMarketHoldings) {
    var currency = currencyByAccountId.getOrDefault(h.getAccountId(), "USD");
    mmTotalByCurrency.merge(currency, h.getQuantity(), BigDecimal::add);
}
```

Then in the friday loop, compute value per currency group and convert:

```java
for (var friday : fridays) {
    var investmentValue = BigDecimal.ZERO;

    // Add regular holdings value, converted by currency
    for (var entry : holdingsByCurrency.entrySet()) {
        var currency = entry.getKey();
        var holdings = entry.getValue();
        var qtyBySymbol = new HashMap<String, BigDecimal>();
        for (var h : holdings) {
            qtyBySymbol.merge(h.getSymbol(), h.getQuantity(), BigDecimal::add);
        }
        var symbols = qtyBySymbol.keySet().stream()
                .filter(priceMap::containsKey)
                .sorted().toList();
        var currencyValue = computeInvestmentValue(friday, symbols, qtyBySymbol, priceMap, BigDecimal.ZERO);
        investmentValue = investmentValue.add(
                exchangeRateService.convertToUsd(currencyValue, currency, tenantId));
    }

    // Add money market totals, converted by currency
    for (var entry : mmTotalByCurrency.entrySet()) {
        investmentValue = investmentValue.add(
                exchangeRateService.convertToUsd(entry.getValue(), entry.getKey(), tenantId));
    }

    var propertyEquity = computePropertyEquity(friday, properties, valuationsByProperty);
    var totalValue = investmentValue.add(propertyEquity);
    dataPoints.add(new CombinedPortfolioDataPointDto(friday, totalValue,
            investmentValue, propertyEquity));
}
```

Remove the old `quantityBySymbol`, `moneyMarketTotal`, and the old `computeInvestmentValue()` call in the loop.

- [ ] **Step 5: Verify compilation**

Run: `cd backend && mvn compile -pl wealthview-core`
Expected: BUILD SUCCESS

- [ ] **Step 6: Run all core tests**

Run: `cd backend && mvn test -pl wealthview-core`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/DashboardService.java \
      backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionInputBuilder.java \
      backend/wealthview-core/src/main/java/com/wealthview/core/portfolio/TheoreticalPortfolioService.java \
      backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/CombinedPortfolioHistoryService.java
git commit -m "feat(core): convert non-USD values to USD at aggregation boundaries

DashboardService converts account balances before summing net worth.
ProjectionInputBuilder converts linked account balances for projections.
TheoreticalPortfolioService converts data points for non-USD accounts.
CombinedPortfolioHistoryService groups holdings by account currency
and converts each group to USD before summing across accounts."
```

---

### Task 7: Full Backend Test Pass

**Files:** No new files — verify everything compiles and tests pass together.

- [ ] **Step 1: Run full backend build with tests**

Run: `cd backend && mvn clean test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection`
Expected: All unit tests PASS

If any tests fail due to the AccountRequest 4th parameter or the new ExchangeRateService dependency being injected into AccountService (breaking tests that use `@InjectMocks`), fix those by adding the missing `@Mock ExchangeRateService` or updating `AccountRequest` constructors.

- [ ] **Step 2: Fix any compilation or test failures found**

Common issues:
- Other test classes constructing `AccountRequest` with 3 args — add `null` 4th arg
- Other services that construct `AccountService` manually — add `ExchangeRateService` param
- Controller tests that mock `AccountService` — may need updated `AccountResponse` assertions to include `currency`

- [ ] **Step 3: Commit any fixes**

```bash
git add -u
git commit -m "fix(core): update tests for AccountRequest currency parameter and ExchangeRateService dependency"
```

---

### Task 8: Frontend — Types, API Client, and formatCurrency

**Files:**
- Modify: `frontend/src/types/account.ts`
- Modify: `frontend/src/utils/format.ts`
- Create: `frontend/src/types/exchangeRate.ts`
- Create: `frontend/src/api/exchangeRates.ts`

- [ ] **Step 1: Update Account type and AccountRequest**

```typescript
export interface Account {
    id: string;
    name: string;
    type: string;
    institution: string | null;
    currency: string;
    balance: number;
    created_at: string;
}

export interface AccountRequest {
    name: string;
    type: string;
    institution?: string;
    currency?: string;
}
```

- [ ] **Step 2: Create ExchangeRate types**

```typescript
export interface ExchangeRate {
    currency_code: string;
    rate_to_usd: number;
    updated_at: string;
}

export interface ExchangeRateRequest {
    currency_code: string;
    rate_to_usd: number;
}
```

- [ ] **Step 3: Create exchangeRates API client**

```typescript
import client from './client';
import type { ExchangeRate, ExchangeRateRequest } from '../types/exchangeRate';

export async function listExchangeRates(): Promise<ExchangeRate[]> {
    const { data } = await client.get<ExchangeRate[]>('/exchange-rates');
    return data;
}

export async function createExchangeRate(request: ExchangeRateRequest): Promise<ExchangeRate> {
    const { data } = await client.post<ExchangeRate>('/exchange-rates', request);
    return data;
}

export async function updateExchangeRate(
    currencyCode: string,
    request: ExchangeRateRequest
): Promise<ExchangeRate> {
    const { data } = await client.put<ExchangeRate>(`/exchange-rates/${currencyCode}`, request);
    return data;
}

export async function deleteExchangeRate(currencyCode: string): Promise<void> {
    await client.delete(`/exchange-rates/${currencyCode}`);
}
```

- [ ] **Step 4: Update formatCurrency to accept optional currency code**

```typescript
export function formatCurrency(value: number, currency: string = 'USD'): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(value);
}
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/account.ts \
      frontend/src/types/exchangeRate.ts \
      frontend/src/api/exchangeRates.ts \
      frontend/src/utils/format.ts
git commit -m "feat(frontend): add exchange rate types, API client, and currency-aware formatCurrency

Account type now includes currency field. ExchangeRate type and API
client support CRUD operations. formatCurrency accepts optional
currency parameter (defaults to USD)."
```

---

### Task 9: Frontend — Account Form Currency Field

**Files:**
- Modify: `frontend/src/pages/AccountsListPage.tsx`

- [ ] **Step 1: Add currency state and form field**

In `AccountsListPage.tsx`:

Add `currency` state:

```typescript
const [currency, setCurrency] = useState('USD');
```

Update `resetForm()` to reset currency:

```typescript
function resetForm() {
    setName('');
    setType('brokerage');
    setInstitution('');
    setCurrency('USD');
    setEditingId(null);
    setShowForm(false);
}
```

Update `startEdit()` to populate currency:

```typescript
function startEdit(account: Account) {
    setName(account.name);
    setType(account.type);
    setInstitution(account.institution ?? '');
    setCurrency(account.currency ?? 'USD');
    setEditingId(account.id);
    setShowForm(true);
}
```

Update `handleSave()` to include currency:

```typescript
const request: AccountRequest = {
    name,
    type,
    institution: institution || undefined,
    currency: currency || 'USD',
};
```

- [ ] **Step 2: Add currency input to the form grid**

Change the form grid from `gridTemplateColumns: '1fr 1fr 1fr'` to `'1fr 1fr 1fr 1fr'` (4 columns), and add a currency text input after the institution input:

```tsx
<input
    placeholder="Currency (e.g. USD, EUR)"
    value={currency}
    onChange={(e) => setCurrency(e.target.value.toUpperCase())}
    maxLength={3}
    style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}
/>
```

- [ ] **Step 3: Show currency on account cards**

Update the balance display line (around line 111) to show the currency-appropriate symbol. Replace the hardcoded `$` prefix:

```tsx
<div style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: '1rem', color: '#1b5e20' }}>
    {formatCurrency(account.balance, account.currency)}
</div>
```

Add import for `formatCurrency` at the top:

```typescript
import { formatCurrency } from '../utils/format';
```

Also show a small currency badge next to the type badge if the account is non-USD:

```tsx
{account.currency !== 'USD' && (
    <span style={{
        padding: '0.2rem 0.6rem',
        background: '#fce4ec',
        color: '#c62828',
        borderRadius: '4px',
        fontSize: '0.75rem',
        fontWeight: 600,
        marginLeft: '0.5rem',
    }}>
        {account.currency}
    </span>
)}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/AccountsListPage.tsx
git commit -m "feat(frontend): add currency field to account form and display

Account form now includes a currency input (defaults to USD).
Account cards display balance formatted with the account's currency
and show a currency badge for non-USD accounts."
```

---

### Task 10: Frontend — Exchange Rates Admin Section

**Files:**
- Create: `frontend/src/components/admin/ExchangeRatesSection.tsx`
- Modify: `frontend/src/pages/AdminAreaPage.tsx`

- [ ] **Step 1: Create ExchangeRatesSection component**

```tsx
import { useState, useEffect } from 'react';
import { listExchangeRates, createExchangeRate, updateExchangeRate, deleteExchangeRate } from '../../api/exchangeRates';
import type { ExchangeRate } from '../../types/exchangeRate';
import { cardStyle } from '../../utils/styles';
import toast from 'react-hot-toast';

export default function ExchangeRatesSection() {
    const [rates, setRates] = useState<ExchangeRate[]>([]);
    const [loading, setLoading] = useState(true);
    const [editingCode, setEditingCode] = useState<string | null>(null);
    const [editRate, setEditRate] = useState('');
    const [showAdd, setShowAdd] = useState(false);
    const [newCode, setNewCode] = useState('');
    const [newRate, setNewRate] = useState('');
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        loadRates();
    }, []);

    async function loadRates() {
        setLoading(true);
        try {
            setRates(await listExchangeRates());
        } catch {
            toast.error('Failed to load exchange rates');
        } finally {
            setLoading(false);
        }
    }

    async function handleAdd() {
        if (!newCode || !newRate) return;
        setSaving(true);
        try {
            await createExchangeRate({
                currency_code: newCode.toUpperCase(),
                rate_to_usd: parseFloat(newRate),
            });
            toast.success(`${newCode.toUpperCase()} rate added`);
            setShowAdd(false);
            setNewCode('');
            setNewRate('');
            loadRates();
        } catch {
            toast.error('Failed to add exchange rate');
        } finally {
            setSaving(false);
        }
    }

    async function handleUpdate(currencyCode: string) {
        if (!editRate) return;
        setSaving(true);
        try {
            await updateExchangeRate(currencyCode, {
                currency_code: currencyCode,
                rate_to_usd: parseFloat(editRate),
            });
            toast.success(`${currencyCode} rate updated`);
            setEditingCode(null);
            loadRates();
        } catch {
            toast.error('Failed to update exchange rate');
        } finally {
            setSaving(false);
        }
    }

    async function handleDelete(currencyCode: string) {
        if (!confirm(`Delete ${currencyCode} exchange rate?`)) return;
        try {
            await deleteExchangeRate(currencyCode);
            toast.success(`${currencyCode} rate deleted`);
            loadRates();
        } catch {
            toast.error('Failed to delete — accounts may still use this currency');
        }
    }

    if (loading) return <div>Loading exchange rates...</div>;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Exchange Rates</h2>
                <button
                    onClick={() => setShowAdd(true)}
                    style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                >
                    Add Currency
                </button>
            </div>

            {showAdd && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Add Exchange Rate</h3>
                    <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                        <div>
                            <div style={{ fontSize: '0.75rem', color: '#999', marginBottom: '0.25rem' }}>Currency Code</div>
                            <input
                                placeholder="EUR"
                                value={newCode}
                                onChange={(e) => setNewCode(e.target.value.toUpperCase())}
                                maxLength={3}
                                style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '80px' }}
                            />
                        </div>
                        <div>
                            <div style={{ fontSize: '0.75rem', color: '#999', marginBottom: '0.25rem' }}>1 {newCode || '???'} = ? USD</div>
                            <input
                                placeholder="1.08"
                                value={newRate}
                                onChange={(e) => setNewRate(e.target.value)}
                                type="number"
                                step="0.0001"
                                style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '120px' }}
                            />
                        </div>
                        <div style={{ display: 'flex', gap: '0.5rem', alignSelf: 'flex-end' }}>
                            <button onClick={handleAdd} disabled={saving}
                                    style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                {saving ? '...' : 'Save'}
                            </button>
                            <button onClick={() => { setShowAdd(false); setNewCode(''); setNewRate(''); }}
                                    style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                Cancel
                            </button>
                        </div>
                    </div>
                </div>
            )}

            <div style={cardStyle}>
                {rates.length === 0 ? (
                    <div style={{ color: '#999' }}>No exchange rates configured. All accounts use USD.</div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #eee' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem', fontSize: '0.85rem', color: '#999' }}>Currency</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem', fontSize: '0.85rem', color: '#999' }}>Rate to USD</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem', fontSize: '0.85rem', color: '#999' }}>Last Updated</th>
                                <th style={{ padding: '0.5rem' }}></th>
                            </tr>
                        </thead>
                        <tbody>
                            {rates.map((rate) => (
                                <tr key={rate.currency_code} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem', fontWeight: 600 }}>{rate.currency_code}</td>
                                    <td style={{ padding: '0.5rem' }}>
                                        {editingCode === rate.currency_code ? (
                                            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                                <input
                                                    value={editRate}
                                                    onChange={(e) => setEditRate(e.target.value)}
                                                    type="number"
                                                    step="0.0001"
                                                    style={{ padding: '0.4rem', border: '1px solid #ccc', borderRadius: '4px', width: '120px' }}
                                                    autoFocus
                                                />
                                                <button onClick={() => handleUpdate(rate.currency_code)} disabled={saving}
                                                        style={{ padding: '0.4rem 0.8rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                                                    {saving ? '...' : 'Save'}
                                                </button>
                                                <button onClick={() => setEditingCode(null)}
                                                        style={{ padding: '0.4rem 0.8rem', background: '#fff', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                                                    Cancel
                                                </button>
                                            </div>
                                        ) : (
                                            <span style={{ fontFamily: 'monospace' }}>
                                                1 {rate.currency_code} = {rate.rate_to_usd} USD
                                            </span>
                                        )}
                                    </td>
                                    <td style={{ padding: '0.5rem', color: '#999', fontSize: '0.85rem' }}>
                                        {new Date(rate.updated_at).toLocaleDateString()}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                        {editingCode !== rate.currency_code && (
                                            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                                                <button onClick={() => { setEditingCode(rate.currency_code); setEditRate(String(rate.rate_to_usd)); }}
                                                        style={{ padding: '0.3rem 0.6rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>
                                                    Edit
                                                </button>
                                                <button onClick={() => handleDelete(rate.currency_code)}
                                                        style={{ padding: '0.3rem 0.6rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>
                                                    Delete
                                                </button>
                                            </div>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}
```

- [ ] **Step 2: Add Exchange Rates section to AdminAreaPage**

In `AdminAreaPage.tsx`:

Add import:

```typescript
import ExchangeRatesSection from '../components/admin/ExchangeRatesSection';
```

Update `AdminSection` type:

```typescript
type AdminSection = 'dashboard' | 'users' | 'tenants' | 'prices' | 'exchange-rates' | 'invite-codes' | 'system-config' | 'audit-log';
```

Add to `sidebarItems` array (after `'prices'`):

```typescript
{ key: 'exchange-rates', label: 'Exchange Rates' },
```

Add case to `renderSection()`:

```typescript
case 'exchange-rates': return <ExchangeRatesSection />;
```

- [ ] **Step 3: Verify frontend builds**

Run: `cd frontend && npm run build`
Expected: BUILD SUCCESS with no type errors

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/admin/ExchangeRatesSection.tsx \
      frontend/src/pages/AdminAreaPage.tsx
git commit -m "feat(frontend): add Exchange Rates admin section

New ExchangeRatesSection component with add/edit/delete for exchange
rates, integrated into AdminAreaPage sidebar. Shows rate table with
inline editing, available to all authenticated users."
```

---

### Task 11: Integration Tests

**Files:**
- Create: `backend/wealthview-app/src/test/java/com/wealthview/app/it/exchangerate/ExchangeRateControllerIT.java`
- Modify: `backend/wealthview-app/src/test/java/com/wealthview/app/it/testutil/TestDataHelper.java`

- [ ] **Step 1: Add exchange rate helper to TestDataHelper**

```java
// ── Exchange Rates ──────────────────────────────────────────────────
public void createExchangeRate(String currencyCode, double rateToUsd) {
    var body = Map.of("currency_code", currencyCode, "rate_to_usd", rateToUsd);
    restTemplate.exchange("/api/v1/exchange-rates",
            HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
}

public String createAccountWithCurrencyAndGetId(String name, String type, String currency) {
    var body = Map.of("name", name, "type", type, "currency", currency);
    var response = restTemplate.exchange("/api/v1/accounts",
            HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
    return (String) response.getBody().get("id");
}
```

- [ ] **Step 2: Write integration tests**

```java
package com.wealthview.app.it.exchangerate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeRateControllerIT extends AbstractApiIntegrationTest {

    @Test
    void create_validRate_returns201() {
        var body = Map.of("currency_code", "EUR", "rate_to_usd", 1.08);
        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("currency_code")).isEqualTo("EUR");
    }

    @Test
    void create_duplicateCurrency_returns409() {
        data.createExchangeRate("GBP", 1.27);

        var body = Map.of("currency_code", "GBP", "rate_to_usd", 1.30);
        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void create_usdCurrency_returns400() {
        var body = Map.of("currency_code", "USD", "rate_to_usd", 1.0);
        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_afterCreating_returnsRates() {
        data.createExchangeRate("EUR", 1.08);
        data.createExchangeRate("GBP", 1.27);

        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void update_existingRate_returns200() {
        data.createExchangeRate("EUR", 1.08);

        var body = Map.of("currency_code", "EUR", "rate_to_usd", 1.12);
        var response = restTemplate.exchange("/api/v1/exchange-rates/EUR",
                HttpMethod.PUT, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("rate_to_usd")).doubleValue()).isEqualTo(1.12);
    }

    @Test
    void delete_noAccountsUsingCurrency_returns204() {
        data.createExchangeRate("EUR", 1.08);

        var response = restTemplate.exchange("/api/v1/exchange-rates/EUR",
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void delete_accountsUsingCurrency_returns409() {
        data.createExchangeRate("EUR", 1.08);
        data.createAccountWithCurrencyAndGetId("Euro Account", "brokerage", "EUR");

        var response = restTemplate.exchange("/api/v1/exchange-rates/EUR",
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void accountBalance_eurAccount_convertedToUsd() {
        data.createExchangeRate("EUR", 1.08);
        var accountId = data.createAccountWithCurrencyAndGetId("Euro Brokerage", "bank", "EUR");

        // Get account — balance should be 0 in USD (no transactions)
        var response = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("currency")).isEqualTo("EUR");
    }
}
```

- [ ] **Step 3: Add exchange_rates to DatabaseCleaner**

Check `DatabaseCleaner` and add `exchange_rates` to the list of tables to truncate (before `accounts` since it has no foreign key dependencies on accounts).

- [ ] **Step 4: Run integration tests**

Run: `cd backend && mvn verify -pl wealthview-app -Dtest=ExchangeRateControllerIT -Dfailsafe.includes="**/ExchangeRateControllerIT.java"`
Expected: All 8 tests PASS

- [ ] **Step 5: Run all integration tests to ensure no regressions**

Run: `cd backend && mvn verify -pl wealthview-app`
Expected: All IT tests PASS (existing + new)

- [ ] **Step 6: Commit**

```bash
git add backend/wealthview-app/src/test/java/com/wealthview/app/it/exchangerate/ \
      backend/wealthview-app/src/test/java/com/wealthview/app/it/testutil/TestDataHelper.java
git commit -m "test(app): add ExchangeRateController integration tests

8 IT tests covering create, duplicate, USD rejection, list, update,
delete, delete-with-accounts guard, and account-with-currency.
Adds exchange rate helpers to TestDataHelper."
```

---

### Task 12: End-to-End Verification

- [ ] **Step 1: Rebuild and launch with Docker Compose**

```bash
docker compose down
docker compose up --build -d
```

- [ ] **Step 2: Verify the app starts and migrations apply**

```bash
docker compose logs -f app
```

Look for Flyway migration messages for V053 and V054.

- [ ] **Step 3: Manual smoke test**

1. Log in at http://localhost:80
2. Go to Admin → Exchange Rates → Add EUR with rate 1.08
3. Go to Accounts → Create new account with currency EUR
4. Verify the account card shows EUR formatting and currency badge
5. Verify dashboard net worth includes the converted EUR account value

- [ ] **Step 4: Commit any final fixes**

If smoke testing reveals issues, fix and commit.
