[← Back to README](../../README.md)

# Admin Area and Data Export

Administrative settings all live in one place: the **Admin** area. Data export has its own page. This guide covers both.

> **There is no longer a separate Settings page.** Everything that used to live under Settings, the audit log, and the price admin screens has been consolidated into `/admin`. The old URLs still work — `/settings`, `/audit-log`, and `/admin/prices` all redirect to the Admin area.

---

## Finding Your Way Around

The sidebar has these entries, in order:

**Dashboard** · **Accounts** · **Projections** · **Spending Profiles** · **Income Sources** · **Properties** · **Prices** · **Export** · **Admin**

**Admin** only appears if you have the **admin** or **super-admin** role. Your email and role are shown at the bottom of the sidebar, along with **Logout**.

---

## The Admin Area

Open **Admin** in the sidebar. A secondary sidebar on the left switches between sections. Which sections you see depends on your role:

| Section | Who sees it |
|---------|-------------|
| **Dashboard** | Super-admin only |
| **Users** | Admin and super-admin |
| **Tenants** | Super-admin only |
| **Prices** | Admin and super-admin |
| **Stock Splits** | Super-admin only |
| **Exchange Rates** | Admin and super-admin |
| **Invite Codes** | Admin and super-admin |
| **System Config** | Super-admin only |
| **Audit Log** | Admin and super-admin |

Super-admins land on **Dashboard** by default; everyone else lands on **Users**.

---

## Dashboard (Super-Admin)

A system health overview with stat cards:

- **Total Users**, **Active Users (30d)**, **Tenants**
- **Accounts**, **Holdings**, **Transactions**, **Database Size**
- **Symbols Tracked**, **Stale Symbols**

Two buttons let you kick off a price refresh immediately: **Sync Finnhub** and **Sync Yahoo**. The Yahoo sync reports how many prices were inserted and updated.

Below that, **Recent Login Activity** lists the last 50 login attempts with **Email**, **Time**, **IP Address**, and a **Success** or **Failed** status badge. Worth glancing at occasionally — a run of failures from an address you don't recognise is the kind of thing you want to notice.

---

## Users

Lists everyone you can manage. What you see depends on your role.

**As an admin**, you see the users in your own tenant: **Email**, **Role**, join date, and a **Remove** action. The role dropdown offers **Admin**, **Member**, and **Viewer**.

**As a super-admin**, you see every user across every tenant, with extra **Tenant** and **Status** columns (**Active** / **Disabled**) and three actions per row: **Reset PW**, **Deactivate** / **Activate**, and **Delete**. The role dropdown adds **Super Admin**.

### Roles

| Role | Permissions |
|------|------------|
| **Viewer** | Read-only access to the tenant's data. |
| **Member** | Can create and edit accounts, transactions, properties, projections, and exchange rates. |
| **Admin** | All member permissions, plus user management, invite codes, and price administration. |
| **Super Admin** | Everything, across all tenants. |

Changing a role takes effect **as soon as you pick it** from the dropdown — there is no separate save step.

### Resetting a Password (Super-Admin)

Click **Reset PW** on a user's row. A **Reset Password** dialog appears — *"Set a new password for {email}"* — with a field for the new password.

### Removing a User

Click **Remove** (or **Delete** as super-admin). You'll be asked to confirm: *"Remove this user? This cannot be undone."*

Removing a user revokes their access but does **not** delete the tenant's data. Accounts, transactions, properties, and projections all belong to the tenant, not the individual, so they remain intact.

---

## Tenants (Super-Admin)

Create and manage tenants — the isolation boundary for all data in WealthView.

**Create Tenant** takes a tenant name. The list below shows **Name**, **Users**, **Accounts**, **Status** (**Active** / **Disabled**), and **Created**, with a **Disable** / **Enable** toggle per row.

---

## Prices

Four sub-tabs for managing market price data.

### Finnhub Sync

**Sync All Holdings** fetches the latest prices for every symbol you hold. Below it, **Price Sync Status** lists each **Symbol** with its **Latest Date**, **Source**, and a **Stale** or **Current** badge — a quick way to spot symbols that have quietly stopped updating.

