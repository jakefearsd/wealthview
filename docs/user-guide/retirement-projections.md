[← Back to README](../../README.md)

# Retirement Projections

WealthView's projection engine runs a year-by-year simulation of your financial future through retirement. You can model different scenarios — varying retirement ages, spending levels, asset allocations, Roth conversion strategies, spouse assumptions, and income streams — then compare them side by side.

Two engines work together:

- The **deterministic projection** walks one year at a time using a single set of assumptions and produces the detailed year-by-year table and charts.
- The **Monte Carlo spending optimizer** (reached from **Optimize Spending**) runs thousands of simulated market histories to answer "how much can I actually spend, and how likely is this to work?" See [Spending and Income](spending-and-income.md) for that workflow.

> **A note on taxes.** WealthView provides planning estimates only, not tax advice. All tax calculations are approximations. Talk to a CPA before making decisions with real money.

---

## What the Projection Engine Does

For each year from today until your end age, the engine computes:

- Contributions to each account during your working years.
- Investment growth, driven by each account's **asset allocation** (or an override return you set).
- Withdrawals needed to fund your spending in retirement.
- Required Minimum Distributions (RMDs) once you reach the applicable age.
- Tax on withdrawals, income sources, Roth conversions, and realized capital gains.
- Income from Social Security, pensions, rental properties, and other sources.
- Spending, split into essential vs. discretionary.

**Everything is reported in today's dollars.** The results page says so explicitly. Rather than showing you an inflated $4.1M in 2055, the engine works in *real* (inflation-adjusted) terms so the numbers mean something to you today.

---

## Key Concepts

### Investment Pools

Each projection account belongs to one of three pools, matching how retirement accounts are taxed:

| Pool | Tax Treatment | Examples |
|------|--------------|---------|
| **Taxable** | Regular brokerage. After-tax contributions; growth taxed as capital gains. | Brokerage accounts |
| **Traditional (Pre-tax)** | Contributions reduce taxable income now. Withdrawals taxed as ordinary income. | IRA, 401(k) |
| **Roth** | After-tax contributions. Growth and qualified withdrawals are tax-free. | Roth IRA, Roth 401(k) |

The form shows this help text inline as you pick a type.

> One thing the engine does **not** model: when you compare Roth vs. Traditional contributions here, it does not credit you for the pre-tax wage deduction a Traditional contribution gives you today. It models only each account's own growth and withdrawal taxation. The scenario form carries this caveat too.

### Asset Allocation Drives Returns

This is the biggest change from earlier versions of WealthView. You no longer have to guess an "expected return" per account. Instead, each account has an **allocation** across four asset classes:

- **US Stocks**
- **Intl Stocks**
- **Bonds**
- **Cash**

The engine converts that mix into a projected **real** (after-inflation) return using historical asset-class returns going back to 1972 (or 1928, if you opt in — see below).

- If the account is **linked to a real WealthView account**, the allocation is *derived from your actual holdings*. The form shows "Derived from holdings" with a summary like `70.0% US / 15.0% Intl / 10.0% Bond / 5.0% Cash`.
- Click **Customize allocation** to override it. The four percentages must sum to 100% — the editor shows a running `Total: 100%` and blocks saving until it does.
- Click **Reset to derived** to go back to the holdings-derived mix.
- **Override Return (%)** on the account still exists as an escape hatch. Leave it blank to use the allocation-derived return; fill it in to force a specific rate.

If some of your holdings can't be classified into an asset class, the results page shows an orange notice: *"These holdings were modeled as US Stock because we couldn't classify them."* You can pick the right class per symbol right there and re-run.

### Inflation

The inflation rate you set drives the real-terms conversion. Because results are in today's dollars, you generally will not see spending numbers grow year over year — a flat line means your purchasing power is holding steady.

### Market History Window

By default the simulated return sample uses 1972–2025. Tick **Include 1928–1971 market history** to widen it to include the Great Depression and postwar era. The form's own description: *"Slightly higher average equity returns, materially fatter tails."* Turn it on if you want your plan stress-tested against genuinely bad decades.

### Drags on Returns

Three scenario-level assumptions reduce what you actually keep:

