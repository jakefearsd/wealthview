[← Back to README](../../README.md)

# Getting Started with WealthView

WealthView is a self-hosted personal finance application for tracking investments, rental properties, and retirement projections. It runs entirely on your own infrastructure, so your financial data never leaves your control.

This guide walks you through your first login, core concepts, and how to navigate the application.

---

## Logging In

Open your browser to `http://localhost` (or wherever you deployed the app). You will land on the **Login to WealthView** screen.

1. Enter your **Email** and **Password**. The **Show** / **Hide** button inside the password box lets you check what you typed.
2. Click **Sign In**.

### Docker Demo Mode

If you deployed WealthView using Docker Compose, a demo account is created automatically:

- **Email:** `demo@wealthview.local`
- **Password:** `demo123`

A super-admin account is also available in all deployment modes:

- **Email:** `admin@wealthview.local`
- **Password:** `admin123`

Change these before exposing WealthView to anything but your own machine.

### Registering a New Account

New users register with an **invite code**. An admin generates invite codes from the Admin area and shares them with you. To register:

1. On the login page, click **Register** at the bottom.
2. Fill in the three fields on the **Register for WealthView** form:
   - **Email**
   - **Password** — must be at least 8 characters, and cannot be a well-known/common password.
   - **Invite Code** — paste the code your admin gave you.
3. Click **Register**. You are signed in immediately and dropped on the Dashboard.

There is no separate "name" field — your email is your identity in WealthView.

Invite codes are single-use and expire after 7 days by default (an admin can choose a different expiry when generating one). If your code is invalid, revoked, already used, or expired, you will see *"Invalid or expired invite code"* — ask your admin for a new one.

Everyone who registers with an invite code joins as a **Member**. An admin can promote you afterwards.

> **Two-factor authentication:** the web app currently has no MFA setup or challenge screens. If your deployment enables MFA for an account, sign in from the mobile app instead.

---

## Multi-Tenancy

WealthView supports multiple **tenants** — isolated data spaces for different organizations, households, or individuals. All data within a tenant (accounts, properties, projections) is completely invisible to other tenants.

The invite code you register with determines which tenant you join. Every user in the same tenant shares access to that tenant's financial data, subject to their role permissions.

---

## User Roles

Each user has one of four roles within their tenant:

| Role | What You Can Do |
|------|----------------|
| **Viewer** | View all data (accounts, properties, projections). Cannot create or modify anything — the Edit, Delete, and "New …" buttons are simply not shown. |
| **Member** | Everything a Viewer can do, plus create and edit accounts, transactions, properties, and projections. |
| **Admin** | Everything a Member can do, plus the **Admin** area: manage users, generate invite codes, set exchange rates, and run price syncs. |
| **Super-Admin** | Full system access across all tenants, plus the super-admin-only Admin sections (system dashboard, tenants, stock splits, system config). Used for initial setup and system administration. |

Admins can switch a user between **member** and **admin** from Admin → Users. Super-admin is granted at the system level, not from the UI.

---

## Navigating the Application

The dark sidebar on the left is always visible once you are signed in:

- **Dashboard** — Your financial overview: net worth, portfolio history, a forward projection, account balances, allocation, and recent stock splits.
- **Accounts** — Investment and bank accounts with their holdings, transactions, and import tools.
- **Projections** — Retirement scenario modeling with year-by-year simulations.
- **Spending Profiles** — Define retirement spending needs with essential and discretionary breakdowns.
- **Income Sources** — Model Social Security, pensions, rental income, and other retirement income streams.
- **Properties** — Rental and personal real estate with mortgage tracking, income, expenses, and analytics.
- **Prices** — See the latest price on record for every symbol, and add prices by hand.
- **Export** — Download your data as JSON or CSV for backups or analysis.
- **Admin** — Only shown to admins and super-admins. See below.

At the bottom of the sidebar you will find your email address, your role, and a **Logout** button.

### The Admin Area

Everything that used to live under "Settings" and "Audit Log" now lives on a single **Admin** page with its own left-hand menu. (Old `/settings` and `/audit-log` bookmarks redirect there automatically.)

