[← Back to README](../../README.md)

# Retirement Projections

WealthView's projection engine runs a deterministic year-by-year simulation of your financial future through retirement. You can model different scenarios — varying retirement ages, spending levels, Roth conversion strategies, and income assumptions — then compare them side by side.

---

## What the Projection Engine Does

The engine steps through each year from now until your specified end age, computing:

- Contributions to each investment pool during your working years.
- Investment growth based on expected returns.
- Withdrawals needed to fund your spending in retirement.
- Tax liability on withdrawals, income sources, and Roth conversions.
- Income from Social Security, pensions, rental properties, and other sources.
- Spending analysis breaking down essential vs. discretionary needs.

The result is a year-by-year table showing your projected balances, income, taxes, and spending through retirement.

---

## Key Concepts

### Investment Pools

The projection engine groups your accounts into three pools, matching how retirement accounts are taxed:

| Pool | Tax Treatment | Examples |
|------|--------------|---------|
| **Traditional** | Contributions are pre-tax. Withdrawals taxed as ordinary income. | IRA, 401(k) |
| **Roth** | Contributions are after-tax. Qualified withdrawals are tax-free. | Roth IRA, Roth 401(k) |
| **Taxable** | No special tax treatment. Gains taxed when realized. | Brokerage accounts |

### Withdrawal Ordering

When you need money in retirement, the engine withdraws in this order:

1. **Taxable accounts first** — These have the most favorable tax treatment for withdrawals (only gains are taxed, not the full amount).
2. **Traditional accounts second** — Withdrawals are fully taxed as ordinary income.
3. **Roth accounts last** — Withdrawals are tax-free, so these are preserved as long as possible.

This ordering is a common tax-efficient strategy. The engine follows it automatically.

### Inflation

All dollar amounts in future years are adjusted for inflation. The inflation rate you set on the scenario compounds annually. For example, with 3% inflation, $50,000 of spending today becomes $51,500 next year, $53,045 the year after, and so on.

### Growth

Each projection account has its own expected annual return. The engine applies this growth rate to the account balance each year (after contributions and before withdrawals).

---

## Creating a Scenario

1. Navigate to **Projections** in the sidebar.
2. Click **Create Scenario**.
3. Fill in the scenario parameters:

| Field | Description | Example |
|-------|-------------|---------|
| **Name** | A descriptive name for this scenario. | "Retire at 62, moderate spending" |
| **Retirement Date** | When you plan to stop working. | 2035-06-01 |
| **End Age** | The age through which to project. | 95 |
| **Inflation Rate** | Annual inflation as a decimal. | 0.03 (for 3%) |
| **Filing Status** | Your tax filing status. | `single` or `married_filing_jointly` |

4. Click **Save** to create the scenario. You can then add accounts, configure withdrawal strategy, and link spending profiles and income sources.

---

## Adding Projection Accounts

Each scenario contains one or more **projection accounts** that represent your investment pools.

### Linked Accounts

You can link a projection account to a real WealthView account. When linked:

- The **initial balance** is automatically pulled from the real account's current market value.
- The account type (traditional/roth/taxable) is inferred from the real account type.

### Hypothetical Accounts

You can also create accounts that do not correspond to a real account. This is useful for modeling:

- Future accounts you plan to open.
- Employer 401(k) plans not yet tracked in WealthView.
- "What if" scenarios with different balances.

### Account Fields

| Field | Description |
|-------|-------------|
| **Account Type** | `traditional`, `roth`, or `taxable`. Determines the pool. |
| **Initial Balance** | Starting balance (auto-filled if linked to a real account). |
| **Annual Contribution** | How much you contribute per year during working years. |
| **Expected Return** | Annual growth rate as a decimal (e.g., 0.07 for 7%). |

---

## Withdrawal Strategies

The withdrawal strategy determines how much you pull from your portfolio each year in retirement. WealthView supports three strategies:

### Fixed Percentage