| Field | Default | What it does |
|-------|---------|--------------|
| **Dividend Yield (%)** | 1.8 | Annual qualified-dividend drag on taxable accounts. |
| **Bond Interest Yield (%)** | 4.0 | Nominal coupon on the bond portion of taxable accounts, taxed annually as ordinary income (bonds are tax-inefficient). Only matters if an account holds bonds. |
| **Investment Fees (%)** | 0.25 | Annual all-in cost — expense ratios plus advisory fees — subtracted from returns. |

---

## Creating a Scenario

1. Navigate to **Projections** in the sidebar.
2. Click **New Scenario**.
3. Fill in the form (described in the sections below).
4. Click **Create Scenario**.

To change a scenario later, open it and click **Edit**. The save button there is **Save & Re-run** — it saves and immediately re-runs the projection.

### Scenario Basics

| Field | Description |
|-------|-------------|
| **Name** | A descriptive label, e.g. "Retire at 62, moderate spending". |
| **Retirement Date** | The date you stop working. |
| **Birth Year** | Used to calculate your age at each projection year. |
| **End Age** | Age at which the projection ends. Plan beyond your expected lifespan for safety. |
| **Inflation Rate (%)** | Annual rate of price increases. `3` = 3%, the historical U.S. average. |
| **Withdrawal Rate (%)** | Percentage of portfolio to withdraw annually in retirement. `4` = 4%. Used when no spending plan is linked. |
| **Dividend Yield (%)** / **Bond Interest Yield (%)** / **Investment Fees (%)** | See "Drags on Returns" above. |
| **Include 1928–1971 market history** | Checkbox. See above. |
| **Spending Plan** | A single dropdown — see the next section. |

### The Spending Plan Dropdown

**This is one dropdown, not two features.** A "spending profile" (your own age-based tiers) and a "guardrail profile" (a plan the Monte Carlo optimizer computed for you) are two ways of answering the same question: *how much do I spend each retirement year?* A scenario can have **at most one** of them active at a time.

The dropdown contains:

- **None (use withdrawal rate)** — no spending plan. The engine falls back to your **Withdrawal Rate (%)** and the withdrawal strategy you chose.
- Every **spending profile** you have created (see [Spending and Income](spending-and-income.md)).
- The scenario's **guardrail profile**, if one exists, marked with a ⚙ gear icon. It may be labelled `(stale)` if the scenario changed since the optimizer ran, or `(inactive)`.

Picking one automatically clears the other. There is no way to have both.

The form's own help text: *"Choose a spending profile (user-defined tiers) or guardrail profile (Monte Carlo optimized). When linked, the projection withdraws what you need each year, minus non-portfolio income."*

### Income Sources

Add one or more income sources to the scenario from the **Income Sources** section. For each, you can set a **per-scenario override amount** so the same income source can be reused across scenarios with different assumptions (e.g. Social Security at 62 vs. 67). See [Spending and Income](spending-and-income.md).

### Withdrawal Strategy

Pick one of three cards. This determines *how much* you pull from the portfolio each year when no spending plan is driving the number.

**Fixed Percentage (4% Rule)** — Year 1 withdrawal is balance × rate. Each subsequent year adjusts for inflation rather than being recalculated from the balance. Predictable income that doesn't adapt to market performance.

**Dynamic Percentage** — Every year, withdraw current balance × rate. Income fluctuates with markets. The portfolio cannot mathematically deplete to zero, but income can drop significantly in downturns.

**Vanguard Dynamic Spending** — Year 1 is balance × rate. Subsequent years recalculate from the current balance, but the year-over-year change is clamped between a floor and a ceiling. Balances adaptability with income stability. Selecting it reveals two extra fields:

- **Ceiling (max increase, %)** — e.g. `5` = 5%.
- **Floor (max decrease, %)** — e.g. `-2.5` = 2.5% decrease.

### Withdrawal Order

Separately from *how much*, this controls *which accounts* you draw from first.

**Taxable First** — Draw from taxable accounts first, then traditional, then Roth. Preserves tax-advantaged growth longest. This is the default.

**Dynamic Sequencing** — Draws from the Traditional IRA first, up to a bracket ceiling you choose, to burn down that balance at low tax rates, then switches to Taxable. Helps manage RMDs and avoid IRMAA surcharges. Selecting it reveals a **Traditional Withdrawal Bracket Ceiling** dropdown with 10%, 12%, and 22% options.

