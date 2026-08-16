[← Back to README](../../README.md)

# Portfolio Analysis

WealthView provides a dashboard and per-account views to help you understand your financial position at a glance. This guide explains what each visualization shows and how to interpret it.

---

## Dashboard Overview

When you sign in, the **Dashboard** is the first thing you see. Reading top to bottom, it contains:

1. Four summary cards — Net Worth, Investments, Cash, Property Equity
2. The **Combined Portfolio History** chart
3. The **Snapshot Forward Projection** chart
4. Side by side: the **Accounts** table and the **Allocation** pie chart
5. **Recent stock splits**

Everything on the dashboard is expressed in USD. Balances held in other currencies are converted using the rates configured under Admin → Exchange Rates.

---

## Summary Cards

| Card | What It Sums |
|------|--------------|
| **Net Worth** | Investments + Cash + Property Equity. Rendered larger than the others. |
| **Investments** | Market value of holdings across every non-bank account (brokerage, IRA, 401(k), Roth IRA). |
| **Cash** | Balances of your bank accounts, built from deposit and withdrawal transactions. |
| **Property Equity** | Each property's current value minus its outstanding mortgage balance. |

Investment values use the latest price on record for each symbol; a holding with no price contributes its cost basis instead, so nothing drops out of the total. These figures update whenever prices change, property values are refreshed, or you record new transactions.

---

## Combined Portfolio History

A stacked area chart of where you have been. Two bands:

- **Investments** (blue) — your current holdings valued at historical prices, across all non-bank accounts.
- **Property Equity** (green) — from the property valuations on record.

A dropdown in the top right selects the window: 1, **2** (default), 3, 5, or 10 years. A caption under the title tells you what is included, e.g. *"3 investment accounts + 2 properties"*. Hovering a point shows each band's value plus the combined total for that date.

Points are sampled weekly (Fridays) plus today. As with all of WealthView's history charts, this is a **theoretical** view: it prices the shares you hold *today* at past prices rather than replaying your actual trades. It answers "what would today's portfolio have been worth back then?", which is a good read on how your current allocation behaves and a poor read on your personal rate of return.

If there is nothing to draw you will see *"No portfolio history data available."* — usually because none of your symbols have price history in the selected window. See [Prices and Valuation](prices-and-valuation.md) for how to fix that.

---

## Snapshot Forward Projection

A stacked area chart of where a naive extrapolation would take you. Two bands, **Projected Investments** and **Projected Property Equity**, from today out to a horizon you choose: 5, **10** (default), 15, or 20 years.

The caption states the assumption plainly, for example *"Based on 10-year historical returns (7.4% avg CAGR) • 4 accounts • 1 property"*.

How the number is arrived at:

- For each investment account, WealthView looks at its theoretical history over the past ten years and derives a compound annual growth rate from it.
- Those per-account rates are combined into one portfolio rate, weighted by each account's current value — that is the CAGR shown in the caption.
- Each account is then compounded forward at its own rate.
- Bank accounts are projected flat at 0% growth.
- Properties are projected using their appreciation rate and mortgage amortization.

This is a straight-line extrapolation of past growth, not a retirement plan. It assumes no contributions, no withdrawals, no taxes, no market downturns, and that the last decade repeats itself. For anything you intend to make decisions on — spending, withdrawals, taxes, Social Security, sequence-of-returns risk — use [Retirement Projections](retirement-projections.md) instead, which models all of that properly.

---

## Accounts Table

A plain list of everything you own, with three columns: **Name**, **Type**, and **Balance**.

Properties appear in this table too, listed by street address with the type `property` and their equity as the balance — so the table is a complete inventory rather than investment accounts only. To open an account, use the **Accounts** page in the sidebar; the rows here are not links.

---

## Allocation Chart

A pie chart breaking your net worth down by category. Each slice is one account **type** — brokerage, ira, 401k, roth, bank — plus one slice for **property**. Labels show the category name and its share of net worth as a percentage.

This is the quickest way to spot tax-diversification problems. If 60% of the pie is `401k`, nearly all of your wealth is in pre-tax dollars, which constrains your options in retirement in ways a Roth-heavy or taxable-heavy portfolio would not.

Note that it groups by account type, not by asset class — two brokerage accounts holding wildly different things merge into one slice. For an asset-class view (US stock, international, bonds, cash), look at a projection scenario's allocation instead.

If you have no accounts or properties yet, the card reads *"No allocation data"*.

---

## Recent Stock Splits

A panel listing splits WealthView has detected and applied to symbols in your portfolio, with the symbol, effective date, ratio, and how it was discovered. It exists so a sudden jump in your share count is explainable rather than alarming. See [Prices and Valuation](prices-and-valuation.md#stock-splits) for the full story.

---

## Per-Account Analysis

Clicking an account from the **Accounts** page opens its detail page.

### Theoretical Portfolio History Chart

Each non-bank account gets its own shaded area chart covering just that account, over a window from 6 months to 20 years (default 2 years). Below the chart, two figures summarize the period:

- **Total Growth** — the dollar change between the first and last point in the window.
- **Avg. Annual Return** — that change expressed as an annualized percentage.

Use this to see which accounts are pulling their weight. Bear in mind both figures come from the theoretical calculation, so they describe how today's holdings would have behaved, not what you actually earned.

Bank accounts show *"Portfolio history is not available for bank accounts."* instead — there are no priced holdings to chart.

### Holdings Breakdown

A table listing every security held in the account:

| Column | What It Shows |
|--------|---------------|
| **Symbol** | The ticker, linking to the holding's own page. Money market funds carry an **(MM)** marker. |
| **Qty** | Shares held. |
| **Price** | Latest close price on record, or `—`. |
| **Cost Basis** | Your total cost for the shares you still hold. |
| **Market Value** | Quantity × latest price, or `—`. |
| **Gain/Loss** | Market value minus cost basis, in dollars — green when positive, red when negative. |

A bold **Total** row sums cost basis, market value, and gain/loss for the account. Holdings with no price contribute their cost basis to that total, which is why the Total can exceed the visible Market Value column.

There is no gain/loss percentage column. To judge a position's return proportionally, compare its Gain/Loss against its Cost Basis — a $500 gain on a $1,000 basis (50%) is a far stronger result than $500 on $10,000 (5%).

### Interpreting Gain/Loss

- A **positive** number means the position is worth more than you paid. It is an unrealized gain.
- A **negative** number means the position is below your cost basis. It only becomes a realized loss when you sell.
- A **dash** means WealthView has no price for the symbol and cannot compute a gain at all — that is a data gap, not a flat return.

Remember that WealthView tracks cost basis at **average cost**. If your brokerage uses specific-lot accounting, its gain/loss figures will legitimately differ from these.

---

## Tips for Analysis

- **Check the Price column first.** A holding showing `—` is silently distorting every total that includes it. Fixing prices is usually the highest-value cleanup you can do.
- **Compare account-level charts** to see which accounts are driving overall growth.
- **Watch the allocation chart for concentration risk**, both in a single account type and in tax treatment.
- **Treat the Snapshot Forward Projection as a sketch.** It is one line drawn from ten years of history. The Projections section exists because the real question is more complicated than that.
- **Use the time-range dropdowns** to separate long-term trend from short-term noise — a 10-year window and a 1-year window on the same portfolio often tell opposite stories.