Calculates your first-year withdrawal as a fixed percentage of your total portfolio balance **at retirement**. Each subsequent year, that dollar amount is adjusted upward for inflation.

**Example:** With a $1,000,000 portfolio and a 4% fixed rate:
- Year 1: Withdraw $40,000
- Year 2: Withdraw $41,200 (with 3% inflation)
- Year 3: Withdraw $42,436

**Pros:** Predictable, inflation-adjusted income. The classic "4% rule" approach.
**Cons:** Does not adapt to market performance. A bad sequence of returns early in retirement can deplete the portfolio.

### Dynamic Percentage

Withdraws a fixed percentage of the **current** portfolio balance each year. The dollar amount fluctuates with market performance.

**Example:** With a 4% dynamic rate:
- Year 1 (portfolio $1,000,000): Withdraw $40,000
- Year 2 (portfolio grew to $1,050,000): Withdraw $42,000
- Year 3 (portfolio dropped to $950,000): Withdraw $38,000

**Pros:** The portfolio can never fully deplete (you always take a percentage of what remains). Spending automatically adjusts to market conditions.
**Cons:** Income is unpredictable. A market crash means a significant spending cut.

### Vanguard Dynamic Spending

A hybrid approach that starts like the fixed percentage method but limits year-over-year changes with **guardrails**:

- **Ceiling** — The maximum percentage increase allowed from one year to the next (e.g., 5%).
- **Floor** — The maximum percentage decrease allowed (e.g., -2.5%).

**Example:** With 4% initial rate, 5% ceiling, -2.5% floor:
- Year 1: Withdraw $40,000
- Year 2 (market boomed, formula says $48,000): Capped at $42,000 (5% increase ceiling)
- Year 3 (market crashed, formula says $35,000): Floored at $40,950 (2.5% decrease floor)

**Pros:** Smooths income volatility while still responding to market conditions. You get some upside in good years and protection in bad years.
**Cons:** More complex. The ceiling/floor parameters require thoughtful calibration.

---

## Roth Conversion Modeling

A Roth conversion moves money from a traditional (pre-tax) account to a Roth (after-tax) account. You pay income tax on the converted amount now, but future growth and withdrawals from Roth are tax-free.

To model Roth conversions in a scenario:

1. Set the **Annual Roth Conversion** amount on the scenario (e.g., $50,000 per year).
2. The engine converts that amount from the traditional pool to the Roth pool each year.
3. The converted amount is added to your taxable income for that year, and the tax is calculated using federal tax brackets and the standard deduction.

This lets you model a **Roth conversion ladder** — systematically converting traditional funds to Roth during low-income years (e.g., early retirement before Social Security starts) to fill up low tax brackets.

---

## Spending Profiles

Link a **spending profile** to your scenario to enable spending analysis in the projection results.

A spending profile defines your retirement spending needs, split into essential and discretionary categories, with optional age-based spending tiers. See [Spending and Income](spending-and-income.md) for details on creating spending profiles.

When a spending profile is linked:

- The projection results include a spending analysis tab.
- In shortfall years, essential expenses are covered first; discretionary spending is cut.
- The year-by-year table shows essential and discretionary amounts separately.

---

## Income Sources

Link one or more **income sources** to your scenario to model retirement income streams like Social Security, pensions, or rental income.

Each income source has a start age, end age, annual amount, and tax treatment. When linked to a scenario, the income reduces the amount you need to withdraw from your portfolio.

You can set a **per-scenario override amount** for any income source, allowing the same income source to be reused across scenarios with different assumed amounts (e.g., modeling Social Security at full benefit vs. reduced early benefit).

See [Spending and Income](spending-and-income.md) for details on creating and configuring income sources.

---

## Running a Projection

Once your scenario is configured with accounts, a withdrawal strategy, and optionally spending profiles and income sources:

1. Navigate to the scenario detail page.
2. Click **Run Projection**.
3. Results are computed on-demand and displayed immediately. They are not stored — you can re-run at any time with updated inputs.