### Roth Conversion

A Roth conversion moves pre-tax retirement funds to a Roth account. You pay income tax on the converted amount now, but all future growth and withdrawals are tax-free. A conversion ladder spreads conversions over multiple years to stay in lower tax brackets.

Two strategies:

**Fixed Amount** — Convert a fixed dollar amount each year. Set **Annual Roth Conversion** to `$0` to skip conversions entirely.

**Fill Tax Bracket** — Automatically convert enough to fill income up to the top of a **Target Tax Bracket** you pick (10%, 12%, 22%, 24%, 32%, or 35%) each year.

Once conversions are active, three more fields appear:

- **Conversion Start Year** — Calendar year when conversions begin. Leave blank to start immediately.
- **Filing Status** — Single or Married Filing Jointly.
- **Other Income** — Non-retirement income (salary, rental income) that affects which bracket your conversions land in.

For a fully optimized conversion schedule computed against thousands of market paths, use **Optimize Spending** instead — the optimizer can search for conversions jointly with the spending plan.

### Tax Configuration

**State** — Adds state income tax to the projection and unlocks SALT deduction and itemized-vs-standard comparison. The modeled options are:

- None (federal only)
- AZ – Arizona
- CA – California
- NV – Nevada (no income tax)
- OR – Oregon
- WA – Washington (no income tax)

If you pick a state WealthView doesn't model, the results page shows a warning banner saying so.

Selecting a state reveals:

- **Primary Residence Property Tax** — Annual property tax on your primary residence. Feeds the SALT deduction (capped at $10K together with state income tax).
- **Primary Residence Mortgage Interest** — Annual mortgage interest, added to SALT for the itemized deduction comparison.

### Spouse / Household

Leave **Spouse Birth Year** blank for a single-person household. Fill it in and the engine models a two-person household properly:

| Field | What it does |
|-------|--------------|
| **Spouse Birth Year** | Enables all the fields below. |
| **Primary Death Age** | Assumed planning age at which the primary passes away (50–120). Blank uses the SSA planning default, shown as a placeholder. |
| **Spouse Death Age** | Same, for the spouse. |
| **Survivor Spending Factor (%)** | Share of pre-transition spending the survivor keeps from the first death forward (50–100%, default 75%). |
| **Community Property State** | Steps up 100% of the embedded gain on joint taxable accounts at the first death, instead of the common-law 50%. |
| **Model Uncertain Lifespans** | Opt-in stochastic mortality — see below. |

When a household is active, every projection account also gets an **Owner** field: **Primary**, **Spouse**, or **Joint**. Joint ownership is only available for taxable accounts; the form disables the option otherwise. Income sources carry an owner too.

**What happens at the first death.** In the year the first spouse dies, the engine performs a single atomic transition: the surviving spouse keeps the larger of the two Social Security benefits, the decedent's retirement accounts roll over to the survivor, joint taxable accounts get a cost-basis step-up, spending scales down by the survivor spending factor, and the tax filing status flips from Married Filing Jointly to Single. The decedent's required distribution for that year is still taken first. From then on, income the decedent owned is scaled by that source's survivor percentage.

**Model Uncertain Lifespans (opt-in).** Instead of the fixed death ages above, this samples each spouse's death year per Monte Carlo trial from an SSA mortality table, giving you a longevity-aware success rate. Two important limits, stated in the form itself: it *only* affects the guardrail optimizer's Monte Carlo results — **not** the deterministic projection or its recommendation. Enabling it reveals:

- **Primary Sex** / **Spouse Sex** — Selects the sex-specific column of the mortality table. Leave unset for a blended (both-sex) table.
- **Longevity Age** — Age threshold (80–110, default 95) for the "the survivor lives to this age" success metric shown beside lifetime success.

### Accounts

Each scenario needs at least one projection account. Click **+ Add Account** to add more, **Remove** to delete one.

**Link Existing Account** — Pick a real WealthView account from the dropdown, or leave it on **Manual Entry**.

