[← Back to README](../../README.md)

# Data Import

WealthView can import transactions and positions directly from CSV and OFX/QFX files downloaded from your brokerage. This is the fastest way to populate your accounts with historical data.

---

## Getting to the Import Screen

Imports always target one specific account.

1. Click **Accounts** in the sidebar and open the account you want to load data into.
2. Click the blue **Import** button.

You land on the **Import** page for that account. It has two tabs and, below them, a shared **Import History** table.

| Tab | What It Does |
|-----|--------------|
| **Transaction History** | Adds buys, sells, dividends, and cash movements to what is already there. Duplicates are skipped. |
| **Current Positions** | Replaces the account's entire contents with a snapshot of what you hold today. |

Uploads are limited to 10 MB per file.

---

## Supported Formats

Everything on the **Transaction History** tab is chosen from a single dropdown:

| Dropdown Option | Source | File Type |
|-----------------|--------|-----------|
| **Generic CSV** (default) | Any brokerage, in WealthView's own column layout | `.csv` |
| **Fidelity** | Fidelity Investments activity export | `.csv` |
| **Vanguard** | Vanguard transaction history export | `.csv` |
| **Schwab** | Charles Schwab transaction export | `.csv` |
| **OFX / QFX** | Any brokerage supporting Open Financial Exchange | `.ofx`, `.qfx` |

The **Current Positions** tab currently supports one format: **Fidelity** (the Fidelity positions/holdings CSV).

The file picker filters to the right extension automatically when you change the dropdown.

---

## Importing Transaction History

1. Open the **Transaction History** tab.
2. Choose your format from the dropdown.
3. Use the file picker to select your download.
4. Click **Upload**.

A toast confirms the result, e.g. *"Imported: 142 successful, 0 failed"*, and the Import History table refreshes.

### Fidelity

Download from Fidelity.com under **Accounts & Trade → Account Activity**: pick the account and date range, then **Download** as CSV.

The parser skips everything above the row containing **Run Date**, then reads the `Run Date`, `Action`, `Symbol`, `Quantity`, and `Amount ($)` columns. Dates are `MM/DD/YYYY`. Actions are matched like this:

| Fidelity Action | Becomes |
|-----------------|---------|
| YOU BOUGHT | Buy |
| REINVESTMENT | Buy |
| YOU SOLD | Sell |
| DIVIDEND RECEIVED | Dividend |
| ELECTRONIC FUNDS TRANSFER | Deposit or Withdrawal, depending on the sign of the amount |

### Vanguard

Download from Vanguard.com under **Transaction History**, choosing CSV.

The parser skips everything above the row containing **Trade Date**, then reads `Trade Date`, `Transaction Type`, `Symbol`, `Shares`, and `Net Amount`. Dates are `MM/DD/YYYY`. Buy, Sell, Dividend, Reinvestment, short- and long-term capital gains, incoming/outgoing transfers, and sweep in/out are all recognized.

### Schwab

Download from Schwab.com under **Accounts → History**, exporting as CSV.

The parser skips everything above the row starting with **Date**, then reads `Date`, `Action`, `Symbol`, `Quantity`, and `Amount`. Dates are `MM/DD/YYYY`. Schwab's long action vocabulary is mapped for you — the various dividend flavours become Dividend, the reinvest actions become Buy, bank and credit interest become Dividend, margin interest becomes Withdrawal, and MoneyLink transfers, wires, and journals become Deposit or Withdrawal based on the sign. The trailing "Total" row and any malformed rows at the end of the file are skipped silently.

### Generic CSV

Use this when your brokerage is not one of the three above. The file must have a **header row** (its contents are ignored — it is simply skipped) followed by data rows with these five columns **in this exact order**:

| Position | Column | Required | Description |
|----------|--------|----------|-------------|
| 1 | date | Yes | `YYYY-MM-DD` only |
| 2 | type | Yes | buy, sell, dividend, deposit, withdrawal, or opening_balance |
| 3 | symbol | For buy/sell/dividend | Ticker symbol (e.g., AAPL) |
| 4 | quantity | For buy/sell | Number of shares; may be blank |
| 5 | amount | Yes | Dollar amount, as a plain number |

Column *order* is what matters — the header names are never inspected, so a file whose columns are in a different order will import wrong data rather than failing. Transaction types are matched case-insensitively, so `BUY` and `buy` both work.

#### Example Generic CSV

```
date,type,symbol,quantity,amount
2025-01-15,buy,VTI,50,11000.00
2025-01-20,buy,BND,100,7500.00
2025-02-01,dividend,VTI,,125.00
2025-03-01,sell,BND,25,1900.00
```

### OFX / QFX

OFX (Open Financial Exchange) is a standardized format used by banks and brokerages; QFX is Quicken's variant. Look for a download option labelled "Download to Quicken", "QFX download", or "OFX export" in your brokerage's activity page.

