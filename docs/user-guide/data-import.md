[← Back to README](../../README.md)

# Data Import

WealthView can import transactions directly from CSV and OFX/QFX files downloaded from your brokerage. This is the fastest way to populate your accounts with historical data.

---

## Supported Formats

| Format | Source | What It Imports |
|--------|--------|----------------|
| **Fidelity CSV** | Fidelity Investments activity export | Transactions (buys, sells, dividends, etc.) |
| **Vanguard CSV** | Vanguard transaction history export | Transactions |
| **Schwab CSV** | Charles Schwab transaction export | Transactions |
| **Generic CSV** | Any brokerage (manual column mapping) | Transactions |
| **OFX/QFX** | Any brokerage supporting Open Financial Exchange | Transactions |

---

## Importing from Fidelity

### Downloading Your Data

1. Log in to Fidelity.com.
2. Navigate to **Accounts & Trade** > **Account Activity**.
3. Select the account and date range you want to export.
4. Click **Download** and choose **CSV** format.
5. Save the file to your computer.

### Uploading to WealthView

1. Navigate to **Accounts** in the sidebar and click on the target account.
2. Click the **Import** button.
3. Select **Fidelity** as the format.
4. Choose your downloaded CSV file.
5. Click **Upload**.

The Fidelity parser expects columns for date, action (buy/sell/dividend), symbol, quantity, price, and amount. The parser automatically maps Fidelity's column names and action descriptions to WealthView transaction types.

---

## Importing from Vanguard

### Downloading Your Data

1. Log in to Vanguard.com.
2. Navigate to **Transaction History**.
3. Select the account, date range, and choose **CSV download**.
4. Save the file.

### Uploading to WealthView

1. Navigate to the target account in WealthView.
2. Click **Import** and select **Vanguard** as the format.
3. Choose your CSV file and click **Upload**.

The Vanguard parser handles Vanguard's specific column layout and transaction type naming.

---

## Importing from Schwab

### Downloading Your Data

1. Log in to Schwab.com.
2. Navigate to **Accounts** > **History**.
3. Select the account and date range.
4. Click **Export** and choose CSV.
5. Save the file.

### Uploading to WealthView

1. Navigate to the target account in WealthView.
2. Click **Import** and select **Schwab** as the format.
3. Choose your CSV file and click **Upload**.

---

## Generic CSV Import

If your brokerage is not Fidelity, Vanguard, or Schwab, you can use the generic CSV format. Your file needs the following columns:

| Column | Required | Description |
|--------|----------|-------------|
| **date** | Yes | Transaction date (YYYY-MM-DD or MM/DD/YYYY) |
| **type** | Yes | One of: buy, sell, dividend, deposit, withdrawal, opening_balance |
| **symbol** | For buy/sell/dividend | Ticker symbol (e.g., AAPL) |
| **quantity** | For buy/sell | Number of shares |
| **amount** | Yes | Dollar amount of the transaction |

The column headers must match these names (case-insensitive). Extra columns are ignored.

### Example Generic CSV

```
date,type,symbol,quantity,amount
2025-01-15,buy,VTI,50,11000.00
2025-01-20,buy,BND,100,7500.00
2025-02-01,dividend,VTI,,125.00
2025-03-01,sell,BND,25,1900.00
```

---

## OFX/QFX Import

### What Is OFX?

OFX (Open Financial Exchange) is a standardized data format used by banks and brokerages. QFX files are Quicken's variant of OFX. Most major brokerages offer OFX or QFX downloads.

### Downloading OFX Files

The download location varies by brokerage. Look for options labeled "Download to Quicken," "QFX download," or "OFX export" in your account's transaction history or activity page.

### Uploading to WealthView

1. Navigate to the target account in WealthView.
2. Click **Import** and select **OFX** as the format.
3. Choose your .ofx or .qfx file and click **Upload**.

WealthView parses the OFX XML structure and extracts transaction data including dates, types, symbols, quantities, and amounts.

---

## Positions Import

In addition to importing transaction history, you can import current **positions** (holdings). This is useful when you want to set up an account with its current state without entering all historical transactions.

A positions import creates holdings directly rather than creating transactions that compute into holdings. The imported holdings will have the manual override flag set since they were not derived from transactions.

---

## Deduplication

WealthView automatically prevents duplicate transactions during import. Each transaction row is assigned a **content hash** — a SHA-256 hash computed from the transaction's key fields (date, type, symbol, quantity, amount).

When you import a file:

- If a transaction with the same content hash already exists in the account, it is silently skipped.
- Only genuinely new transactions are created.
- The import summary shows how many rows were imported vs. skipped.

This means you can safely re-import the same file without creating duplicates. It also means that if you download overlapping date ranges, the overlap is handled automatically.

---

## Import Job History

Every import creates a job record that tracks the import's progress and results. You can view past imports from the account detail page.

Each import job shows:

| Field | Description |
|-------|-------------|
| **Status** | The current state: `pending` (queued), `processing` (in progress), `completed` (finished successfully), or `failed` (error occurred). |
| **Format** | Which parser was used (fidelity, vanguard, schwab, generic, ofx). |
| **Total Rows** | The number of transaction rows found in the file. |
| **Imported Rows** | The number of new transactions created. |
| **Skipped Rows** | The number of rows skipped as duplicates. |
| **Failed Rows** | The number of rows that could not be parsed. |
| **Created At** | When the import was initiated. |

---

## Troubleshooting

### "Wrong format" or unexpected parse errors

If your import fails or produces unexpected results, double-check that you selected the correct format. A Fidelity CSV uploaded with the Schwab parser will likely fail because the column layouts differ.

**Fix:** Delete the failed import's transactions (if any were created) and re-import with the correct format.

### "Missing required columns"

The parser could not find expected column headers in your file. This usually means:

- The file has a different column layout than expected. Try the **Generic CSV** format instead.
- The file has extra header rows or metadata above the actual column headers. Open the file in a text editor and remove any lines above the header row.
- The file is not actually a CSV (some brokerages export tab-separated or Excel files).

### "All rows rejected as duplicates"

Every transaction in the file already exists in the account. This is normal if you have already imported this file or a file covering the same transactions.

If you believe the transactions are genuinely new, check whether a previous import already loaded them. Navigate to the account's transaction list and look for the transactions in question.

### Import shows "failed" status

The import encountered an error it could not recover from. Common causes:

- The file is corrupted or truncated.
- The file uses an encoding that the parser cannot read.
- An OFX file has malformed XML.

Try downloading a fresh copy of the file from your brokerage and re-importing.

### Transactions imported to the wrong account

WealthView imports into whichever account you initiated the import from. If transactions ended up in the wrong account, delete them and re-import from the correct account page.
