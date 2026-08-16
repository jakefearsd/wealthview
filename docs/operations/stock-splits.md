# Stock splits

WealthView detects and applies stock splits automatically. This document
covers the full lifecycle: how detection works, how to recover when it
doesn't, and what to look at in the metrics.

## What "applying" a split means

When `AAPL` has a 4:1 forward split on 2020-08-31, every transaction for
`AAPL` dated on or before 2020-08-31 has its `quantity` multiplied by 4.
The total dollar `amount` of each transaction is left untouched (you paid
the same total dollars). Holdings then recompute to the new post-split
quantity from the rewritten transactions.

If `app.stock-splits.adjust-historical-prices` is `true` (default), every
historical price for `AAPL` strictly before the effective date also has its
`close_price` divided by 4. This keeps long-term price charts from showing
fake cliffs on the split date. Prices are only touched when at least one
tenant actually holds the symbol — otherwise the split row is recorded and
the price history is left alone.

The arithmetic lives in `SplitMath`: it multiplies and then divides in one
step (`quantity × numerator ÷ denominator`, `close × denominator ÷ numerator`)
so reverse and odd ratios (1:3, 1:6, 1:7) don't accumulate the rounding error
a pre-divided ratio bakes in. Results are scale-4 `HALF_UP`.

Splits are **global market events**, not tenant data. The `stock_splits`
table has no `tenant_id`; `applySplit`/`unapplySplit` are annotated
`@CrossTenant` so the Hibernate tenant filter cannot silently scope the
rewrite to whichever principal the calling thread happens to carry. They are
also `@EvictPriceDerivedCaches`, so the balance/price caches don't serve
pre-split numbers afterwards.

Both actions are recorded row-by-row in `stock_split_adjustments` so the
operation is fully reversible — see "Un-applying" below.

## Schema (V064)

```
stock_splits              id, symbol, effective_date, numerator, denominator,
                          source, applied_at, notes, created_at, updated_at
                          UNIQUE (symbol, effective_date)
                          CHECK source IN ('finnhub','yahoo','manual','backfill')

stock_split_adjustments   id, split_id -> stock_splits(id) ON DELETE CASCADE,
                          tenant_id, target_table, target_row_id, field_name,
                          old_value numeric(19,8), new_value numeric(19,8), created_at
                          CHECK target_table IN ('transactions','prices','holdings')
```

Two details worth knowing before you read rows by hand:

- For `target_table = 'transactions'` the `target_row_id` is the real
  transaction UUID and `field_name` is `quantity`.
- For `target_table = 'prices'` the `target_row_id` is a **deterministic**
  UUID derived from `price:<symbol>:<date>` (the prices table has no surrogate
  key the adjustment can point at), `field_name` is `close_price`, and
  `tenant_id` is an *anchor* — the first affected tenant, recorded once so an
  un-apply doesn't restore the same global price row N times.
- `'holdings'` is permitted by the CHECK constraint but nothing writes it;
  holdings are always recomputed from transactions, never adjusted directly.

## How detection runs

1. **Nightly sync** — `StockSplitSyncService.syncAll()` runs on
   `${app.stock-splits.sync-cron:0 0 2 * * *}` in the `America/New_York`
   zone (02:00 ET by default). It enumerates every distinct symbol the
   deployment has ever transacted in, calls Finnhub
   `/api/v1/stock/split` with a 7-day overlap behind the last recorded sync,
   and applies any new splits via `StockSplitService.applySplit` with
   `source = 'finnhub'`. Idempotent: an `existsBySymbolAndEffectiveDate`
   check plus a unique constraint mean the same `(symbol, date)` cannot be
   applied twice. The high-water mark is stored in `system_config` under
   `stock_splits.last_sync_at`.

2. **One-time backfill** — on first deployment after this feature ships,
   `StockSplitBackfillRunner` runs once asynchronously on a dedicated
   `stock-split-backfill` daemon thread when the context refreshes
   (`ContextRefreshedEvent`), so it never blocks startup. It scans every
   symbol back to that symbol's earliest transaction date and applies any
   splits Finnhub knows about, with `source = 'backfill'`. Guarded by
   `system_config` key `stock_splits.backfill_completed = 'true'` so it never
   reruns. The startup auto-run itself can be turned off with
   `app.stock-splits.backfill-auto-run=false` (integration tests do this and
   drive `runIfNeeded()` explicitly).

