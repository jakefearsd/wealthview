[← Back to README](../../README.md)

# Spending Profiles and Income Sources

Spending profiles and income sources are the building blocks that feed into your retirement projections. Spending profiles define how much you plan to spend in retirement. Income sources define how much money comes in from Social Security, pensions, rental properties, and other streams.

---

## Spending Profiles

### What They Are

A spending profile models your annual retirement spending, split into two categories:

- **Essential expenses** — Non-negotiable costs: housing, food, healthcare, insurance, utilities. These are always funded first.
- **Discretionary expenses** — Flexible costs: travel, dining out, hobbies, gifts. These can be cut in years when your portfolio cannot fully cover both.

This split matters in projection results. In a shortfall year (when your portfolio and income cannot cover total spending), the engine funds essential expenses first and reduces discretionary spending. This gives you a realistic view of your retirement quality of life, not just whether the money lasts.

### Creating a Profile

1. Navigate to **Spending Profiles** in the sidebar.
2. Click **Create Profile**.
3. Enter:
   - **Name** — A descriptive label (e.g., "Moderate Retirement", "Lean FIRE").
   - **Essential Expenses** — Annual amount in today's dollars (e.g., $35,000).
   - **Discretionary Expenses** — Annual amount in today's dollars (e.g., $15,000).
4. Click **Save**.

The total annual spending for this profile is the sum of essential and discretionary amounts ($50,000 in the example above). In projections, these amounts are adjusted for inflation each year.

### Spending Tiers

Retirement spending rarely stays constant for 30+ years. Most people spend more in early retirement (travel, activities) and less as they age. Spending tiers let you model this with **age-based spending phases**.

#### Adding Tiers

On the spending profile detail page, click **Add Tier** to define a spending phase:

| Field | Description | Example |
|-------|-------------|---------|
| **Label** | A name for this phase. | "Active Retirement" |
| **Start Age** | The age when this tier begins. | 65 |
| **Essential Expenses** | Annual essential spending for this phase. | $40,000 |
| **Discretionary Expenses** | Annual discretionary spending for this phase. | $20,000 |

You can add multiple tiers to model different life stages:

| Tier | Start Age | Essential | Discretionary | Total |
|------|-----------|-----------|---------------|-------|
| Active Retirement | 65 | $40,000 | $25,000 | $65,000 |
| Slowing Down | 75 | $35,000 | $15,000 | $50,000 |
| Quiet Years | 85 | $30,000 | $5,000 | $35,000 |

Each tier's spending amounts are in base (today's) dollars. Inflation is applied on top.

#### Per-Tier Inflation

Inflation within each tier compounds from the **later of** the tier's start age or your retirement start age.

This means:

- When you enter a new tier, spending resets to that tier's base dollar amounts.
- From the first year in the tier onward, inflation compounds annually.
- The first year in a new tier is always at base dollars — inflation has not yet accumulated for that tier.

**Example:** You retire at 62 with 3% inflation. The "Slowing Down" tier starts at age 75 with $50,000 base spending.
- Age 75: $50,000 (base — no inflation accumulated yet for this tier)
- Age 76: $51,500 (one year of 3% inflation)
- Age 77: $53,045 (two years of inflation)

This approach reflects the reality that when your lifestyle changes (a new spending tier), your costs reset to a new baseline rather than carrying forward decades of compounded inflation from a previous lifestyle.

---

## Income Sources

### What They Are

Income sources represent retirement income streams beyond portfolio withdrawals. They are reusable definitions that you link to one or more projection scenarios.

When an income source is active during a projection year (you have reached the start age and have not passed the end age), the income reduces the amount you need to withdraw from your portfolio.

### Income Types

WealthView supports six types of income:

| Type | Description |
|------|-------------|
| **Rental Property** | Income from a rental property you own. Can be linked to a WealthView property for depreciation deductions. |
| **Social Security** | Federal retirement benefits. Typically starts at 62-70. Subject to the 85% provisional income taxation rule. |
| **Pension** | Defined-benefit pension payments from a former employer. |
| **Part-Time Work** | Income from part-time employment or consulting in retirement. |
| **Annuity** | Payments from an annuity contract. |
| **Other** | Any income that does not fit the above categories. |

### Creating an Income Source

1. Navigate to **Income Sources** in the sidebar.
2. Click **Create Income Source**.
3. Fill in the fields:

| Field | Description | Example |
|-------|-------------|---------|
| **Name** | A descriptive label. | "Social Security - Full Benefit" |
| **Income Type** | Select from the six types above. | `social_security` |
| **Annual Amount** | The annual income in today's dollars. | $28,000 |
| **Start Age** | When this income begins. | 67 |
| **End Age** | When this income stops. Leave empty for lifetime income. | (blank) |
| **Inflation Rate** | Annual growth rate for this income as a decimal. | 0.02 |
| **One-Time** | Toggle on if this is a lump sum received once, not recurring. | Off |

