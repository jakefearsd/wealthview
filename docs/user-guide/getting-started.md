[← Back to README](../../README.md)

# Getting Started with WealthView

WealthView is a self-hosted personal finance application for tracking investments, rental properties, and retirement projections. It runs entirely on your own infrastructure, so your financial data never leaves your control.

This guide walks you through your first login, core concepts, and how to navigate the application.

---

## Logging In

### Docker Demo Mode

If you deployed WealthView using Docker Compose, a demo account is created automatically:

- **Email:** `demo@wealthview.local`
- **Password:** `demo123`

A super-admin account is also available in all deployment modes:

- **Email:** `admin@wealthview.local`
- **Password:** `admin123`

Open your browser to `http://localhost` (or wherever you deployed the app) and enter your credentials on the login page.

### Registering a New Account

New users register with an **invite code**. An admin generates invite codes from the Settings page and shares them with you. To register:

1. Navigate to the login page and click **Register**.
2. Enter your name, email, and password.
3. Paste the invite code provided by your admin.
4. Click **Register** to create your account.

Invite codes are single-use and expire after 7 days. If your code is invalid or expired, ask your admin for a new one.

---

## Multi-Tenancy

WealthView supports multiple **tenants** — isolated data spaces for different organizations, households, or individuals. All data within a tenant (accounts, properties, projections) is completely invisible to other tenants.

When you register, you are placed into a tenant. Every user in the same tenant shares access to that tenant's financial data, subject to their role permissions.

---

## User Roles

Each user has one of four roles within their tenant:

| Role | What You Can Do |
|------|----------------|
| **Viewer** | View all data (accounts, properties, projections). Cannot create or modify anything. |
| **Member** | Everything a Viewer can do, plus create and edit accounts, transactions, properties, and projections. |
| **Admin** | Everything a Member can do, plus manage users, generate invite codes, and change user roles. |
| **Super-Admin** | Full system access across all tenants. Used for initial setup and system administration. |

---

## Navigating the Application

The sidebar on the left provides access to every section of WealthView:

- **Dashboard** — Your financial overview: net worth, account balances, portfolio history, and asset allocation.
- **Accounts** — Investment and bank accounts with holdings, transactions, and import tools.
- **Prices** — View and manage security prices. Configure automated price feeds.
- **Properties** — Rental and personal real estate with mortgage tracking, income, expenses, and analytics.
- **Projections** — Retirement scenario modeling with year-by-year simulations.
- **Spending Profiles** — Define retirement spending needs with essential and discretionary breakdowns.
- **Income Sources** — Model Social Security, pensions, rental income, and other retirement income streams.
- **Export** — Download your data as JSON or CSV for backups or analysis.
- **Audit Log** — Review a history of changes made within your tenant.
- **Settings** — Manage notifications, invite codes, and users (admin only).

---

## Key Concepts

### Account Types

WealthView supports five account types, each representing a different kind of financial account:

- **Brokerage** — A standard taxable investment account.
- **IRA** — A traditional Individual Retirement Account (pre-tax contributions, taxed on withdrawal).
- **401(k)** — An employer-sponsored retirement account (pre-tax contributions).
- **Roth** — A Roth IRA or Roth 401(k) (after-tax contributions, tax-free withdrawals).
- **Bank** — A checking or savings account for cash holdings.

### Holdings vs. Transactions

**Transactions** are individual events: buying 10 shares of AAPL, receiving a dividend, depositing cash. They represent what happened and when.

**Holdings** are the current state of what you own. When you record buy and sell transactions, WealthView automatically computes your holdings — the net quantity and cost basis for each symbol in each account.

### Cost Basis

Cost basis tracks how much you originally paid for an investment. WealthView calculates it as the sum of all buy amounts minus all sell amounts for a given symbol. This is used to determine unrealized gains and losses.

### Net Worth

Your net worth in WealthView is the sum of:

- **Investment value** — Current market value of all holdings across all accounts.
- **Property equity** — Current property values minus outstanding mortgage balances.
- **Cash** — Bank account balances.

---

## Next Steps

Now that you understand the basics, explore these guides for specific features:

- [Investment Accounts](investment-accounts.md) — Set up accounts, record transactions, and track holdings.
- [Data Import](data-import.md) — Import transactions from Fidelity, Vanguard, Schwab, or OFX files.
- [Prices and Valuation](prices-and-valuation.md) — Understand how WealthView values your portfolio.
- [Portfolio Analysis](portfolio-analysis.md) — Read your dashboard and analyze performance.
- [Rental Properties](rental-properties.md) — Track real estate, mortgages, income, and expenses.
- [Retirement Projections](retirement-projections.md) — Model retirement scenarios with tax-aware simulations.
- [Spending and Income](spending-and-income.md) — Define spending profiles and income sources for projections.
- [Settings and Export](settings-and-export.md) — Manage users, notifications, and data exports.
