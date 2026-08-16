# wealthview-import

Handles all external data ingestion: brokerage CSV exports, OFX/QFX bank files, the Finnhub
stock price and split APIs, Yahoo Finance historical prices, and Zillow property valuations.

Depends on `wealthview-core` for the ingestion interfaces and DTOs. Has no knowledge of
`wealthview-api` or `wealthview-projection`.

The dependency is deliberately inverted: every client and parser here implements an interface
declared in `wealthview-core`, so business code never compiles against an HTTP client or a file
format library.

| Interface (in `wealthview-core`) | Implementation (here) |
|---|---|
| `importservice.ImportParser` | `csv.CsvTransactionParser` (`@Primary`), the broker parsers, `ofx.OfxTransactionParser` |
| `pricefeed.PriceFeedClient` | `finnhub.FinnhubClient` |
| `split.SplitDetectionClient` | `finnhub.FinnhubSplitClient` |
| `price.YahooPriceClient` | `yahoo.YahooFinanceClient` |
| `property.PropertyValuationClient` | `zillow.ZillowScraperClient` |

The root package is `com.wealthview.importmodule` (`import` is a Java keyword).

---

## CSV Import

### Architecture

`CsvTransactionParser` is the `@Primary` generic implementation of `ImportParser`. Brokerage
specifics live in subclasses of `AbstractBrokerCsvParser`, one per institution, because their
CSV formats differ significantly in column naming, date formatting, amount sign conventions,
and header rows.

| Parser | Bean name | Format Notes |
|---|---|---|
| `FidelityCsvParser` | `fidelityCsvParser` | Multi-section CSV with metadata header rows |
| `VanguardCsvParser` | `vanguardCsvParser` | Simpler columnar format; transaction type mapping differs |
| `SchwabCsvParser` | `schwabCsvParser` | Includes brokerage and cash account transactions in one file |
| `FidelityPositionsCsvParser` | `fidelityPositionsCsvParser` | Positions/holdings export; used by `PositionImportService` |

`ImportService` (in `wealthview-core`) resolves the parser by **bean name**: it appends the
constant suffix `CsvParser` to the `format` request parameter, so `format=fidelity` selects
`fidelityCsvParser`. A blank format or `generic` falls back to the `@Primary`
`CsvTransactionParser`; an unrecognised format raises `IllegalArgumentException`, which the
global handler maps to 400.

### Deduplication

`TransactionHashUtil` (in `wealthview-core`) hashes every parsed transaction with SHA-256 over
`date | type | symbol | quantity | amount`, pipe-delimited, with `NULL` standing in for absent
optional components. The hex digest is stored in the `import_hash` column under a unique
constraint. Duplicate rows are skipped and counted on the import job record.

---

## OFX / QFX Import

`OfxTransactionParser` (bean name `ofxParser`) wraps the **OFX4J** library, version 1.39, group
`com.webcohesion.ofx4j`. OFX is a standardised XML/SGML format used by most US banks and
brokerages for statement downloads (`.ofx` / `.qfx` files).

A single parser covers all institutions — format differences are handled by OFX4J itself.
Mapping is split by statement kind: `OfxBankTransactionMapper` for bank statements,
`OfxInvestmentTransactionMapper` for brokerage statements, with `OfxDateUtils` normalising OFX
timestamps. The same SHA-256 deduplication applies.

---

## Finnhub Clients

### `FinnhubClient` — prices

Implements the core `PriceFeedClient` interface (`getQuote`, `getCandles`). Authenticates with
an `X-Finnhub-Token` header and works in the `America/New_York` trading calendar.

**Rate limiting** is applied by the caller, not the client: `PriceSyncService` in
`wealthview-core` sleeps `app.finnhub.rate-limit-ms` (default **1100 ms**) between calls during
batch operations, staying safely inside the free tier's 60 requests/minute.

**Price sync jobs**, both driven from `wealthview-core`:

1. **Daily sync** — `PriceSyncService.syncDailyPrices()` carries its own `@Scheduled`
   (`app.finnhub.sync-cron`, default 18:00 America/New_York), fires Monday–Friday, and
   fetches the close for every symbol currently held across all tenants.