3. **Manual entry** — for symbols Finnhub doesn't cover (small foreign
   tickers, recent IPOs, etc.), super-admins can `POST /api/v1/admin/stock-splits`
   with `{"symbol", "effective_date", "numerator", "denominator"}` — all four
   required, the two integers must be positive. It records `source = 'manual'`
   and calls the same `applySplit` code path as auto-detection — same audit
   trail, same un-apply support. Returns `201` with the split row.

4. **Manual re-sync** — `POST /api/v1/admin/stock-splits/sync` runs the
   same `syncAll()` as the scheduled job, on demand, and returns
   `{"symbols_scanned", "splits_discovered", "splits_applied", "failed_symbols"}`.
   Useful if the cron has been disabled or you want to verify a fresh split
   appears immediately. Returns `503` with the standard error envelope when
   Finnhub isn't configured.

Both scheduled paths are `@ConditionalOnBean(SplitDetectionClient.class)`,
and that bean only exists when `app.finnhub.api-key` is non-empty.

### How a Finnhub row becomes a ratio

Finnhub returns `{"symbol","date","fromFactor","toFactor"}` as doubles.
`FinnhubSplitClient` computes `numerator = round(toFactor × 1000)`,
`denominator = round(fromFactor × 1000)`, then reduces by GCD — so
`1.0 → 4.0` becomes `4:1` and `1.0 → 0.5` becomes `1:2`. Malformed rows
(missing date or either factor) are skipped with a `WARN`. Any HTTP or
network failure returns an **empty list** and logs a warning: detection is
discovery-only, so "no data today" means retry tomorrow, never "no splits
exist."

## Reading splits as a user

`GET /api/v1/stock-splits` returns the splits that affect the caller's own
portfolio — splits whose symbol the tenant has at least one transaction in.
Optional `symbol`, `from` and `to` (ISO date) query params narrow it. Any
authenticated role can call it.

In the UI, the read-only `RecentStockSplits` component renders that list on
the **Dashboard** and, filtered to one ticker, on the **holding detail**
page.

## Un-applying

`DELETE /api/v1/admin/stock-splits/{id}` reverses everything and returns
`204`. It reads the `stock_split_adjustments` rows, restores each `old_value`
onto its source row, deletes the adjustment rows and then the split row, and
finally recomputes holdings for every tenant holding the symbol. Requires
SUPER_ADMIN.

Use this when:
- Finnhub reports a phantom split that didn't actually happen
- A symbol's data was corrupted and you want a clean slate before
  re-applying
- Manual entry had the wrong ratio — un-apply, then re-add

Restores are best-effort per row: if a transaction or price row referenced by
an adjustment no longer exists (deleted between apply and un-apply), that one
row is skipped with a `WARN` and the rest of the reversal still completes.
The log line at the end reports how many transactions and prices were
actually restored — compare it against the adjustment count if you're
suspicious.

In the UI this is the **Admin → Stock Splits** tab (`StockSplitsSection`,
SUPER_ADMIN only), which also hosts the manual-entry form and a "sync now"
button. Un-apply is behind a confirm dialog.

## Manual-override holdings

If a holding row has `is_manual_override = true`, the auto-recompute
skips it (this is the existing rule for any holdings recompute). The
split apply logs a `WARN` with the holding id and tenant id; the human
operator then needs to either flip the override off and re-trigger
recompute, or manually adjust the holding.

## Metrics to watch

All exported via `/actuator/prometheus` (SUPER_ADMIN-only — see
`docs/OBSERVABILITY.md`).

| Metric (Micrometer name) | Meaning |
|---|---|
| `wealthview.splits.applied{symbol, ratio}` | Splits successfully applied. Sudden spike → check the source. |
| `wealthview.splits.unapplied{symbol}` | Manual reverts. Should be near-zero. |
| `wealthview.splits.synced_total{result="success"\|"partial"}` | One increment per completed sync run. `partial` means at least one symbol failed. |
| `wealthview.splits.sync_failed{symbol}` | Per-symbol failure during sync (Finnhub unavailable, etc.). |
| `wealthview.splits.last_success_seconds` | Unix epoch of the last **completed** sync run; `0` if never. Alert if `(now - this) > 36h`. |
| `wealthview.splits.backfill_completed_total` | Increments exactly once, on the run that flips the backfill flag. |
| `wealthview.splits.sync` | Timer + histogram around the whole `syncAll()` run. |
| `wealthview.finnhub.splits` | Timer + histogram for the Finnhub `/stock/split` call. |

