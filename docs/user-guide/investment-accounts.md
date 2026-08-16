[← Back to README](../../README.md)

# Investment Accounts

Accounts are the foundation of WealthView. Each account represents a real financial account — a brokerage, retirement account, or bank account — and contains the transactions and holdings that make up your portfolio.

---

## Account Types

When creating an account, you choose one of five types:

| Type | Description | Typical Use |
|------|-------------|-------------|
| **Brokerage** | A standard taxable investment account. | Stocks, ETFs, mutual funds held outside retirement accounts. |
| **IRA** | Traditional Individual Retirement Account. | Pre-tax retirement savings with tax-deferred growth. |
| **401(k)** | Employer-sponsored retirement plan. | Workplace retirement savings, often with employer match. |
| **Roth IRA** | Roth IRA or Roth 401(k). | After-tax contributions with tax-free withdrawals in retirement. |
| **Bank** | Checking or savings account. | Cash holdings, emergency funds. |

The account type matters in three places: bank accounts count as **Cash** on the dashboard while every other type counts as **Investments**; the allocation pie chart groups by type; and retirement scenarios map each account into a projection pool (traditional, Roth, or taxable).

Bank accounts are treated as pure cash — their balance comes from deposit and withdrawal transactions, not from holdings, and they are excluded from portfolio history charts.

---

## Creating an Account

1. Click **Accounts** in the sidebar.
2. Click **New Account** (top right). Viewers do not see this button.
3. Fill in the four fields in the **Create Account** panel:
   - **Name** — A descriptive name (e.g., "Fidelity Brokerage", "Vanguard Roth IRA").
   - **Type** — Brokerage, IRA, 401(k), Roth IRA, or Bank.
   - **Institution** — The financial institution (e.g., "Fidelity", "Schwab"). Optional, but it shows on the account card and helps you keep things organized.
   - **Currency** — A three-letter code, defaulting to `USD`. Typing lower case is fine; the field upper-cases as you go.
4. Click **Create**.

Your new account appears as a card in the account list showing its balance, institution, creation date, a colour-coded type badge, and — if the currency is anything other than USD — a currency badge.

To change any of those fields later, click **Edit** on the account card, adjust, and click **Save**. **Delete** removes the account after a confirmation prompt.

### A Note on Non-USD Accounts

Before you can use a currency other than USD, an admin must add a to-USD rate under **Admin → Exchange Rates**. Without a rate, anything that adds accounts together (the dashboard, net worth, the combined chart) will fail for that account with a *"No exchange rate found"* message. The account's own card and detail page always show its native currency; conversion only happens where totals are combined.

---

## Recording Transactions

Transactions are the individual events that happen in your accounts. Open an account and click **Add Transaction** in the Transactions panel. A single-row form appears with five inputs, left to right.

### Transaction Types

The **Type** dropdown offers five choices:

| Type | What It Represents | What You Normally Fill In |
|------|-------------------|---------------------------|
| **Buy** | Purchasing shares of a security. | Date, symbol, quantity, amount |
| **Sell** | Selling shares of a security. | Date, symbol, quantity, amount |
| **Dividend** | A cash dividend, interest payment, or capital-gain distribution. | Date, symbol, amount |
| **Deposit** | Cash into the account. | Date, amount |
| **Withdrawal** | Cash out of the account. | Date, amount |

There is a sixth type, **opening balance**, which behaves exactly like a buy when holdings are computed. It is not offered in the dropdown — it is created for you by a positions import (see [Data Import](data-import.md)) for positions with no purchase history.

### Field Meanings

- **Date** — When the transaction occurred. Use the actual trade date, not the settlement date. Required.
- **Type** — One of the five types above.
- **Symbol** — The ticker symbol of the security (e.g., "AAPL", "VOO"). Leave blank for cash deposits and withdrawals. A buy or sell with no symbol is accepted, but it will not produce a holding.
- **Quantity** — The number of shares bought or sold. Can be fractional (e.g., 2.5 shares). Must not be negative.
- **Amount** — The dollar value of the transaction, always entered as a positive number. For buys this is the total cost, for sells the total proceeds, for dividends the payment received. Required.

Click **Save** to record it, or **Cancel** to discard.

### Examples

