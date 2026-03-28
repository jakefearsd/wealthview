# Scaling Bottleneck Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce dashboard page load from 15-50+ DB queries to 4 (cache miss) or 0 (cache hit), and raise the concurrent user ceiling from ~7 to ~20+.

**Architecture:** Three independent layers — (1) batch `computeAllBalances()` replaces per-account query loops, (2) Caffeine in-process caching with event-driven eviction for balances/prices/rates/brackets, (3) HikariCP pool tuning. Each layer is independently valuable and testable.

**Tech Stack:** Java 21 / Spring Boot 3.3 / Spring Cache / Caffeine / HikariCP / PostgreSQL

**Spec:** `docs/superpowers/specs/2026-03-28-scaling-bottleneck-design.md`

---

### Task 1: Connection Pool Tuning

Config-only change. Immediate impact, zero risk.

**Files:**
- Modify: `backend/wealthview-app/src/main/resources/application.yml`

- [ ] **Step 1: Add HikariCP configuration**

In `application.yml`, add under the existing `spring.datasource` block (after `driver-class-name`):

```yaml
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      idle-timeout: 300000
      max-lifetime: 600000
```

The full `spring.datasource` section should read:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/wealthview
    username: wv_app
    password: ${DB_PASSWORD:wv_dev_pass}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      idle-timeout: 300000
      max-lifetime: 600000
```

- [ ] **Step 2: Verify app compiles**

Run: `cd backend && mvn compile -pl wealthview-app`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/wealthview-app/src/main/resources/application.yml
git commit -m "chore(app): tune HikariCP connection pool

Set max pool size to 20 (up from default 10), minimum idle to 5,
connection timeout to 10s, idle timeout to 5 min, max lifetime to
10 min. Raises concurrent user capacity without code changes."
```

---

### Task 2: Bulk Balance Query in TransactionRepository

Add a repository method that computes bank balances for all accounts in one SQL query.

**Files:**
- Modify: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/TransactionRepository.java`

- [ ] **Step 1: Add computeBalancesByTenantId method**

Add this method to `TransactionRepository.java` after the existing `computeBalance` method (line 46):

```java
@Query("""
        SELECT t.account.id, COALESCE(
            SUM(CASE WHEN t.type = 'deposit' THEN t.amount ELSE -t.amount END),
            0)
        FROM TransactionEntity t
        WHERE t.tenant.id = :tenantId
        AND t.account.id IN :accountIds
        GROUP BY t.account.id
        """)
List<Object[]> computeBalancesByAccountIds(@Param("tenantId") UUID tenantId,
                                            @Param("accountIds") List<UUID> accountIds);
```

This returns `List<Object[]>` where each element is `[UUID accountId, BigDecimal balance]`. The caller will convert this to a map.

- [ ] **Step 2: Verify compilation**

Run: `cd backend && mvn compile -pl wealthview-persistence`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/TransactionRepository.java
git commit -m "feat(persistence): add bulk bank balance query to TransactionRepository

computeBalancesByAccountIds returns deposit-minus-withdrawal sums
for all specified accounts in a single SQL query, replacing the
per-account computeBalance calls in dashboard aggregation."
```

---

### Task 3: AccountService.computeAllBalances() with Tests (TDD)

Add a method that computes all account balances for a tenant in 4 queries total.

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/account/AccountService.java`
- Modify: `backend/wealthview-core/src/test/java/com/wealthview/core/account/AccountServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add these tests to `AccountServiceTest.java`:

```java
@Test
void computeAllBalances_mixedAccountTypes_returnsAllBalances() {
    var bankAccount = new AccountEntity(tenant, "Checking", "bank", "Chase");
    var brokerageAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
    when(accountRepository.findByTenant_Id(tenantId))
            .thenReturn(List.of(bankAccount, brokerageAccount));

    // Bank balance via bulk query
    when(transactionRepository.computeBalancesByAccountIds(eq(tenantId), any()))
            .thenReturn(List.of(new Object[]{bankAccount.getId(), new BigDecimal("5000.00")}));

    // Investment holdings and prices
    var holding = new HoldingEntity(brokerageAccount, tenant, "AAPL",
            new BigDecimal("10"), new BigDecimal("1500.00"));
    when(holdingRepository.findByTenant_Id(tenantId))
            .thenReturn(List.of(holding));
    var price = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
    when(priceRepository.findLatestBySymbolIn(List.of("AAPL")))
            .thenReturn(List.of(price));

    var result = accountService.computeAllBalances(tenantId);

    assertThat(result).hasSize(2);
    assertThat(result.get(bankAccount.getId())).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(result.get(brokerageAccount.getId())).isEqualByComparingTo(new BigDecimal("2000.00"));
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
    when(accountRepository.findByTenant_Id(tenantId))
            .thenReturn(List.of(account));
    when(transactionRepository.computeBalancesByAccountIds(eq(tenantId), any()))
            .thenReturn(List.of());

    var holding = new HoldingEntity(account, tenant, "XYZ",
            new BigDecimal("10"), new BigDecimal("1500.00"));
    when(holdingRepository.findByTenant_Id(tenantId))
            .thenReturn(List.of(holding));
    when(priceRepository.findLatestBySymbolIn(List.of("XYZ")))
            .thenReturn(List.of());

    var result = accountService.computeAllBalances(tenantId);

    assertThat(result.get(account.getId())).isEqualByComparingTo(new BigDecimal("1500.00"));
}
```

Add import for `List` if not already present.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="AccountServiceTest#computeAllBalances*"`
Expected: FAIL — method does not exist

- [ ] **Step 3: Implement computeAllBalances**

Add this method to `AccountService.java`:

```java
@Transactional(readOnly = true)
public Map<UUID, BigDecimal> computeAllBalances(UUID tenantId) {
    var accounts = accountRepository.findByTenant_Id(tenantId);
    if (accounts.isEmpty()) {
        return Map.of();
    }

    var bankAccountIds = accounts.stream()
            .filter(a -> "bank".equals(a.getType()))
            .map(AccountEntity::getId)
            .toList();

    // Bulk bank balances — 1 query
    var bankBalances = new HashMap<UUID, BigDecimal>();
    if (!bankAccountIds.isEmpty()) {
        for (var row : transactionRepository.computeBalancesByAccountIds(tenantId, bankAccountIds)) {
            bankBalances.put((UUID) row[0], (BigDecimal) row[1]);
        }
    }

    // All holdings for tenant — 1 query
    var allHoldings = holdingRepository.findByTenant_Id(tenantId);

    // Group holdings by account
    var holdingsByAccount = allHoldings.stream()
            .collect(Collectors.groupingBy(HoldingEntity::getAccountId));

    // All distinct symbols — 1 query for latest prices
    var allSymbols = allHoldings.stream()
            .map(HoldingEntity::getSymbol)
            .distinct()
            .toList();

    var latestPrices = allSymbols.isEmpty()
            ? Map.<String, BigDecimal>of()
            : priceRepository.findLatestBySymbolIn(allSymbols).stream()
                    .collect(Collectors.toMap(PriceEntity::getSymbol, PriceEntity::getClosePrice));

    // Compute per-account balances
    var result = new HashMap<UUID, BigDecimal>();
    for (var account : accounts) {
        if ("bank".equals(account.getType())) {
            result.put(account.getId(), bankBalances.getOrDefault(account.getId(), BigDecimal.ZERO));
        } else {
            var holdings = holdingsByAccount.getOrDefault(account.getId(), List.of());
            var value = BigDecimal.ZERO;
            for (var holding : holdings) {
                var price = latestPrices.get(holding.getSymbol());
                if (price != null) {
                    value = value.add(holding.getQuantity().multiply(price)
                            .setScale(4, RoundingMode.HALF_UP));
                } else {
                    value = value.add(holding.getCostBasis());
                }
            }
            result.put(account.getId(), value);
        }
    }
    return result;
}
```

Add imports:

```java
import java.util.HashMap;
import java.util.List;
```

(Check if `HashMap` and `List` are already imported — `List` likely is via `java.util.List` from holdings. `HashMap` may not be.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=AccountServiceTest`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/account/AccountService.java \
      backend/wealthview-core/src/test/java/com/wealthview/core/account/AccountServiceTest.java
git commit -m "feat(core): add computeAllBalances for batch balance computation

