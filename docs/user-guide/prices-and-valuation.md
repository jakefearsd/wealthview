[← Back to README](../../README.md)

# Prices and Valuation

WealthView values your portfolio by multiplying each holding's share quantity by the latest available price for that security. This guide explains how prices are sourced, how valuation works, and how to manage price data.

---

## How Valuation Works

For each holding in your accounts, WealthView calculates market value as:

**Market Value = Quantity x Latest Price**

If no price is available for a symbol, WealthView falls back to the holding's **cost basis** as an estimate. This ensures every holding contributes to your net worth calculation, even if price data is unavailable.

On the dashboard and account detail pages, you will see both:

- **Market Value** — Based on the latest price (or cost basis if no price exists).
- **Gain/Loss** — The difference between market value and cost basis.

---

## Manual Price Entry

You can enter or update prices manually from the **Prices** page.

1. Navigate to **Prices** in the sidebar.
2. You will see a list of all symbols that appear in your holdings.
3. Click on a symbol to view its price history.
4. Click **Add Price** to enter a new price point.
5. Enter the **date** and **price**, then save.

Manual entry is useful for:

- Securities not covered by the automated price feed (e.g., private placements, foreign securities).
- Correcting a price that was fetched incorrectly.
- Adding historical prices for backdated portfolio analysis.

---

## Finnhub Automated Price Feed

WealthView can automatically fetch daily closing prices from the **Finnhub** financial data API.

### Setup

To enable automated price fetching, set the `FINNHUB_API_KEY` environment variable to your Finnhub API key. You can get a free API key by registering at [finnhub.io](https://finnhub.io).

For Docker Compose deployments, add the key to your environment configuration:

```yaml
environment:
  FINNHUB_API_KEY: your_api_key_here
```

### How It Works

- The price feed runs automatically on **weekdays at 4:30 PM** (after U.S. market close).
- It fetches the closing price for every symbol that appears in your holdings.
- Prices are stored with the current date and become the "latest price" used for valuation.

### Free Tier Rate Limits

Finnhub's free tier has API rate limits. WealthView respects these limits by spacing out requests. If you hold many different securities, the price sync may take a few minutes to complete. All symbols will be updated within a single sync cycle.

---

## Historical Price Data

WealthView includes **seed price data** for 12 commonly held symbols, covering historical prices from 2006 to present:

AAPL, AMZN, BND, FXAIX, GOOG, MSFT, NVDA, SCHD, VOO, VTI, VUG, VXUS

This seed data powers the portfolio history charts immediately, without waiting for the Finnhub feed to accumulate data. If your holdings include symbols outside this list, historical chart data will only be available from the date Finnhub begins collecting prices (or from any prices you enter manually).

---

## Symbols Without Prices

Some securities do not have publicly traded prices. The most common example is **money market funds** like SPAXX (Fidelity Government Money Market).

For money market holdings:

- Mark the holding as a money market fund on the account detail page (see [Investment Accounts](investment-accounts.md) for details).
- WealthView values the holding using cost basis rather than looking up a market price.
- Portfolio history charts **skip** money market and other unpriced symbols, since there is no meaningful price series to graph.

If you see a holding valued at its cost basis on the dashboard, it likely means no price data exists for that symbol. You can either:

1. Enter prices manually on the Prices page.
2. Ensure the Finnhub feed is configured and the symbol is supported.
3. Mark the holding as a money market fund if appropriate.

---

## Portfolio History Charts

Portfolio history charts show how your account values have changed over time. They appear in two places:

### Per-Account Chart

On each account's detail page, a chart shows the historical value of that single account. It multiplies your current holdings by the daily price for each symbol to produce a value series.

### Dashboard Combined Chart

On the main dashboard, a combined chart aggregates the value across all your accounts. You can filter by time range to focus on recent performance or view the full history.

### How the Charts Work

The charts use your **current holdings** applied against **historical prices**. This means:

- If you bought 100 shares of VTI last month, the chart shows what those 100 shares would have been worth at every historical date where price data exists.
- The chart does not reconstruct past holdings from transaction history — it uses today's positions.
- Symbols without price data are excluded from the chart rather than plotted at zero.

### Time Range Filter

You can filter portfolio history charts by time period (e.g., 1 year, 5 years, all time). This controls the date range of the x-axis without changing the underlying data.