2. **Historical backfill** — `PriceSyncService.onNewHolding(...)` listens for the
   `NewHoldingCreatedEvent` published by `HoldingsComputationService` and backfills the
   trailing **2 years** of daily closes when a symbol first appears, so portfolio history
   charts populate immediately. It no-ops if prices already exist for the symbol.

Fetched prices are stored with `source = finnhub`.

### `FinnhubSplitClient` — stock splits

Implements the `@FunctionalInterface` `SplitDetectionClient`. On network or HTTP failure it
returns an **empty list** and logs a warning rather than throwing — split discovery is
advisory, and an empty result simply means "try again tomorrow".

It backs two `wealthview-core` beans, both `@ConditionalOnBean(SplitDetectionClient.class)`
(which in turn requires a non-empty `app.finnhub.api-key`):

* `StockSplitSyncService` — nightly at 02:00 America/New_York
  (`app.stock-splits.sync-cron`), scanning every held symbol with a 7-day overlap window.
* `StockSplitBackfillRunner` — one-time historical backfill run asynchronously after
  `ContextRefreshedEvent`, idempotent via the `stock_splits.backfill_completed` flag in
  `system_config`, and gated by `app.stock-splits.backfill-auto-run` (default `true`).

---

## Yahoo Finance Client

`YahooFinanceClient` implements the core `YahooPriceClient` interface. It provides an
alternative historical price source used by the admin price tooling
(`POST /api/v1/admin/prices/yahoo/sync`, `.../fetch`, `.../save`). Prices sourced this way are
stored with `source = yahoo` (added by migration V047).

---

## Zillow Property Valuation

`ZillowScraperClient` implements the core `PropertyValuationClient` interface and uses
**jsoup** (1.23.1) to fetch a Zillow property detail page and extract the current Zestimate.

### Why a Scraper?

Zillow's official API is no longer available. The scraper is intentionally narrow: it matches
the embedded JSON blocks for `zpid` and Zestimate, with a DOM fallback for pages that render
the value directly, and is guarded by a configurable timeout (`app.zillow.timeout-ms`, default
10 000 ms) and a per-call rate limit (`app.zillow.rate-limit-ms`, default 5 000 ms). It is
disabled by default (`app.zillow.enabled: false`).

A property must first be associated with a Zillow ZPID — the API exposes ZPID search and
selection under `POST /api/v1/properties/{id}/valuations/select-zpid` — before automated syncs
will run for it.

### Sync Schedule

`PropertyValuationSyncService` (in `wealthview-core`) runs the Zillow sync on
`app.zillow.sync-cron`, defaulting to **Sunday at 6:00 AM**. Weekly frequency is intentional —
Zillow updates Zestimates roughly once a week, and more frequent scraping risks detection.

Synced valuations are stored with `source = zillow`.

`ZillowHealthIndicator` and `FinnhubHealthIndicator` in `wealthview-app` surface the reachability
of both integrations through Actuator.

---

## Dependencies

| Library | Version | Use |
|---|---|---|
| Apache Commons CSV | 1.14.1 | CSV parsing; `CSVFormat` per brokerage |
| OFX4J (`com.webcohesion.ofx4j`) | 1.39 | OFX/QFX file parsing |
| jsoup | 1.23.1 | Zillow HTML scraping |

---

## Testing

Import parsers are tested against real fixture files in `src/test/resources/`:
`fidelity-sample.csv`, `vanguard-sample.csv`, `schwab-sample.csv`, a Fidelity positions export
under `testdata/`, and four Zillow HTML fixtures under `zillow/` (valid, DOM-only, missing, and
malformed Zestimate pages). Each test validates that a known sample produces the expected
transaction list — correct amounts, dates, symbols, and transaction types — and that malformed
input degrades to a row-level error rather than an exception.

Network calls are never made in CI; the HTTP clients are exercised against stubbed responses.

Coverage gates: **80%** line, **0.71** branch (enforced by `jacoco:check` on `mvn verify`).