Computes all account balances for a tenant in 4 queries total:
1 for accounts, 1 bulk bank balance sum, 1 for all holdings,
1 for all latest prices. Replaces the per-account loop pattern
that issued 1-3 queries per account."
```

---

### Task 4: Update DashboardService to Use Batch Balances

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/DashboardService.java`
- Modify: `backend/wealthview-core/src/test/java/com/wealthview/core/dashboard/DashboardServiceTest.java`

- [ ] **Step 1: Update DashboardService.getSummary()**

Replace the per-account loop that calls `accountService.computeBalance()` with `computeAllBalances()`.

Replace lines 52-73 of `DashboardService.java`:

```java
var accounts = accountRepository.findByTenant_Id(tenantId, Pageable.unpaged());
var balances = accountService.computeAllBalances(tenantId);

var totalInvestments = BigDecimal.ZERO;
var totalCash = BigDecimal.ZERO;
var accountSummaries = new ArrayList<AccountSummary>();
var allocationMap = new HashMap<String, BigDecimal>();

for (var account : accounts) {
    var nativeBalance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
    var accountBalanceUsd = exchangeRateService.convertToUsd(
            nativeBalance, account.getCurrency(), tenantId);

    if ("bank".equals(account.getType())) {
        totalCash = totalCash.add(accountBalanceUsd);
    } else {
        totalInvestments = totalInvestments.add(accountBalanceUsd);
    }

    accountSummaries.add(new AccountSummary(
            account.getName(), account.getType(), accountBalanceUsd));
    allocationMap.merge(account.getType(), accountBalanceUsd, BigDecimal::add);
}
```

- [ ] **Step 2: Update DashboardServiceTest**

Read the existing `DashboardServiceTest` and update it. The key change: instead of mocking `accountService.computeBalance()` per account, mock `accountService.computeAllBalances()` to return a map. For example:

```java
when(accountService.computeAllBalances(tenantId))
        .thenReturn(Map.of(accountId1, new BigDecimal("10000.00"),
                           accountId2, new BigDecimal("5000.00")));
```

Remove any per-account `computeBalance()` mocking and replace with the bulk map.

- [ ] **Step 3: Run tests**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=DashboardServiceTest`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/DashboardService.java \
      backend/wealthview-core/src/test/java/com/wealthview/core/dashboard/DashboardServiceTest.java
git commit -m "refactor(core): DashboardService uses batch balance computation

Replaces per-account computeBalance() loop with a single
computeAllBalances() call, reducing dashboard summary from
N+1 queries to 4 total queries regardless of account count."
```

---

### Task 5: Update SnapshotProjectionService to Use Batch Balances

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/SnapshotProjectionService.java`

- [ ] **Step 1: Add AccountService.computeAllBalances dependency and use it for bank accounts**

In `SnapshotProjectionService.computeProjection()`, the loop at lines 65-73 calls `accountService.computeBalance()` for bank accounts. Replace this with a pre-computed balances map.

Before the loop, add:

```java
var balances = accountService.computeAllBalances(tenantId);
```

Then replace the bank account branch (line 67):

```java
if ("bank".equals(account.getType())) {
    var balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
    accountProjections.add(new AccountProjection(balance, BigDecimal.ZERO));
}
```

The investment account branch (line 70) still calls `computeInvestmentProjection()` which uses `TheoreticalPortfolioService` for historical CAGR — this is different from balance computation and should stay as-is.

- [ ] **Step 2: Run tests**

Run: `cd backend && mvn test -pl wealthview-core`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/dashboard/SnapshotProjectionService.java
git commit -m "refactor(core): SnapshotProjectionService uses batch bank balances

Bank account balances now come from computeAllBalances() instead of
per-account computeBalance() calls. Investment accounts still use
TheoreticalPortfolioService for CAGR-based projections."
```

---

### Task 6: Add Caffeine Cache Dependencies and Configuration

**Files:**
- Modify: `backend/pom.xml` (parent POM)
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/config/CacheConfig.java`
- Modify: `backend/wealthview-app/src/main/java/com/wealthview/app/WealthViewApplication.java`

- [ ] **Step 1: Add Caffeine dependency to parent POM**

In the parent `pom.xml`, add to the `<dependencyManagement><dependencies>` section:

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>
```

Then add both dependencies to the `wealthview-core` module's `pom.xml` (under `<dependencies>`):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

- [ ] **Step 2: Create CacheConfig**

