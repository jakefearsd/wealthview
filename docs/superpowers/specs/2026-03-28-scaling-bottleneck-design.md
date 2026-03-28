# Scaling Bottleneck Fixes — Design Spec

## Problem

The dashboard page load triggers 15-50+ database queries due to N+1 patterns in
`DashboardService`, `CombinedPortfolioHistoryService`, and
`SnapshotProjectionService`. There is zero caching anywhere in the application,
and the HikariCP connection pool uses default sizing (10). Together, these
limitations cap the system at roughly 6-7 concurrent users.

## Solution

Three complementary fixes: batch dashboard queries (reduce per-request DB cost),
add Caffeine in-process caching (eliminate repeated DB work), and tune the
connection pool (increase concurrent capacity).

## 1. Batch Dashboard Queries

### Current State

`DashboardService.getSummary()` calls `accountService.computeBalance()` in a loop
for every account. Each call issues 1-3 DB queries depending on account type
(bank vs investment). With 10 accounts, this is 10-30 queries for one HTTP request.

### Fix

Add `AccountService.computeAllBalances(UUID tenantId)` returning
`Map<UUID, BigDecimal>` (account ID to balance). This method:

1. Loads all accounts for tenant — 1 query
2. Loads all holdings for tenant — 1 query
3. Fetches latest prices for all distinct symbols in one batch — 1 query
4. Computes all bank balances via a new
   `TransactionRepository.computeBalancesByTenantId(UUID)` — 1 query returning
   account_id + sum pairs
5. Assembles the map in-memory: bank accounts get their transaction sums,
   investment accounts get holdings x prices (with cost basis fallback)
6. Converts non-USD balances to USD via `ExchangeRateService`

New repository method:

```sql
SELECT account_id, COALESCE(SUM(amount), 0) AS balance
FROM transactions
WHERE tenant_id = :tenantId
  AND account_id IN (:bankAccountIds)
GROUP BY account_id
```

### Callers Updated

- `DashboardService.getSummary()` — use `computeAllBalances()` instead of per-account loop
- `CombinedPortfolioHistoryService` — already loads holdings in bulk, but needs
  the currency conversion grouped by account currency (done in the multi-currency
  work). No further changes needed.
- `SnapshotProjectionService` — use `computeAllBalances()` for the bank account
  balance portion

### Result

Dashboard summary: 15-50 queries → 4 queries, regardless of account count.

## 2. Caffeine Cache Layer

### Dependencies

Add to parent POM:
- `spring-boot-starter-cache`
- `com.github.ben-manes.caffeine:caffeine`

### Configuration

`@EnableCaching` on the application config. A `CacheConfig` class defining named
caches with per-cache TTL and max size via `CaffeineCacheManager`.

### Caches

| Cache Name | Key | TTL | Max Size | Invalidation |
|------------|-----|-----|----------|-------------|
| `accountBalances` | `tenantId` | 5 min | 200 | Transaction create/delete, import complete, holdings recompute, exchange rate CRUD |
| `latestPrices` | `symbol` | 10 min | 500 | Price sync, manual price entry |
| `exchangeRates` | `tenantId` | 30 min | 200 | Exchange rate create/update/delete |
| `taxBrackets` | `year` | 24 hours | 10 | None (immutable seed data) |
| `standardDeductions` | `year` | 24 hours | 10 | None (immutable seed data) |

### Cache Annotations

**`@Cacheable`** on read methods:
- `AccountService.computeAllBalances(tenantId)` — `accountBalances` cache
- `AccountService.computeAllBalances()` already batch-fetches latest prices
  internally, so price caching applies there via the `accountBalances` cache.
  For standalone price lookups (e.g., portfolio history), add a
  `PriceService.getLatestPrice(symbol)` wrapper method cached in `latestPrices`
- `ExchangeRateService.list(tenantId)` — `exchangeRates` cache
- `FederalTaxCalculator` bracket/deduction lookups — `taxBrackets` and
  `standardDeductions` caches

**`@CacheEvict`** on mutation methods:
- `TransactionService` create/delete → evict `accountBalances` for tenant
- `ImportService` on import completion → evict `accountBalances` for tenant
- `HoldingsComputationService.recompute()` → evict `accountBalances` for tenant
- `ExchangeRateService` create/update/delete → evict `exchangeRates` and
  `accountBalances` for tenant
- `PriceSyncService` after sync → evict `latestPrices` (all entries or by symbol)

### What NOT to Cache

- Portfolio history data points (too large, changes with price updates)
- Projection results (already persisted as `params_json`)
- Individual entity lookups (low value, invalidation complexity)

## 3. Connection Pool Tuning

Add explicit HikariCP configuration to `application.yml` (both default and docker
profiles):

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      idle-timeout: 300000
      max-lifetime: 600000
```

- **20 max connections** — conservative against PostgreSQL default of 100
- **5 minimum idle** — warm connections without waste
- **10s connection timeout** — fail fast over 30s default queue
- **5 min idle, 10 min max lifetime** — prevent stale connections

## Cache Invalidation Paths

Every path that changes account balance data must evict the
`accountBalances` cache:

| Mutation | Service Method | Eviction |
|----------|---------------|----------|
| Create transaction | `TransactionService.create()` | `accountBalances` by tenant |
| Delete transaction | `TransactionService.delete()` | `accountBalances` by tenant |
| Import complete | `ImportService` completion path | `accountBalances` by tenant |
| Holdings recompute | `HoldingsComputationService.recompute()` | `accountBalances` by tenant |
| Exchange rate CRUD | `ExchangeRateService.create/update/delete()` | `accountBalances` + `exchangeRates` by tenant |
| Manual price entry | `PriceService.create()` | `latestPrices` by symbol |
| Price sync | `PriceSyncService.syncDailyPrices()` | `latestPrices` (all) |

## Testing Strategy

- Unit tests for `computeAllBalances()`: mixed account types, multiple currencies,
  holdings with/without prices, empty accounts
- Verify `@CacheEvict` annotations are on the correct methods (integration test
  or manual review)
- Existing tests unchanged — caching is transparent via Spring AOP
- Connection pool: no tests, verify app starts cleanly

## Expected Impact

| Metric | Before | After |
|--------|--------|-------|
| Dashboard summary queries | 15-50 | 4 (cache miss) or 0 (cache hit) |
| Concurrent user ceiling | ~7 | ~20+ |
| Repeated dashboard load | Full DB cost | Served from cache |
| Price/bracket lookups | DB every time | Cached with TTL |