When linked:
- **Initial Balance** becomes read-only and is labelled *(live)* — it updates automatically each time the projection runs.
- The account type is inferred from the real account (`roth` → Roth; `401k` and `traditional_ira` → Traditional; everything else → Taxable).
- **Cost Basis** and **Allocation** are derived from the account's holdings. Cost basis shows "Available after first run" until you've run the projection once.

Manual (unlinked) accounts let you model future accounts, an employer 401(k) you don't track in WealthView, or pure "what if" balances.

| Field | Description |
|-------|-------------|
| **Account Type** | Taxable, Traditional (Pre-tax), or Roth. |
| **Owner** | Primary / Spouse / Joint. Only shown when a spouse birth year is set. |
| **Initial Balance** | Starting balance. Read-only when linked. |
| **Annual Contribution** | How much you add per year during working years. |
| **Override Return (%)** | Optional. Blank uses the allocation-derived return. |
| **Cost Basis** | Total dollars invested, used for capital-gains tax calculations. Derived when linked. |
| **Allocation** | US/Intl stocks, bonds, cash. Derived from holdings by default. |

---

## Running a Projection

1. Open the scenario from the **Projections** list (or click **Run** on the card to open it and run immediately).
2. Click **Run Projection**.
3. Results are computed on demand and shown immediately. They are not stored — re-run any time with updated inputs.

---

## Reading the Results

### Headline Cards

| Card | Meaning |
|------|---------|
| **Final Balance** | Portfolio value at the end of your projection period. |
| **Net Worth** | Portfolio balance plus property equity at the end of the projection. |
| **Years in Retirement** | Years between your retirement date and the projection end. |
| **Peak Balance** | Highest portfolio value reached, with the year it happens. |
| **Plan Outcome** / **Depletion** | See below. |

The last card changes shape depending on what you've configured:

- With **no spending plan**, it's labelled **Depletion** and reads either the year and age the portfolio hits zero, or "Funds last through plan".
- With a spending plan linked, it's labelled **Plan Outcome** and reads **Fully Sustainable**, **Depleted at age N**, or **Underfunded at age N**. "Underfunded" means the money technically lasts, but there are years where it can't cover your full planned spending.

A **Milestone Strip** below repeats the retirement year, peak balance, and plan outcome in a compact band.

### Tax Cards

- **Lifetime Tax** — Federal plus state taxes over retirement.
- **Avg Effective Rate** — Average tax rate on retirement income.

If you selected a state, two more appear:

- **Total State Tax** — Cumulative state tax over retirement.
- **SALT Claimed** — with a subtitle showing how many of your retirement years itemized.

### Banners

- **Spending Shortfall Detected** — Appears when your plan needs more than the portfolio can sustain. It tells you the required annual spending, the sustainable annual spending, and the age the shortfall begins.
- **Warnings** — e.g. your filing state isn't one WealthView models.
- **Unclassified holdings** — described under "Asset Allocation Drives Returns" above.

### Tabs

Which tabs appear depends on what your scenario contains.

| Tab | What it shows |
|-----|---------------|
| **Balance Over Time** | Portfolio balance through time, with Total Contributions overlaid. When pool data exists, it stacks Traditional / Roth / Taxable. |
| **Annual Flows** | Bar chart of Contributions, Growth, Withdrawals, and Income Streams per year. |
| **Data Table** | The full year-by-year table. Includes a **Download CSV** button. |
| **Spending Analysis** | Stacked area of Essential Expenses and Discretionary (After Cuts), with Withdrawal and Income Streams lines over the top. Only when a spending plan is linked. |
| **Income & Tax** | Per-year tax detail — see below. Only when income sources or state tax produce data. |
| **Income Streams** | Each linked income source charted over time. |
| **Tax Shield** | Depreciation-driven tax savings from rental properties. Only when there is depreciation to show. |

### The Data Table

The default columns are: Year, Age, Start, Contributions, Growth, Withdrawals, Income, Total Spending, End — then, when pool data is present, Traditional, Roth, Taxable, Conversion, and Tax.

Click **Show Pool Details** to reveal a much wider view: Essential / Discretionary / Net Need, per-pool growth, tax paid out of each pool, **RMD**, **Cap-Gains Tax**, withdrawals from each pool, Surplus/Deficit, Surplus Reinvested, and a Working/Retired status flag.

