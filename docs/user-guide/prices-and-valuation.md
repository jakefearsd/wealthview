[← Back to README](../../README.md)

# Prices and Valuation

WealthView values your portfolio by multiplying each holding's share quantity by the latest available price for that security. This guide explains how prices are sourced, how valuation works, and how to manage price data.

---

## How Valuation Works

For each holding, WealthView calculates market value as:

**Market Value = Quantity × Latest Price**

The "latest price" is simply the most recent close price on record for that symbol, whatever its origin — an automated sync, an uploaded file, or something you typed in yourself.

If no price is available for a symbol, WealthView falls back to the holding's **cost basis** when totalling an account or your net worth, so nothing vanishes from the sum. On the Holdings table itself, an unpriced holding shows `—` in the Price, Market Value, and Gain/Loss columns — a clear signal that the number is missing rather than zero.

On the account detail page you will see, per holding:

- **Price** — The latest close on record.
- **Market Value** — Quantity × that price.
- **Gain/Loss** — Market value minus cost basis, in dollars.

---

## The Prices Page

**Prices** in the sidebar shows what WealthView currently knows about every symbol it has a price for.

### Latest Prices

A table with one row per symbol:

| Column | What It Shows |
|--------|---------------|
| **Symbol** | The ticker. |
| **Latest Date** | The date of the most recent close price on record. |
| **Close Price** | That price. |
| **Source** | Where it came from: `finnhub`, `yahoo`, or `manual`. |

A **Refresh** button in the panel header re-reads the list. Note that this table lists every symbol that has price data — including symbols you no longer hold — because prices are shared reference data rather than per-account information.

### Add Manual Price

Above the table, admins and super-admins see an **Add Manual Price** panel with three fields — **Symbol**, **Date**, and **Price** — and a **Save** button. Symbols are upper-cased for you. Saved prices are recorded with the source `manual`, and each one you add in the current session is also echoed in a **Recently Added** panel at the bottom of the page (it disappears on reload; the price itself is stored permanently).

Adding a price requires an admin or super-admin account; members and viewers do not see the panel. If you are a member and need a price added, ask an admin.

Manual entry is useful for:

- Securities not covered by the automated feeds (private placements, some foreign securities, obscure mutual funds).
- Correcting a price that was fetched incorrectly.
- Adding historical prices so a portfolio history chart has something to draw.

---

## Where Prices Come From

WealthView has three sources of price data, plus the seed data it ships with. All of the automated controls live under **Admin → Prices**, which is available to admins and super-admins and is split into four tabs.

### Finnhub (automatic)