### Yahoo Finance

A fallback for symbols Finnhub doesn't cover. The tab carries a warning worth heeding:

> *Yahoo Finance scraping may break without notice. Use as a fallback for symbols Finnhub doesn't cover.*

Two options:

- **Sync All Holdings from Yahoo** — same as the Finnhub sync, different source.
- **Fetch Specific Symbols** — enter comma-separated symbols (e.g. `FXAIX, VBTLX, BND`) and a **From** / **To** date range (defaulting to the last 30 days), then **Fetch Preview**. You get a preview table of **Symbol**, **Date**, and **Close Price** before committing with **Save All**. Nothing is written until you save.

### CSV Upload

For prices you have from somewhere else entirely. The expected format:

> *CSV with a header row and columns `symbol`, `date`, `close_price`. Date format: YYYY-MM-DD.*

After upload you get a count of imported prices and a list of any rows that failed.

### Browse

Look up price history for a single symbol. Enter a **Symbol** and a **From** / **To** range, then **Search**. You get a chart plus a table of **Date**, **Close Price**, and **Source**, with a **Delete** action per row for removing a bad data point.

---

## Stock Splits (Super-Admin)

WealthView detects stock splits automatically and adjusts your transactions, holdings, and historical prices so your cost basis and share counts stay correct across a split.

**Sync from Finnhub** — *"Splits are detected automatically every night at 02:00. You can also trigger a manual sync now."* The **Sync now** button reports how many new splits were applied out of how many were discovered, and how many symbols were scanned.

**Add manual split** — for when automatic detection isn't enough:

> *Use this when Finnhub doesn't cover the symbol or you need to record a split historically. Numerator:denominator means new-shares-per-old-shares — a 4:1 AAPL split is numerator=4, denominator=1.*

Enter the symbol, effective date, numerator, and denominator, then **Add split**.

**Applied splits** lists everything that's been applied: **Symbol**, **Effective date**, **Ratio**, **Source** (Finnhub, Yahoo, Manual, or Backfill), and when it was **Applied**. Each row has an **Un-apply** action, which asks first: *"Un-apply {symbol} split on {date}? Transactions and prices will be restored to their pre-split values."*

---

## Exchange Rates

If any of your accounts are denominated in a currency other than USD, you need an exchange rate for that currency here. Without one, WealthView can't aggregate that account into your net worth and will tell you so.

Click **Add Currency** and provide two things:

- **Currency Code** — a three-letter code, e.g. `EUR`. It's uppercased automatically.
- **The rate** — the field is labelled dynamically as `1 EUR = ? USD`. Enter what one unit of that currency is worth in USD.

The table shows each **Currency**, its **Rate to USD** (displayed as `1 EUR = 1.0800 USD`), and **Last Updated**, with **Edit** and **Delete** actions.

Some rules worth knowing:

- **You cannot add a rate for USD.** It's always 1.0.
- **You cannot add the same currency twice.**
- **You cannot delete a rate that's still in use.** If accounts still use that currency you'll get an error telling you how many.
- Rates are **per-tenant** and **manual** — WealthView does not fetch live FX rates. Update them yourself when you want fresher numbers.

Conversion happens at display and aggregation time: your dashboard, portfolio history, and projections convert non-USD balances to USD using these rates. The underlying account data stays in its own currency.

Empty state: *"No exchange rates configured. All accounts use USD."*

---

## Invite Codes

New users register with an invite code. WealthView does not allow open registration.

### Generating a Code

1. Go to **Admin → Invite Codes**.
2. Pick an expiry from the **Expires in:** dropdown — **1 day**, **7 days** (default), **30 days**, or **90 days**.
3. Click **Generate Code**.
4. Click **Copy** on the new row and send the code to whoever you're inviting.

### Code Rules

- Each code is **single-use** — it's consumed the moment someone registers with it.
- Codes expire after the window you chose at generation time.
- A code can be **revoked** before it's used, which invalidates it immediately.

### The Table

