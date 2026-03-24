# wealthview-import

Handles all external data ingestion: brokerage CSV exports, OFX/QFX bank files, the Finnhub
stock price API, and Zillow property valuations.

Depends on `wealthview-core` for service interfaces and DTOs. Has no knowledge of
`wealthview-api` or `wealthview-projection`.

---

## CSV Import

### Architecture

All CSV parsers extend `AbstractBrokerCsvParser` and implement `CsvTransactionParser`.
Each brokerage has its own subclass because their CSV formats differ significantly in
column naming, date formatting, amount sign conventions, and header rows.

| Parser | Brokerage | Format Notes |
|---|---|---|
| `FidelityCsvParser` | Fidelity | Multi-section CSV with metadata header rows; uses "Run Date" and "Symbol" columns |
| `VanguardCsvParser` | Vanguard | Simpler columnar format; transaction type mapping differs |
| `SchwabCsvParser` | Schwab | Includes brokerage and cash account transactions in one file |
| `FidelityPositionsCsvParser` | Fidelity | Positions/holdings export; used by `PositionImportService` |

The format is selected by the `format` query parameter on `POST /import/csv`:
`fidelity`, `vanguard`, or `schwab`.

### Deduplication

Every parsed transaction is hashed with SHA-256 over `(date, amount, description)`. The hash
is stored in the `import_hash` column with a unique constraint. Duplicate rows are silently
skipped; the import job record tracks `rows_skipped` for visibility.

---

## OFX / QFX Import

`OfxTransactionParser` wraps the **OFX4J** library (version 1.7). OFX is a standardised XML/SGML
format used by most US banks and brokerages for statement downloads (`.ofx` / `.qfx` files).

A single parser covers all institutions — format differences are handled by OFX4J's parser.
Parsed `OFXTransaction` objects are mapped to `TransactionEntity` values using the standard
transaction type mapping. The same SHA-256 deduplication applies.

---

## Finnhub Price Client

`FinnhubClient` calls the **Finnhub free API** to fetch daily stock close prices.

### Rate Limiting

The free Finnhub tier allows 60 requests per minute. `FinnhubClient` enforces a **1100 ms
minimum delay** between calls to stay safely within the limit during batch operations.

### Price Sync Job

`PriceSyncService` (in `wealthview-core`) triggers `FinnhubClient` for:

1. **Daily sync** — run as a `@Scheduled` cron job; fetches the previous trading day's close
   for every symbol currently held across all tenants.

2. **Historical backfill** — triggered by a `NewHoldingCreatedEvent` application event;
   backfills daily close prices for the trailing 2 years when a new symbol first appears.
   This populates the portfolio history charts immediately.

Fetched prices are stored with `source = finnhub`.

---

## Zillow Property Valuation

`ZillowScraperClient` uses **jsoup** (version 1.21) to scrape the Zillow property detail page
and extract the current estimated value (`Zestimate`).

### Why a Scraper?

Zillow's official API was deprecated in 2023. The scraper is intentionally fragile —
it parses a known JSON-LD block in the page `<head>` that Zillow has kept stable — and is
guarded by a configurable timeout and per-call rate limit.

A property must first be associated with a Zillow ZPID via `POST /properties/select-zpid`
before automated syncs will run for it.

### Sync Schedule

`PropertyValuationSyncService` (in `wealthview-core`) runs the Zillow sync every
**Sunday at 6:00 AM** (configurable cron). Weekly frequency is intentional — Zillow updates
Zestimates approximately once a week, and more frequent scraping risks detection.

Synced valuations are stored with `source = zillow`.

---

## Dependencies

| Library | Version | Use |
|---|---|---|
| Apache Commons CSV | 1.14.1 | CSV parsing; `CSVFormat` per brokerage |
| OFX4J | 1.7 | OFX/QFX file parsing |
| jsoup | 1.21.2 | Zillow HTML scraping |

---

## Testing

Import parsers are tested with real fixture CSV/OFX files stored in `src/test/resources/`.
Each parser test validates that a known sample file produces the expected transaction list:
correct amounts, dates, symbols, and transaction types.

Integration tests for `FinnhubClient` and `ZillowScraperClient` are opt-in behind a
system property (`-Dfinnhub.integration.test=true`) to avoid network calls in CI.