Retirement years are shaded, with a thick line marking the transition year. A ⚠ next to a year means income exceeded the 22% bracket — hover it for the tooltip: *"Income exceeds 22% bracket — review IRMAA implications for Medicare (2-year lookback)."*

### The Income & Tax Tab

One row per year, with columns for Rental Gross, Rental Expenses, Depreciation, Loss Applied, Suspended Loss, SS Taxable, SE Tax, and Tax Liability — plus Federal Tax, State Tax, SALT and Deduction when a state is set, and RMD, Cap-Gains Tax, IRMAA, and Early Penalty columns when those apply. Rows expand for a per-year breakdown.

---

## Required Minimum Distributions

Once you reach the RMD age, the IRS forces money out of traditional accounts whether you need it or not — and it's taxed as ordinary income. WealthView models this inside the main projection.

- RMDs begin at **age 73** if you were born before 1960, or **age 75** if you were born in 1960 or later (SECURE Act 2.0).
- The amount uses the IRS Uniform Lifetime Table (Publication 590-B, Table III).
- In a household scenario, each person has their own RMD stream based on their own age and their own traditional balance.
- The per-year amount appears in the **RMD** column of the Data Table (with Pool Details shown) and the Income & Tax tab.

This is why Roth conversions and Dynamic Sequencing matter: both shrink the traditional balance before RMDs force it out at a potentially higher rate.

---

## Capital Gains on Taxable Accounts

Withdrawals from a taxable account are not tax-free — you owe tax on the *gain*. The engine tracks cost basis per lot and sells oldest-first (FIFO), computing long-term capital gains tax and the Net Investment Income Tax where it applies. This is why the **Cost Basis** field on each account matters: without it the engine can't tell how much of a withdrawal is gain.

Realized capital gains tax shows up in the **Cap-Gains Tax** column.

---

## Tax Modeling

The engine's tax model includes federal brackets with the standard deduction, an age-65 additional deduction, Social Security's provisional-income rule (up to 85% of benefits taxable), self-employment tax, rental passive-loss rules, long-term capital gains brackets, state income tax with SALT and itemized-vs-standard comparison, IRMAA Medicare surcharge flags, and early-withdrawal penalties.

Bracket schedules fall back to the most recent defined year when a future year isn't seeded.

**Again: these are planning estimates, not tax advice.** The app says so on the Income Sources screen and it applies to every number here.

---

## Comparing Scenarios

1. Navigate to **Projections**.
2. Click **Compare Scenarios**.
3. Select 2 or 3 scenarios (the third is optional). You'll get an error if you select fewer than 2.
4. Click **Compare**.

You get:

- **Balance Over Time** — an overlay area chart of each scenario's end-of-year balance.
- **Summary** — a table comparing **Final Balance**, **Peak Balance** (with year), **Depletion Year** (or "Never"), and **Years in Retirement**.

---

## Tips and Examples

### "Can I retire at 60?"

Create three scenarios with identical spending and accounts but different retirement dates — age 60, 62, 65. Compare them and look at the Plan Outcome card and depletion year on each.

### "Should I do Roth conversions?"

Duplicate a scenario and set one to Fixed Amount / $0 and the other to Fill Tax Bracket at 12% or 22%. Compare **Lifetime Tax** and **Final Balance**. Conversions usually pay off in low-income years — after you retire but before Social Security and RMDs start — because they let you fill up cheap brackets that would otherwise go unused.

### "What withdrawal rate is safe?"

Rather than guessing, run **Optimize Spending**. The Monte Carlo optimizer searches for the highest spending your portfolio can support at your chosen confidence level, instead of asking you to pick a rate and hope.

### "How much does my allocation matter?"

Duplicate a scenario, customize the allocation on your largest account (say 80/20 stocks/bonds vs. 50/50), and compare. Then tick **Include 1928–1971 market history** on both and re-run to see how each holds up against worse history.

### "What if I die first?"

Fill in the spouse fields and set **Primary Death Age** to something early. Watch the Income & Tax tab: the filing status flip from Married Filing Jointly to Single is often a bigger tax shock than people expect, because the same income gets squeezed into narrower single-filer brackets.