Columns: **Code**, **Created By**, **Created**, **Expires**, **Status**, **Used By**, and actions.

**Status** is one of **Revoked**, **Used**, **Expired**, or **Active**, evaluated in that order — a revoked code shows as Revoked even if it has also expired.

Every row has **Copy**. Only **Active** rows have **Revoke**.

Once at least one code has been consumed, a **Delete Used Codes** button appears for tidying up. It confirms first: *"Delete all used invite codes? This cannot be undone."*

---

## System Config (Super-Admin)

Runtime configuration you can change without restarting the app, grouped into three cards:

**API Keys**
- `finnhub.api-key` — masked as `********` with a **Show** / **Hide** toggle.
- `zillow.scraper.enabled` — a toggle switch rather than a text field.

**Application Settings**
- `cors.allowed-origins`
- `jwt.access-token-expiry`
- `jwt.refresh-token-expiry`

**Price Sync**
- `finnhub.sync-schedule`
- `finnhub.rate-limit-ms`
- `yahoo.rate-limit-ms`

Anything else the server reports appears under an **Other** card. Editable values have an **Edit** button that turns the row into a text field with **Save** and **Cancel**.

---

## Audit Log

A record of every change made in your tenant.

Use **Filter by type:** to narrow to **account**, **transaction**, **holding**, **property**, **user**, or **tenant** — or leave it on **All**.

Columns: **Time**, **Action**, **Entity Type**, and **Details**. Fifty entries per page, with **Previous** / **Next** navigation and a total count at the bottom.

---

## Security Features Without a Web UI

Two security features exist in the WealthView backend but have **no screens in the web app** yet. They are available through the API only:

- **Multi-factor authentication (TOTP)** — setup, verification, recovery codes, status, and disable all exist as API endpoints under `/api/v1/auth/mfa`, and the login flow supports an MFA challenge. There is no setup wizard or QR code screen in the web UI.
- **Session management** — you can list your active sessions, revoke one, or revoke all sessions except your current one via `/api/v1/auth/sessions`. There is no session list screen in the web UI.

Similarly, **notification preferences** (for large transactions, completed imports, and failed imports) exist as an API but have no settings screen. Preferences default to enabled.

If you need any of these today, you'll need to call the API directly.

---

## Data Export

Navigate to **Export** in the sidebar.

### Full Export (JSON)

> *Download all your data (accounts, transactions, holdings, properties) as a single JSON file.*

Click **Download JSON**. You get `wealthview-export.json` containing four top-level sections:

- `accounts`
- `transactions`
- `holdings`
- `properties`

**Note what isn't in there:** projection scenarios, spending profiles, income sources, property valuations, and property expenses are **not** included in the JSON export. For a genuinely complete backup — everything, including projections — use a database backup via `./wv backup` instead.

### CSV Export

> *Download individual data tables as CSV files.*

Four buttons, one per table:

| Export | Columns |
|--------|---------|
| **accounts** | `id, name, type, institution, created_at` |
| **transactions** | `id, account_id, date, type, symbol, quantity, amount, created_at` |
| **holdings** | `id, account_id, symbol, quantity, cost_basis, is_manual_override, as_of_date` |
| **properties** | `id, address, purchase_price, purchase_date, current_value, mortgage_balance, property_type` |

Two things to be aware of: the accounts CSV contains **no balance column**, and the holdings CSV contains **cost basis but no market value** — market values are computed from current prices at display time rather than stored.

CSV values that begin with `=`, `+`, `-`, `@`, a tab, or a carriage return are prefixed with an apostrophe. That's deliberate: it stops a spreadsheet from interpreting an exported value as a formula, which is a real security problem with CSV files and not a corruption of your data.

### Use Cases

- **Spreadsheet analysis** — pull the CSVs into Excel or Google Sheets for pivot tables and custom charts.
- **Tax preparation** — the transactions CSV is the one your accountant will want.
- **Migration** — the JSON export gives you your core records in a portable format.
- **Backup** — for a real backup, use `./wv backup` rather than the export page. It captures the entire database, projections included.