**Buying 50 shares of VTI at $220:**
- Date: 2025-01-15
- Type: Buy
- Symbol: VTI
- Quantity: 50
- Amount: 11000.00

**Receiving a $125 dividend from VOO:**
- Date: 2025-03-31
- Type: Dividend
- Symbol: VOO
- Amount: 125.00

**Depositing cash into a bank account:**
- Date: 2025-01-01
- Type: Deposit
- Amount: 5000.00

---

## How Holdings Auto-Compute

When you record buy and sell transactions, WealthView automatically calculates your holdings for each symbol in the account. You do not need to enter your current positions separately.

The computation works as follows, walking the account's transactions for one symbol in order:

1. **Buys** (and opening balances) add their share count to the quantity and their full dollar amount to the cost basis.
2. **Sells** reduce the quantity, and reduce the cost basis **proportionally** — the basis that remains follows the shares that remain, at average cost.
3. **Dividends, deposits, and withdrawals** move cash only. They never change a share count or a cost basis.
4. If the net quantity ever reaches zero or below, the holding is flattened to zero quantity and zero basis.

### Example

Three transactions for AAPL:
- Buy 10 shares for $1,500
- Buy 5 shares for $800
- Sell 3 shares for $510

After the two buys you hold 15 shares with a $2,300 basis, an average cost of $153.33 per share. Selling 3 shares leaves 12 shares, so the basis becomes 12 × $153.33:

- **Quantity:** 12 shares
- **Cost basis:** $1,840.00

Note that the $510 you actually received is irrelevant to the remaining basis — that is what makes this *average cost* rather than "buys minus sells". The gain or loss on the sale shows up as a higher or lower gain on what you still hold.

---

## Manual Overrides

Sometimes you want to state a position directly instead of deriving it — for instance when you are setting up an account that has years of history you do not want to re-key.

**Editing a holding always turns on its manual-override flag.** From that point on:

- The quantity and cost basis you entered are used as-is.
- Recomputation from transactions **skips** that holding entirely, so adding, editing, or deleting transactions no longer moves it.
- Stock-split adjustments also skip it (see below).
- The holding's detail page shows **Manual Override: Yes**.

**When manual overrides are useful:**

- You are setting up an existing account and want to enter current positions without reconstructing all historical transactions.
- Your brokerage reports a cost basis that differs from WealthView's calculation (e.g., due to wash sale adjustments or corporate actions).
- You transferred shares between accounts and want to preserve the original cost basis.

**How to set one:** from the account detail page, click **Edit** on the holding's row, change **Qty** and/or **Cost Basis**, then click **Save**. You can also do it from the holding's own page via the **Edit Override** button.

There is currently no way to turn an override back off from the web UI. If you want a holding to track its transactions again, the practical route is to delete the holding's data and re-import, or ask an admin to clear the flag in the database.

---

## Money Market Funds

Money market funds like SPAXX hold a stable $1.00 NAV and pay interest rather than trading at a market price, so there is no useful price series for them.

WealthView recognizes these automatically. When a holding's symbol is one of the known money market tickers — **SPAXX, FDRXX, FZFXX, VMFXX, VMMXX, SWVXX, SNVXX, SPRXX** — it is flagged as a money market fund and given a default rate of 4.00%. You do not configure this; there is no toggle in the UI.

What you will notice:

- The holding shows a small **(MM)** marker next to its symbol in the Holdings table.
- Its detail page shows a **Money Market Rate** row.
- Because no price is ever fetched for it, its market value falls back to cost basis.
- In the Theoretical Portfolio History chart it is held at a constant $1.00 per share, and the chart displays a note saying so.

If you hold a money market fund whose ticker is not on that list, it will simply be treated as an ordinary symbol with no price data — valued at cost basis and left out of the history chart. Entering a price for it manually on the Prices page is a reasonable workaround.

---

## Editing and Deleting Transactions

Every transaction row has **Edit** and **Delete** buttons (members, admins, and super-admins only). **Edit** expands the row into the same five-field form; **Delete** removes it immediately, with no confirmation prompt — so aim carefully.

**Important:** editing or deleting a transaction triggers an automatic recomputation of holdings for the affected symbol. If you delete a buy transaction, the quantity and cost basis of the corresponding holding decrease accordingly.