The primary feed. If your deployment sets a `FINNHUB_API_KEY`, WealthView fetches quotes from [finnhub.io](https://finnhub.io) — free API keys are available.

- **Scheduled sync** — runs automatically at **6:00 PM US Eastern, Monday to Friday**, after the US market close. It walks every symbol held anywhere in the deployment and records that day's price.
- **On-demand sync** — **Admin → Prices → Finnhub Sync** has a **Sync All Holdings** button that runs the same job immediately. Below it, a **Price Sync Status** table lists every symbol with its latest date, source, and a **Current** or **Stale** badge.
- **Automatic backfill** — the first time a holding appears for a symbol WealthView has never seen, it pulls roughly two years of daily history so charts have something to show right away.

Finnhub's free tier is rate-limited, so WealthView deliberately paces its requests. If you hold many different securities, a sync can take a few minutes.

If no API key is configured, the Finnhub sync simply doesn't run — nothing breaks, but prices only come from the other sources.

### Yahoo Finance (manual fallback)

**Admin → Prices → Yahoo Finance** covers symbols Finnhub doesn't. The tab opens with an orange warning: *"Yahoo Finance scraping may break without notice. Use as a fallback for symbols Finnhub doesn't cover."* Take it at face value — this source reads a public web page and can stop working at any time.

Two controls:

- **Sync All Holdings from Yahoo** — one button, fetches everything you hold.
- **Fetch Specific Symbols** — enter comma-separated tickers and a **From** / **To** date range (defaulting to the last 30 days), click **Fetch Preview**, review the results in a table, then click **Save All** to commit them. Nothing is written until you press Save.

Prices from here are stored with the source `yahoo`.

### CSV Upload

**Admin → Prices → CSV Upload** takes a file with a header row and three columns: `symbol`, `date`, `close_price`, with dates as `YYYY-MM-DD`.

Rows are validated individually. A row is rejected — with the line number and the reason listed on screen — if the symbol is missing, the date is unparseable or in the future, or the price is not a positive number. Everything else imports. Uploaded prices are stored with the source `manual`.

### Browse and Correct

**Admin → Prices → Browse** lets you look up one symbol over a date range. Enter a **Symbol**, a **From** and **To** date, and click **Search**. You get a line chart of the period plus a table of every stored price with its source, and a **Delete** link on each row for removing a bad data point.

---

## Bundled Historical Price Data

WealthView ships with seed price data for twelve commonly held symbols:

**AAPL, AMZN, BND, FXAIX, GOOG, MSFT, NVDA, SCHD, VOO, VTI, VUG, VXUS**

Each series runs from as far back as the security has history (AAPL starts in December 1980) through March 2026, so portfolio history charts for these symbols work immediately, without waiting for a live feed to accumulate data.

If your holdings include symbols outside this list, historical chart data only exists from whenever a feed first collected it — or from whatever you entered manually.

> **On upgrades:** when a WealthView release ships refreshed seed data, the price table is rebuilt from the seed file. Prices you entered by hand for other symbols are replaced along with it, so keep your own price CSVs if they are hard to reconstruct.

---

## Symbols Without Prices

### Money Market Funds

The most common case. Funds like SPAXX hold a stable $1.00 NAV and pay interest instead of trading, so no price exists to fetch.

WealthView detects these automatically from the ticker — **SPAXX, FDRXX, FZFXX, VMFXX, VMMXX, SWVXX, SNVXX, SPRXX** — and flags the holding. You do not configure anything. See [Investment Accounts](investment-accounts.md#money-market-funds) for what changes on screen.

In charts, money market holdings are **not** dropped: they are carried at a constant $1.00 per share, and the chart shows a note saying so. In account and net-worth totals they fall back to cost basis, which for a $1 NAV fund is the same thing.

### Everything Else

If a holding shows `—` in the Price column, WealthView has no price on record for that symbol. Your options:

1. Add prices manually on the **Prices** page (or ask an admin to).
2. Check that the Finnhub feed is configured and that the symbol is one Finnhub covers — the **Price Sync Status** table under Admin → Prices will show it as missing or stale.
3. Try the Yahoo Finance fallback for mutual funds and less common tickers.
4. Bulk-load a history via **Admin → Prices → CSV Upload**.

Symbols with no price data are excluded from portfolio history charts entirely rather than plotted at zero — which is why a chart can look "short" when one of your holdings has no data.

---

## Stock Splits

WealthView detects and applies stock splits for you. You do not have to adjust anything by hand after a split.

**What happens automatically.** A nightly job at 2:00 AM US Eastern checks every symbol the deployment has ever transacted in for new splits. When one is found:

- Every transaction for that symbol dated on or before the effective date has its **share quantity** multiplied by the split ratio. The dollar amounts are left alone — you paid what you paid.
- Holdings are recomputed from the adjusted transactions, so your position reflects the post-split share count.
- Historical prices before the effective date are divided by the same ratio, which keeps long-term price charts from showing a fake cliff on the split date.

**What you see.** A **Recent stock splits** panel at the bottom of the Dashboard lists splits affecting your portfolio, and each holding's detail page has a **Splits affecting *SYMBOL*** panel scoped to that one symbol. Both show:

| Column | What It Shows |
|--------|---------------|
| **Symbol** | The ticker. |
| **Effective date** | The date the split took effect. |
| **Ratio** | Numerator:denominator — a 4:1 forward split reads `4:1`. |
| **Source** | A coloured badge: *Auto-detected via Finnhub*, *Auto-detected via Yahoo*, *Manually entered*, or *Backfilled*. |

If nothing has affected you, the panel simply says so and reminds you that new splits are detected automatically each night.

**Two things worth knowing:**

- Split detection depends on the Finnhub key. Without one, splits are not detected automatically and must be entered by a super-admin under **Admin → Stock Splits**.
- A holding with the **manual override** flag set is skipped by the post-split recompute, because manual overrides always win over computed values. If you have manually overridden a holding and it later splits, you will need to update its quantity yourself.

---

## Portfolio History Charts

Two charts use the stored price history. Both work the same way underneath and both are described in more detail in [Portfolio Analysis](portfolio-analysis.md).

### Per-Account: Theoretical Portfolio History

On each non-bank account's detail page. Prices that account's **current** holdings at each weekly point over a window you choose (6 months to 20 years, defaulting to 2 years).

### Dashboard: Combined Portfolio History

On the Dashboard. Stacks investments and property equity across everything you own, over 1 to 10 years (defaulting to 2).

### How the Charts Work

Both charts take the shares you hold **today** and value them at historical prices. This means:

- If you bought 100 shares of VTI last month, the chart shows what those 100 shares would have been worth at every past date where price data exists.
- The charts do **not** reconstruct your past holdings from transaction history — they use today's positions throughout.
- Points are sampled weekly (each Friday), plus today, so the line always reaches the current date.
- For any given week, a point is only drawn if a price exists for **every** priced symbol in the set. A symbol with sparse history can therefore shorten the whole chart.
- Symbols with no price data at all are excluded rather than plotted at zero.
- Balances in non-USD accounts are converted to USD at the rate an admin has configured.

### Time Range Filter

The dropdown in each chart's top-right corner changes the window. The per-account chart offers 6 Months, 1 / 2 / 3 / 5 / 10 / 20 Years; the dashboard chart offers 1 / 2 / 3 / 5 / 10 Years. Changing it reloads the chart with a fresh calculation over the new window.