| Section | Who Can See It | What It Does |
|---------|----------------|--------------|
| **Dashboard** | Super-admin | System-wide stats and login activity |
| **Users** | Admin | List the users in your tenant, change their role, remove them. Super-admins additionally see every tenant's users and can reset passwords or deactivate accounts. |
| **Tenants** | Super-admin | Create and manage tenants |
| **Prices** | Admin | Finnhub sync, Yahoo Finance fetch, price CSV upload, price browser |
| **Stock Splits** | Super-admin | Review, add, and un-apply stock splits |
| **Exchange Rates** | Admin | Set the to-USD rate for each non-USD currency you use |
| **Invite Codes** | Admin | Generate, list, and revoke invite codes |
| **System Config** | Super-admin | Application configuration keys |
| **Audit Log** | Admin | A history of changes made within your tenant |

---

## Your First Ten Minutes

1. **Create an account.** Go to **Accounts** → **New Account**, give it a name and a type. See [Investment Accounts](investment-accounts.md).
2. **Get some data in.** Either add transactions by hand, or open the account and click **Import** to upload a CSV or OFX/QFX file from your brokerage. See [Data Import](data-import.md).
3. **Check your prices.** Visit **Prices** to confirm WealthView has a recent close price for each of your symbols. See [Prices and Valuation](prices-and-valuation.md).
4. **Read the Dashboard.** Net worth, allocation, and the portfolio history chart should now be populated. See [Portfolio Analysis](portfolio-analysis.md).

---

## Key Concepts

### Account Types

WealthView supports five account types, each representing a different kind of financial account:

- **Brokerage** — A standard taxable investment account.
- **IRA** — A traditional Individual Retirement Account (pre-tax contributions, taxed on withdrawal).
- **401(k)** — An employer-sponsored retirement account (pre-tax contributions).
- **Roth IRA** — A Roth IRA or Roth 401(k) (after-tax contributions, tax-free withdrawals).
- **Bank** — A checking or savings account for cash holdings.

### Holdings vs. Transactions

**Transactions** are individual events: buying 10 shares of AAPL, receiving a dividend, depositing cash. They represent what happened and when.

**Holdings** are the current state of what you own. When you record buy and sell transactions, WealthView automatically computes your holdings — the net quantity and cost basis for each symbol in each account.

### Cost Basis

Cost basis tracks how much you paid for the shares you still hold. Buys add their full dollar amount to the basis. Sells reduce it **proportionally**: WealthView uses average cost, so selling a third of a position removes a third of the basis. Cost basis is what unrealized gain/loss is measured against.

### Currency

Each account has its own currency (USD by default). If you hold an account in another currency, an admin adds a to-USD rate under Admin → Exchange Rates, and WealthView converts at display time wherever it adds accounts together — the dashboard, the combined chart, net worth.

### Net Worth

Your net worth in WealthView is the sum of:

- **Investments** — Current market value of all holdings in non-bank accounts (quantity × latest price; cost basis is used when no price exists).
- **Cash** — Bank account balances, built from deposit and withdrawal transactions.
- **Property equity** — Current property values minus outstanding mortgage balances.

All three are converted to USD before being added together.

---

## Next Steps

Now that you understand the basics, explore these guides for specific features:

- [Investment Accounts](investment-accounts.md) — Set up accounts, record transactions, and track holdings.
- [Data Import](data-import.md) — Import transactions from Fidelity, Vanguard, Schwab, or OFX/QFX files.
- [Prices and Valuation](prices-and-valuation.md) — Understand how WealthView values your portfolio.
- [Portfolio Analysis](portfolio-analysis.md) — Read your dashboard and analyze performance.
- [Rental Properties](rental-properties.md) — Track real estate, mortgages, income, and expenses.
- [Retirement Projections](retirement-projections.md) — Model retirement scenarios with tax-aware simulations.
- [Spending and Income](spending-and-income.md) — Define spending profiles and income sources for projections.
- [Settings and Export](settings-and-export.md) — The consolidated admin area: users, invite codes, and data exports.