---

## Reading the Results

### Year-by-Year Table

The main results view is a table with one row per year. Columns include:

| Column | Description |
|--------|-------------|
| **Year** | Calendar year. |
| **Age** | Your age in that year. |
| **Contributions** | Total contributions across all accounts (working years only). |
| **Growth** | Investment returns earned that year. |
| **Withdrawals** | Total withdrawals needed to fund spending. |
| **Traditional Balance** | End-of-year traditional pool balance. |
| **Roth Balance** | End-of-year Roth pool balance. |
| **Taxable Balance** | End-of-year taxable pool balance. |
| **Roth Conversion** | Amount converted from traditional to Roth that year. |
| **Tax Liability** | Federal income tax owed that year. |
| **Essential Spending** | Essential expenses (if spending profile linked). |
| **Discretionary Spending** | Discretionary expenses (may be reduced in shortfall years). |
| **Income** | Total income from linked income sources. |
| **Net Need** | Spending minus income = amount needed from portfolio. |
| **Surplus** | Excess funds if income exceeds spending. |
| **Discretionary After Cuts** | Actual discretionary spending after any shortfall reductions. |

### Spending Analysis Tab

If a spending profile is linked, a **stacked area chart** visualizes essential spending, discretionary spending, and income over time. This makes it easy to see when income covers spending vs. when portfolio withdrawals are needed.

### Viability

A projection is considered **viable** if the total portfolio balance remains above zero through your specified end age. If any pool depletes before the end age, the scenario is flagged as non-viable, and the depletion year is highlighted.

---

## Comparing Scenarios

WealthView lets you compare two or three scenarios side by side.

1. Navigate to **Projections** in the sidebar.
2. Click **Compare**.
3. Select the scenarios you want to compare (2 or 3).
4. View the comparison:
   - **Overlay chart** — Portfolio balance lines for each scenario on the same axes.
   - **Summary table** — Key metrics side by side: final balance, peak balance, and depletion year (if applicable).

This is the most powerful way to evaluate different retirement strategies.

---

## Tax Modeling

The projection engine includes detailed tax calculations:

### Federal Tax Brackets

Income from traditional withdrawals, Roth conversions, and taxable income sources is taxed using federal income tax brackets. The engine uses the bracket schedule for the relevant tax year, with year fallback (if future-year brackets are not defined, the most recent available year is used).

### Standard Deduction

The standard deduction is subtracted from your gross income before applying bracket math. This means the first portion of your income (e.g., $14,600 for single filers in 2024) is not taxed.

### Social Security 85% Rule

Social Security benefits are taxed using the provisional income formula. Up to 85% of benefits may be taxable, depending on your total income. The engine calculates the taxable portion based on your combined income from all sources.

### Self-Employment Tax

Income sources with the `self_employment` tax treatment are subject to a 15.3% self-employment tax:
- 12.4% Social Security tax (up to the annual wage base).
- 2.9% Medicare tax (no cap).

---

## Tips and Examples

### "Can I retire at 60?"

Create three scenarios with identical spending and accounts but different retirement dates: age 60, 62, and 65. Run all three and use the Compare feature. Look at the final balances and whether any scenario depletes before your end age.

### "Should I do Roth conversions?"

Create two versions of the same scenario:
1. No Roth conversions (set annual conversion to $0).
2. With Roth conversions (e.g., $50,000/year during early retirement).

Compare the tax liability over time and final portfolio balances. Roth conversions often pay off when you convert during low-income years (after retirement but before Social Security starts), filling up the lower tax brackets.

### "What withdrawal rate is safe?"

Create multiple scenarios with different withdrawal strategies and rates (3.5%, 4%, 4.5%). Compare to find the rate that keeps your portfolio viable through your end age while providing adequate income.

### "How does inflation affect my plan?"

Duplicate a scenario and change only the inflation rate (e.g., 2.5% vs. 3.5%). The comparison will show how sensitive your plan is to inflation assumptions.