4. Click **Save**.

### One-Time Income

Enable the **One-Time** flag for lump-sum events like an inheritance, a property sale, or a one-time bonus. The amount is received only in the year you reach the start age, not annually.

---

## Tax Treatments

Each income source has a tax treatment that determines how the income is taxed in the projection engine. Choosing the correct treatment is important for accurate tax projections.

### Taxable

The income is **fully taxed as ordinary income**. It is added to your gross income and taxed at your marginal federal tax rate.

**Use for:** Pensions, part-time work wages, most annuity payments, traditional IRA/401(k) distributions.

### Partially Taxable

Applies the **Social Security 85% provisional income rule**. The taxable portion of your Social Security benefits depends on your total "provisional income" (adjusted gross income + non-taxable interest + half of Social Security benefits):

- Below the first threshold: benefits are not taxed.
- Between thresholds: up to 50% of benefits are taxable.
- Above the second threshold: up to 85% of benefits are taxable.

The projection engine calculates this automatically based on your total income from all sources.

**Use for:** Social Security benefits.

### Tax-Free

The income is **not taxed at all**. It does not appear in your gross income for tax calculations.

**Use for:** Roth IRA distributions, municipal bond interest, return-of-principal payments, tax-free gifts.

### Rental Passive

Applies **passive activity loss rules**. Rental income is considered passive income. If you have rental losses (because depreciation exceeds rental income), the deduction is limited:

- Up to $25,000 in passive losses can be deducted if your adjusted gross income (AGI) is below $100,000.
- The $25,000 allowance phases out between $100,000 and $150,000 AGI.
- Above $150,000 AGI, no passive losses can be deducted (they are carried forward).

**Use for:** Rental property income when you are not a real estate professional and do not materially participate.

### Rental Active (Real Estate Professional Status)

If you qualify as a **real estate professional** (750+ hours annually in real estate activities, more than half your working time), there is **no limit** on rental loss deductions. All rental losses can be deducted against ordinary income.

**Use for:** Rental income when you qualify for Real Estate Professional Status (REPS).

### Rental Active (Short-Term Rental)

If you operate a **short-term rental** (average guest stay of 7 days or fewer) and you materially participate in the rental activity, the passive loss limitations do not apply. This is similar to REPS but applies specifically to short-term rental operators.

**Use for:** Airbnb, VRBO, or other short-term rental income where you materially participate.

### Self-Employment

Income subject to **self-employment tax** in addition to regular income tax. The SE tax rate is 15.3%:

- **12.4%** Social Security tax (applies up to the annual Social Security wage base).
- **2.9%** Medicare tax (applies to all SE income, no cap).

The SE tax is calculated separately from income tax and added to the total tax liability.

**Use for:** Freelance work, consulting, sole proprietorship income, independent contractor payments.

---

## Property-Linked Income

When you create an income source of type `rental_property`, you can link it to a specific property in WealthView. This connection enables **depreciation deductions** in projections.

How it works:

1. Create an income source with type `rental_property`.
2. Select the property to link.
3. The property's annual depreciation (calculated from the depreciation configuration on the property — see [Rental Properties](rental-properties.md)) is applied as a deduction against the rental income.
4. The deduction reduces the taxable portion of the rental income, lowering your projected tax liability.

For example, if your rental property generates $24,000 in annual income and has $9,000 in annual depreciation, only $15,000 is treated as taxable rental income (subject to the applicable tax treatment rules above).

---

## Per-Scenario Overrides

The same income source can be linked to multiple projection scenarios. When linking, you can set a **per-scenario override amount** that replaces the income source's default annual amount for that specific scenario.

This is useful for modeling:

- **Social Security timing:** Link the same "Social Security" income source to two scenarios — one assuming full benefit at 67, another assuming reduced early benefit at 62, using different override amounts.
- **Optimistic vs. conservative rental income:** Same rental property income source with $24,000 in one scenario and $20,000 in another.
- **Part-time work variations:** Different assumed hourly rates or hours per week.

The override only affects the amount. All other settings (start age, end age, tax treatment, inflation rate) remain as defined on the income source.

---

## Inflation Adjustment

Each income source has its own **inflation rate**. The annual amount grows by this rate each year.

- Social Security typically uses a COLA adjustment (often around 2-3%).
- Pensions may have a fixed COLA or no inflation adjustment (set to 0).
- Rental income may grow faster than general inflation in strong markets.

Set each income source's inflation rate to match your assumptions for that specific income stream.

---

## Connecting to Projections

To use spending profiles and income sources in a projection:

1. Navigate to the projection scenario detail page.
2. In the **Spending Profile** section, link a spending profile.
3. In the **Income Sources** section, add one or more income sources. Optionally set per-scenario override amounts.
4. Run the projection.

The engine uses the spending profile to determine annual spending needs and the income sources to determine non-portfolio income. The difference (spending minus income) is the amount withdrawn from investment pools.
