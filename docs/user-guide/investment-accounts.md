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
| **Roth** | Roth IRA or Roth 401(k). | After-tax contributions with tax-free withdrawals in retirement. |
| **Bank** | Checking or savings account. | Cash holdings, emergency funds. |

The account type affects how WealthView categorizes your assets on the dashboard and how accounts map to projection pools (traditional, Roth, or taxable) in retirement scenarios.

---

## Creating an Account

1. Navigate to **Accounts** in the sidebar.
2. Click **Add Account**.
3. Fill in the required fields:
   - **Name** — A descriptive name (e.g., "Fidelity Brokerage", "Vanguard Roth IRA").
   - **Type** — Select from brokerage, IRA, 401(k), Roth, or bank.
   - **Institution** — The financial institution (e.g., "Fidelity", "Schwab"). This is optional but helps organize your accounts.
4. Click **Save**.

Your new account appears in the account list, ready for transactions.

---

## Recording Transactions

Transactions are the individual events that happen in your accounts. Navigate to an account's detail page and click **Add Transaction** to record one.

### Transaction Types

| Type | What It Represents | Required Fields |
|------|-------------------|-----------------|
| **Buy** | Purchasing shares of a security. | Date, symbol, quantity, amount |
| **Sell** | Selling shares of a security. | Date, symbol, quantity, amount |
| **Dividend** | A cash dividend received from a security. | Date, symbol, amount |
| **Deposit** | Cash deposited into the account. | Date, amount |
| **Withdrawal** | Cash withdrawn from the account. | Date, amount |
| **Opening Balance** | The starting balance when you first add the account. | Date, symbol (optional), quantity (optional), amount |

### Field Meanings

- **Date** — When the transaction occurred. Use the actual trade date, not the settlement date.
- **Type** — One of the six transaction types above.
- **Symbol** — The ticker symbol of the security (e.g., "AAPL", "VOO"). Required for buy, sell, and dividend transactions. Leave blank for cash deposits and withdrawals.
- **Quantity** — The number of shares bought or sold. Can be fractional (e.g., 2.5 shares). Required for buy and sell transactions.
- **Amount** — The dollar value of the transaction. For buys, this is the total cost. For sells, this is the total proceeds. For dividends, this is the dividend payment received.

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

When you record buy and sell transactions, WealthView automatically calculates your holdings for each symbol in the account. You do not need to manually enter your current positions.

The computation works as follows:

1. For each unique symbol in the account, WealthView aggregates all buy and sell transactions.
2. **Quantity** = total shares bought minus total shares sold.
3. **Cost basis** = total dollar amount of buys minus total dollar amount of sells.

This means your holdings always reflect the net result of all your transactions. When you add, edit, or delete a transaction, holdings are automatically recomputed.

### Example

If you have three transactions for AAPL:
- Buy 10 shares at $150 ($1,500)
- Buy 5 shares at $160 ($800)
- Sell 3 shares at $170 ($510)

Your AAPL holding would show:
- **Quantity:** 12 shares (10 + 5 - 3)
- **Cost basis:** $1,790 ($1,500 + $800 - $510)

---

## Manual Overrides

In most cases, letting WealthView compute holdings from transactions is the best approach. However, sometimes you need to override the computed values — for example, when you have an existing account with years of history and do not want to enter every past transaction.

When a holding has the **manual override** flag set:

- The quantity and cost basis you enter are used as-is, regardless of transactions.
- New transactions will not overwrite the manual values.
- A visual indicator shows that the holding is manually managed.

**When to use manual overrides:**

- You are setting up an existing account and want to enter current positions without reconstructing all historical transactions.
- Your brokerage reports a cost basis that differs from WealthView's calculation (e.g., due to wash sale adjustments or corporate actions).
- You transferred shares between accounts and want to preserve the original cost basis.

To set a manual override, edit a holding from the account detail page and toggle the manual override option.

---

## Money Market Funds

Some securities, like SPAXX (Fidelity's money market fund), do not have publicly traded prices. They maintain a stable $1.00 NAV and earn interest instead.

When you mark a holding as a **money market fund**:

- WealthView does not look up market prices for that symbol.
- Instead, it values the holding using the **money market rate** you specify.
- Portfolio history charts skip money market holdings since there is no meaningful price history to graph.

To configure a money market holding:

1. Navigate to the account detail page.
2. Find the holding and click to edit it.
3. Enable the **Money Market** flag.
4. Enter the **Money Market Rate** (the annual yield, e.g., 0.045 for 4.5%).

---

## Editing and Deleting Transactions

You can edit or delete any transaction from the account detail page. Click on a transaction to edit its fields, or use the delete button to remove it.

**Important:** Editing or deleting a transaction triggers an automatic recomputation of holdings for the affected symbol. If you delete a buy transaction, the quantity and cost basis of the corresponding holding will decrease accordingly.

If a holding has the manual override flag set, editing transactions will not change the holding values.

---

## Account Detail Page

When you click on an account from the Accounts list, you see the account detail page with several sections:

### Holdings Table

A table showing every security you hold in the account:

- **Symbol** — The ticker symbol.
- **Quantity** — Number of shares held.
- **Cost Basis** — Your total cost for the position.
- **Market Value** — Current value based on the latest price (quantity times price). If no price is available, falls back to cost basis.
- **Gain/Loss** — The difference between market value and cost basis, both in dollars and as a percentage.
- **Manual Override** indicator if applicable.

### Transactions List

A chronological list of all transactions in the account. You can filter by type, date range, or symbol. Each transaction shows its date, type, symbol, quantity, and amount.

### Portfolio History Chart

A line chart showing the historical value of the account over time. This chart multiplies your holdings by daily prices to show how the account's value has changed. You can filter by time range (1 year, 5 years, all time).

Note that the chart reflects theoretical value — it uses your current holdings applied against historical prices, so it shows what the current portfolio would have been worth at each past date.

### Import Button

A button to import transactions from CSV or OFX files. See the [Data Import](data-import.md) guide for details.

---

## Tips

- **Start with opening balance transactions** if you do not want to enter your full transaction history. Record an opening_balance transaction for each symbol with the current quantity and cost basis.
- **Use imports for historical data.** Downloading CSV files from your brokerage is much faster than manual entry. See [Data Import](data-import.md).
- **Check your cost basis** against your brokerage statements periodically. Differences may arise from wash sales, corporate actions, or transfer basis adjustments.
- **Group related accounts** by using consistent institution names. This helps organize the Accounts list view.