Two gotchas when you turn these into queries:

- In Prometheus the dots become underscores, timers expand into
  `_seconds_count`/`_seconds_sum`/`_seconds_bucket`, and counters gain a
  `_total` suffix — but `wealthview.splits.synced_total` and
  `wealthview.splits.backfill_completed_total` already end in `_total` in
  their Micrometer name. Check the live scrape body for their exact exposed
  name before writing an alert against them.
- `wealthview.splits.last_success_seconds` is bumped at the end of **every**
  run that completes, including one where individual symbols failed. It means
  "the job ran to completion," not "every symbol succeeded." Pair it with
  `wealthview.splits.synced_total{result="partial"}` and
  `wealthview.splits.sync_failed`.

## "Splits aren't being detected"

1. Confirm `app.finnhub.api-key` is set — without it the whole
   `FinnhubConfig` is skipped (`@ConditionalOnExpression`), so no
   `SplitDetectionClient` bean exists and neither `StockSplitSyncService`
   nor `StockSplitBackfillRunner` is registered. The quickest check:
   `POST /api/v1/admin/stock-splits/sync` returns `503` when the client is
   absent. (Note the same key gates the Finnhub *price* feed.)
2. Check `wealthview_splits_last_success_seconds` — if it's stale or `0`,
   the cron didn't run (verify `SchedulingConfig` — the `@EnableScheduling`
   holder — is loaded and that `app.stock-splits.sync-cron` wasn't overridden
   with something that never fires) or the run is dying early (look for
   `ERROR` from `StockSplitSyncService`).
3. Check `wealthview_splits_sync_failed` — per-symbol failures are tagged so
   you can see which symbols Finnhub is choking on. A run with any failure
   also increments `synced_total{result="partial"}`.
4. Spot-check Finnhub coverage manually:
   ```bash
   curl -H "X-Finnhub-Token: $FINNHUB_API_KEY" \
     "https://finnhub.io/api/v1/stock/split?symbol=AAPL&from=2020-01-01&to=2021-01-01"
   ```
5. If Finnhub doesn't have the data, fall back to the manual admin endpoint.

## "A split looks wrong"

Work from the audit trail rather than guessing — every rewrite is recorded.

1. Find the split row:
   ```sql
   SELECT id, symbol, effective_date, numerator, denominator, source, applied_at
   FROM stock_splits WHERE symbol = 'AAPL' ORDER BY effective_date;
   ```
   (`./wv psql` opens a shell against the running deployment.)
2. Sanity-check `source`. `finnhub`/`backfill` means the ratio came from the
   feed; `manual` means a human typed it and is the far more likely culprit.
3. Look at what it actually touched:
   ```sql
   SELECT target_table, field_name, count(*), min(old_value), min(new_value)
   FROM stock_split_adjustments WHERE split_id = '<id>'
   GROUP BY target_table, field_name;
   ```
   Zero `transactions` rows on a symbol people hold means the apply ran
   without seeing their data — check the app log for the
   "found ZERO tenants holding … immediately afterward" `WARN` that both the
   sync and the backfill emit for exactly this case.
4. Check for a **duplicate** adjustment — i.e. the same underlying event
   recorded twice under two effective dates (feed date vs. ex-date). Two
   rows for the same symbol a day or two apart with compatible ratios is the
   classic double-application. Un-apply the wrong one.
5. Check the holdings didn't get skipped: any holding for that symbol with
   `is_manual_override = true` was not recomputed, and the apply logged a
   `WARN` naming it.
6. To fix: `DELETE /api/v1/admin/stock-splits/{id}` to reverse, verify the
   transaction quantities are back to their pre-split values, then re-enter
   the correct ratio via `POST /api/v1/admin/stock-splits` if a split really
   did occur.

## Disabling

- Set `app.finnhub.api-key=""` (or leave `FINNHUB_API_KEY` unset) to disable
  both detection and backfill. This also disables the Finnhub price feed —
  the two share one conditional configuration.
- Set `app.stock-splits.backfill-auto-run=false` to keep the nightly sync but
  suppress the one-time startup backfill.
- Set `app.stock-splits.adjust-historical-prices=false` to apply
  splits to transactions only and leave the historical prices alone.
  (Useful if you import prices from a feed that already returns
  split-adjusted values.)
- Override `app.stock-splits.sync-cron` to reschedule the nightly job.

Manual entry and un-apply remain available in all of these cases — they don't
depend on Finnhub.