```java
package com.wealthview.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                buildCache("accountBalances", 5, TimeUnit.MINUTES, 200),
                buildCache("latestPrices", 10, TimeUnit.MINUTES, 500),
                buildCache("exchangeRates", 30, TimeUnit.MINUTES, 200),
                buildCache("taxBrackets", 24, TimeUnit.HOURS, 10),
                buildCache("standardDeductions", 24, TimeUnit.HOURS, 10)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, long duration, TimeUnit unit, int maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(duration, unit)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
```

- [ ] **Step 3: Remove @EnableCaching from WealthViewApplication if present (it shouldn't be) — verify**

Check `WealthViewApplication.java`. It should NOT have `@EnableCaching` — that's in `CacheConfig` now.

- [ ] **Step 4: Verify compilation**

Run: `cd backend && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml \
      backend/wealthview-core/pom.xml \
      backend/wealthview-core/src/main/java/com/wealthview/core/config/CacheConfig.java
git commit -m "feat(core): add Caffeine cache infrastructure

Adds spring-boot-starter-cache and Caffeine dependencies.
CacheConfig defines 5 named caches with per-cache TTL and max size:
accountBalances (5m), latestPrices (10m), exchangeRates (30m),
taxBrackets (24h), standardDeductions (24h)."
```

---

### Task 7: Add @Cacheable and @CacheEvict Annotations

Apply cache annotations to the appropriate service methods.

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/account/AccountService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/exchangerate/ExchangeRateService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/transaction/TransactionService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/holding/HoldingsComputationService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/importservice/ImportService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/price/PriceService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/pricefeed/PriceSyncService.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/FederalTaxCalculator.java`

- [ ] **Step 1: @Cacheable on AccountService.computeAllBalances()**

```java
@Cacheable(value = "accountBalances", key = "#tenantId")
@Transactional(readOnly = true)
public Map<UUID, BigDecimal> computeAllBalances(UUID tenantId) {
```

Add import: `import org.springframework.cache.annotation.Cacheable;`

- [ ] **Step 2: @Cacheable on ExchangeRateService.list()**

```java
@Cacheable(value = "exchangeRates", key = "#tenantId")
@Transactional(readOnly = true)
public List<ExchangeRateResponse> list(UUID tenantId) {
```

Add import: `import org.springframework.cache.annotation.Cacheable;`

- [ ] **Step 3: @CacheEvict on ExchangeRateService mutation methods**

On `create()`, `update()`, and `delete()`, add:

```java
@CacheEvict(value = {"exchangeRates", "accountBalances"}, key = "#tenantId")
```

Add import: `import org.springframework.cache.annotation.CacheEvict;`

- [ ] **Step 4: @CacheEvict on TransactionService.create() and delete()**

The `tenantId` is available as a method parameter. Add to both `create()` and `delete()`:

```java
@CacheEvict(value = "accountBalances", key = "#tenantId")
```

Also add to `createWithHash()` and `createWithHashNoRecompute()` if they take `tenantId`.

Check `TransactionService` — `create()` takes `(UUID tenantId, UUID accountId, TransactionRequest request)` so `#tenantId` works. `delete()` takes `(UUID tenantId, UUID transactionId)` so `#tenantId` works.

For `createWithHashNoRecompute()` — check if it takes `tenantId`. If it receives the account entity instead, you'll need to extract tenantId. Read the method signature and adapt.

Add import: `import org.springframework.cache.annotation.CacheEvict;`

- [ ] **Step 5: @CacheEvict on HoldingsComputationService.recomputeForAccountAndSymbol()**

This method takes `(AccountEntity account, TenantEntity tenant, String symbol)`. The tenant ID is `tenant.getId()`. Add:

```java
@CacheEvict(value = "accountBalances", key = "#tenant.id")
```

Add import: `import org.springframework.cache.annotation.CacheEvict;`

- [ ] **Step 6: @CacheEvict on ImportService completion**

Find where `ImportService` finalizes a successful import. The `processImport()` method calls `finalizeJob()`. Add `@CacheEvict` to `processImport()` or to the method that calls the recompute loop. Since `processImport()` already takes `tenantId` indirectly (via the import job entity), the most reliable approach is to add an explicit eviction call.

If `processImport()` doesn't directly take tenantId as a parameter, inject `CacheManager` and manually evict:

```java
@Autowired  // Only use this for CacheManager injection where annotation doesn't work
private CacheManager cacheManager;

// In processImport(), after recomputing holdings:
var cache = cacheManager.getCache("accountBalances");
if (cache != null) {
    cache.evict(tenantId);
}
```

Wait — the project uses constructor injection only. Instead, add `CacheManager` as a constructor parameter to `ImportService` and call `evict()` manually after the import completes.

- [ ] **Step 7: @CacheEvict on PriceService and PriceSyncService**

On `PriceService.createPrice()` — evict `latestPrices` for the specific symbol. Since `latestPrices` is keyed by symbol:

```java
@CacheEvict(value = "latestPrices", key = "#symbol")
```

On `PriceSyncService.syncDailyPrices()` — evict all `latestPrices` entries:

```java
@CacheEvict(value = "latestPrices", allEntries = true)
```

- [ ] **Step 8: Replace FederalTaxCalculator HashMap cache with Spring @Cacheable**

The FederalTaxCalculator already has a manual `HashMap` cache. Replace it with Spring `@Cacheable`.

Remove the `bracketCache` and `deductionCache` fields. Add `@Cacheable` to `loadBracketsWithFallback()` and `loadStandardDeduction()`:

```java
@Cacheable(value = "taxBrackets", key = "#taxYear + '-' + #status.value()")
public List<TaxBracketEntity> loadBracketsWithFallback(int taxYear, FilingStatus status) {
```

```java
@Cacheable(value = "standardDeductions", key = "#taxYear + '-' + #status.value()")
public BigDecimal loadStandardDeduction(int taxYear, FilingStatus status) {
```

**Important:** These methods must be `public` for Spring AOP to intercept them. Currently they may be `private` — change visibility to `public` if needed.

Also remove the manual `computeIfAbsent` caching logic from these methods since Spring handles it now.

- [ ] **Step 9: Run all tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-api,wealthview-projection`
Expected: All tests PASS

If tests fail because of caching AOP not intercepting in unit tests (Mockito), that's expected — `@Cacheable` is a Spring AOP concern and won't affect unit tests using `@InjectMocks`. Only integration tests see caching.

- [ ] **Step 10: Commit**

```bash
git add -u backend/
git commit -m "feat(core): add @Cacheable and @CacheEvict annotations

accountBalances cached on computeAllBalances(), evicted on transaction
create/delete, holdings recompute, import completion, exchange rate CRUD.
exchangeRates cached on list(), evicted on create/update/delete.
latestPrices evicted on price creation and daily sync.
taxBrackets and standardDeductions use Spring cache instead of manual
HashMap cache in FederalTaxCalculator."
```

---

### Task 8: Full Test Pass and Verification

**Files:** No new files — verify everything works together.

- [ ] **Step 1: Run all unit tests**

Run: `cd backend && mvn clean test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection`
Expected: All tests PASS

- [ ] **Step 2: Run integration tests**

Run: `cd backend && mvn verify -pl wealthview-app -DskipTests`
Expected: All IT tests PASS

- [ ] **Step 3: Fix any failures**

Common issues:
- `@CacheEvict` on methods where the SpEL key expression references a parameter name that doesn't match (use `@Param` or check parameter names)
- `FederalTaxCalculator` methods changed from private to public — existing tests calling them directly should still work
- `ImportService` needing `CacheManager` constructor parameter — add to test mocks

- [ ] **Step 4: Commit any fixes**

```bash
git add -u backend/
git commit -m "fix(core): resolve test failures from caching integration"
```

---

### Task 9: Docker Compose Rebuild and Smoke Test

- [ ] **Step 1: Rebuild and launch**

```bash
docker compose down
docker compose up --build -d
```

- [ ] **Step 2: Verify app starts and migrations apply**

```bash
docker compose logs -f app
```

Look for: Flyway migrations, "Started WealthViewApplication", no errors.

- [ ] **Step 3: Manual smoke test**

1. Log in at http://localhost:80
2. Load dashboard — verify net worth, allocation, portfolio chart all display correctly
3. Go to Admin → Exchange Rates — verify rates load
4. Create/edit a transaction — verify dashboard updates on reload (cache eviction works)
5. Check response times in browser network tab — should be noticeably faster on second load

- [ ] **Step 4: Commit any final fixes**

If smoke testing reveals issues, fix and commit.