WealthView reads both investment and banking statements:

- **Investment transactions** — buys and sells become Buy and Sell, reinvested income becomes a Buy, and other income becomes a Dividend. Ticker symbols are resolved from the security list embedded in the file.
- **Banking transactions** — credits, deposits, and direct deposits become Deposit; debits, checks, payments, point-of-sale, ATM, and direct debits become Withdrawal; dividends and interest become Dividend. Anything else falls back to the sign of the amount.

Transaction types the parser doesn't recognize are skipped rather than failing the import.

---

## Importing Current Positions

The **Current Positions** tab loads a snapshot of what you hold right now, which is handy for standing up an account without reconstructing years of trades.

> **This tab is destructive.** An orange banner and a confirmation dialog both warn you: importing positions **deletes every existing transaction and holding in the account** before loading the snapshot. There is no undo.

1. Open the **Current Positions** tab.
2. Leave the format on **Fidelity**.
3. Choose your positions CSV.
4. Click the red **Replace & Import** button and confirm the dialog.

Under the hood, each position becomes an **opening balance** transaction dated from the "Date downloaded" line in the file's footer (today's date if that line is missing), and holdings are then computed from those transactions in the normal way. Cash held in the Fidelity core position (FCASH) is recorded as a Deposit instead. Positions whose cost basis Fidelity reports as `--` fall back to the snapshot's current value.

---

## Deduplication

WealthView automatically prevents duplicate transactions on the **Transaction History** tab. Each row is fingerprinted with a hash of its date, type, symbol, quantity, and amount, and compared against the transactions already in that account.

- If a transaction with the same fingerprint already exists in the account, it is silently skipped.
- Only genuinely new transactions are created.
- Skipped duplicates are counted separately — they are **not** failures.

This means you can safely re-import the same file without creating duplicates, and overlapping date ranges sort themselves out.

Two caveats worth knowing:

- The fingerprint is exact. If your brokerage rounds an amount differently between two exports, the same trade will look like two different transactions.
- Two genuinely separate but identical trades on the same day — same symbol, same quantity, same amount — are indistinguishable, so the second one is treated as a duplicate and skipped. Add it by hand with a slightly different amount if this matters to you.

---

## Import History

Every import — from any account in your tenant — is recorded in the **Import History** table at the bottom of the page.

| Column | Description |
|--------|-------------|
| **Date** | When the import ran. |
| **Source** | `csv` for any of the four CSV formats, `ofx` for OFX/QFX, `positions` for a positions snapshot. It does not record which broker layout you picked. |
| **Status** | `completed` once the import has finished. |
| **Total** | Rows found in the file — successfully parsed rows plus rows that could not be parsed. |
| **Success** | New transactions created. |
| **Failed** | Rows that could not be parsed or could not be saved. |

Duplicates do not appear in any column. A re-import of a file you have already loaded therefore shows a healthy Total with **Success: 0** and **Failed: 0** — that is the expected result, not an error.

An import that fails outright (a corrupt file, an unreadable format) leaves no history row at all, because the whole operation is rolled back.

---

## Troubleshooting

### "Unsupported file type"

The browser reported a content type WealthView doesn't accept. Make sure you are uploading the raw `.csv`, `.ofx`, or `.qfx` file rather than a `.xls`/`.xlsx` workbook or a zip archive.

### Wrong format selected

If your import produces zero rows or a pile of failures, check the dropdown. A Fidelity CSV uploaded as Schwab will not find the columns it expects, because each broker parser looks for a different header row.

**Fix:** delete any transactions that were created and re-import with the correct format.

### Everything came back as 0 successful, 0 failed

Every transaction in the file already exists in the account. This is normal if you have already imported this file, or a file covering the same trades.

If you believe the transactions are genuinely new, open the account and check the transaction list for the dates in question.

### High "Failed" count

Failed rows are usually one of:

- **Generic CSV with the wrong column order or a non-ISO date.** The generic parser reads the date from the first column and only accepts `YYYY-MM-DD`. Reorder the columns or convert the dates.
- **An action the broker parser doesn't recognize.** Brokers add new action names over time. Rows with an unknown action are reported as errors rather than guessed at; you can add those transactions manually.
- **Extra header/metadata rows.** Fidelity, Vanguard, and Schwab exports normally carry a preamble, and the parsers skip it — but only if the real header row is intact. If you have edited the file, make sure the header line survived.

### Transactions landed in the wrong account

WealthView imports into whichever account you started the import from. If transactions ended up in the wrong place, delete them and re-import from the correct account's Import page.

### A positions import wiped data I wanted

There is no undo. Restore from a backup (`./wv restore`) if the data mattered — and prefer the **Transaction History** tab for anything additive.
