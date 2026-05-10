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
fake cliffs on the split date.

Both actions are recorded row-by-row in `stock_split_adjustments` so the
operation is fully reversible — see "Un-applying" below.

## How detection runs

1. **Nightly sync** — at 02:00 server time, `StockSplitSyncService`
   enumerates every distinct symbol the deployment has ever transacted in,
   calls Finnhub `/stock/split` with a 7-day overlap window, and applies
   any new splits via `StockSplitService.applySplit`. Idempotent:
   re-running the sync cannot apply the same `(symbol, date)` twice.

2. **One-time backfill** — on first deployment after this feature ships,
   `StockSplitBackfillRunner` runs once asynchronously after
   `ApplicationReadyEvent`. It scans every symbol back to its earliest
   transaction date and applies any splits Finnhub knows about. Guarded
   by `system_config.stock_splits.backfill_completed = 'true'` so it
   never reruns.

3. **Manual entry** — for symbols Finnhub doesn't cover (small foreign
   tickers, recent IPOs, etc.), super-admins can `POST /api/v1/admin/stock-splits`
   with `{symbol, effective_date, numerator, denominator}`. This calls
   the same `applySplit` code path as the auto-detection — same audit
   trail, same un-apply support.

4. **Manual re-sync** — `POST /api/v1/admin/stock-splits/sync` runs the
   same logic as the scheduled job on demand. Useful if the cron has
   been disabled or you want to verify a fresh split appears immediately.

## Un-applying

`DELETE /api/v1/admin/stock-splits/{id}` reverses everything. It reads
the `stock_split_adjustments` rows and restores each `old_value` to its
source row, then deletes the split (which cascade-deletes the
adjustments), then recomputes holdings. Requires SUPER_ADMIN.

Use this when:
- Finnhub reports a phantom split that didn't actually happen
- A symbol's data was corrupted and you want a clean slate before
  re-applying
- Manual entry had the wrong ratio — un-apply, then re-add

## Manual-override holdings

If a holding row has `is_manual_override = true`, the auto-recompute
skips it (this is the existing rule for any holdings recompute). The
split apply logs a `WARN` with the holding id and tenant id; the human
operator then needs to either flip the override off and re-trigger
recompute, or manually adjust the holding.

## Metrics to watch

All exported via `/actuator/prometheus`:

| Metric | Meaning |
|---|---|
| `wealthview_splits_applied_total{symbol, ratio}` | Splits successfully applied. Sudden spike → check the source. |
| `wealthview_splits_unapplied_total{symbol}` | Manual reverts. Should be near-zero. |
| `wealthview_splits_synced_total{result="success"\|"partial"}` | Per-run sync status. |
| `wealthview_splits_sync_failed_total{symbol}` | Per-symbol failure during sync (Finnhub unavailable, etc.). |
| `wealthview_splits_last_success_seconds` | Unix epoch of last successful sync. Alert if `(now - this) > 36h`. |
| `wealthview_splits_backfill_completed_total` | Increments exactly once on first deployment. |
| `wealthview_finnhub_splits_*` | Latency/error histogram for the Finnhub call. |

## "Splits aren't being detected"

1. Confirm `app.finnhub.api-key` is set — without it, the
   `FinnhubSplitClient` bean isn't registered and neither
   `StockSplitSyncService` nor `StockSplitBackfillRunner` will load.
2. Check `wealthview_splits_last_success_seconds` — if it's stale,
   the cron didn't run (verify `SchedulingConfig` is loaded) or
   exceptions are crashing the run (look for ERROR in the
   `StockSplitSyncService` logger).
3. Check `wealthview_splits_sync_failed_total` — per-symbol failures
   are tagged so you can see which symbols Finnhub is choking on.
4. Spot-check Finnhub coverage manually:
   `curl -H "X-Finnhub-Token: $KEY" "https://finnhub.io/api/v1/stock/split?symbol=AAPL&from=2020-01-01&to=2021-01-01"`
5. If Finnhub doesn't have the data, fall back to the manual
   admin endpoint.

## Disabling

- Set `app.finnhub.api-key=""` to disable both detection and backfill.
- Set `app.stock-splits.adjust-historical-prices=false` to apply
  splits to transactions only and leave the historical prices alone.
  (Useful if you import prices from a feed that already returns
  split-adjusted values.)