If a holding has the manual override flag set, editing transactions will not change the holding values.

---

## Account Detail Page

Clicking an account from the Accounts list opens its detail page. Below the breadcrumb, name, and a line showing the type and institution, you get:

### Holdings Table

Every security you hold in the account, one row each:

| Column | What It Shows |
|--------|---------------|
| **Symbol** | The ticker, as a link to that holding's own page. Money market funds get a **(MM)** marker. |
| **Qty** | Number of shares held. |
| **Price** | The latest close price on record, or `—` if there is none. |
| **Cost Basis** | Your total cost for the shares you still hold. |
| **Market Value** | Quantity × latest price, or `—` if there is no price. |
| **Gain/Loss** | Market value minus cost basis, in dollars. Green when positive, red when negative. |

A bold **Total** row at the bottom sums cost basis, market value, and gain/loss across the whole account. In that total row, holdings with no price fall back to their cost basis so nothing silently disappears from the sum.

Members, admins, and super-admins get an **Edit** button on each row for inline quantity/cost-basis edits — remember that this sets the manual override flag.

There is no "add holding" button: holdings appear when you record a transaction (or run an import) for a new symbol.

### Import Button

An **Import** button next to the holdings panel opens the import screen for this account. See the [Data Import](data-import.md) guide.

### Theoretical Portfolio History Chart

A shaded area chart titled **Theoretical Portfolio History**. It is shown for every account type except Bank; bank accounts display *"Portfolio history is not available for bank accounts."*

A dropdown in the top right picks the window: 6 Months, 1 Year, **2 Years** (the default), 3 Years, 5 Years, 10 Years, or 20 Years. Below the title, a caption lists the symbols in the chart and repeats the window you chose. Underneath the chart, two figures summarize the period: **Total Growth** in dollars and **Avg. Annual Return** as a percentage.

The word *theoretical* is doing real work here. The chart takes the shares you hold **today** and prices them at each weekly point in the past, so it answers "what would my current portfolio have been worth back then?" — it does **not** replay your actual buying and selling. Symbols with no price data for the window are left out, and if none of your symbols have prices you will see *"No price data available for current holdings."*

### Transactions List

The 50 most recent transactions in the account, showing **Date**, **Type**, **Symbol**, **Qty**, and **Amount**, plus Edit/Delete for members, admins, and super-admins. There is no filtering or search on this list; to see everything for one symbol, click the symbol in the Holdings table instead.

---

## Holding Detail Page

Clicking a symbol in the Holdings table opens a page dedicated to that position:

- **Holding Summary** — Symbol, Quantity, Cost Basis, Manual Override (Yes/No), Money Market Rate (only for money market funds), and As Of Date. The **Edit Override** button turns Quantity and Cost Basis into editable fields.
- **Splits affecting *SYMBOL*** — Any stock splits WealthView has applied to this symbol, with the effective date, the ratio, and how the split was discovered. See [Prices and Valuation](prices-and-valuation.md#stock-splits).
- **Transactions for *SYMBOL*** — Every transaction in this account for this symbol (up to 100), read-only. Edit them from the account page.

---

## Asset Classification

Retirement projections need to know whether each symbol is US stock, international stock, bonds, or cash in order to model returns. WealthView classifies the symbols it recognizes automatically.

When you run a projection that contains symbols it could not classify, an orange notice appears above the results listing them, with a dropdown per symbol. Pick the right asset class and click **Apply & re-run**; your choice is remembered for that symbol from then on. Until you do, those holdings are modeled as **US Stock**.

---

## Tips

- **Use imports for historical data.** Downloading a CSV from your brokerage is far faster than manual entry, and duplicates are skipped automatically. See [Data Import](data-import.md).
- **If you only want today's positions**, use the Current Positions import rather than typing an opening balance for every symbol.
- **Check your cost basis** against your brokerage statements periodically. Differences arise from wash sales, corporate actions, or transfer basis adjustments — and WealthView's average-cost method will not match a brokerage that tracks specific lots.
- **Group related accounts** by using consistent institution names.
- **Watch the manual-override flag.** It is easy to set by accident with an inline edit, and a manually overridden holding quietly stops responding to imports and splits.
