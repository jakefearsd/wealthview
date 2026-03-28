# Multi-Currency Account Support — Design Spec

## Problem

WealthView assumes all accounts are in USD. Users holding accounts in other
currencies (e.g., EUR) have no way to represent this. Dashboard totals, portfolio
history, and projections aggregate raw values across accounts without any currency
awareness.

## Solution

Per-account `currency` field + tenant-scoped exchange rates table with manual,
single-rate entries. Conversion happens at display/aggregation time only — accounts
store all values (transactions, holdings, cost basis) in their native currency.

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Conversion timing | Display/aggregation time | Preserves native values; rate updates don't require reprocessing |
| Exchange rate source | Manual, user-entered | Simplest; no external API dependency |
| Rate model | Single rate per currency (not dated) | Keeps it simple; historical accuracy not needed now |
| Base currency | USD (hardcoded) | Only need to support user's use case; configurable later |
| Rate storage | Separate `exchange_rates` table | Avoids per-account rate duplication; single update point |

## Database Changes

### Migration: Add `currency` to `accounts`

```sql
ALTER TABLE accounts ADD COLUMN currency text NOT NULL DEFAULT 'USD';
```

All existing accounts get `'USD'`. New accounts specify an ISO 4217 code.

### Migration: Create `exchange_rates` table

```sql
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

One row per non-USD currency per tenant. `rate_to_usd` means "1 EUR = X USD" (e.g.,
`1.08`). Scale of 8 for precision with small-value currencies.

## Backend: Entity & Service Layer

### Persistence

**ExchangeRateEntity** — maps to `exchange_rates`. Fields: `id`, `tenantId`,
`currencyCode`, `rateToUsd`, `createdAt`, `updatedAt`.

**ExchangeRateRepository** — `findByTenantIdAndCurrencyCode(UUID, String)`,
`findAllByTenantId(UUID)`, `countByTenantIdAndCurrencyCode(UUID, String)`.

**AccountEntity** — add `currency` field (String, defaults to `"USD"`).

### Core

**ExchangeRateService** — thin CRUD + conversion:
- `create(tenantId, currencyCode, rateToUsd)`
- `update(tenantId, currencyCode, rateToUsd)`
- `delete(tenantId, currencyCode)` — fails with 409 if accounts use the currency
- `list(tenantId)` → all rates for tenant
- `convertToUsd(BigDecimal amount, String currency, UUID tenantId)` — returns
  `amount` unchanged if `USD`; otherwise looks up rate and multiplies. Throws domain
  exception if no rate exists for a non-USD currency.

### Conversion Points

Conversion via `convertToUsd()` is inserted at:

1. **`AccountService.computeBalance()`** — after computing native-currency balance,
   convert to USD before returning.
2. **`DashboardService`** — already uses `computeBalance()`, inherits conversion.
3. **`TheoreticalPortfolioService` / `CombinedPortfolioHistoryService`** — convert
   data points when summing across accounts.
4. **`ProjectionInputBuilder`** — convert linked account balance to USD for initial
   projection balance.

Principle: conversion happens at the boundary where values leave the single-account
context and enter cross-account aggregation.

## API Surface

### ExchangeRateController

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/exchange-rates` | List all rates for tenant |
| POST | `/api/v1/exchange-rates` | Create a new rate |
| PUT | `/api/v1/exchange-rates/{currencyCode}` | Update a rate |
| DELETE | `/api/v1/exchange-rates/{currencyCode}` | Delete a rate |

Path keyed by `currencyCode` (unique per tenant) rather than UUID.

### DTOs

```
ExchangeRateRequest(
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rateToUsd
)

ExchangeRateResponse(
    String currencyCode,
    BigDecimal rateToUsd,
    OffsetDateTime updatedAt
)
```

### Account DTOs

**AccountRequest** — add optional `currency` field (defaults to `"USD"` if omitted).
No validation against exchange rates at account creation — the rate is only needed at
aggregation time, not when creating the account.

**AccountResponse** — include `currency` field.

## Frontend Changes

1. **Account form** — add `currency` dropdown/text field, default `USD`. Show
   currency on account cards/list alongside balance.

2. **Exchange Rates page** — simple CRUD table (under Settings or own nav item).
   List rates, add/edit/delete.

3. **`formatCurrency()`** — extend to accept optional currency code:
   `formatCurrency(value, currency?)`. Defaults to `USD`. Account-level displays
   use the account's currency; aggregated values (dashboard, projections) show USD.

4. **Account balance display** — show native-currency balance with currency symbol.
   Optionally show USD equivalent for non-USD accounts.

## Edge Cases & Constraints

1. **Deleting rate with accounts using it** — 409 Conflict: "Cannot delete EUR rate:
   N accounts use this currency."

2. **Changing account currency** — allowed freely. Transactions/holdings stay as-is.
   User responsible for ensuring values are correct.

3. **Missing rate at aggregation** — domain exception. Dashboard/portfolio endpoints
   return clear error rather than silently showing unconverted values.

4. **Rate validation** — must be positive (> 0).

5. **USD rate blocked** — reject `currencyCode = "USD"` on create/update (always 1.0).

6. **Currency code format** — validated as 3 uppercase letters (`[A-Z]{3}`).
