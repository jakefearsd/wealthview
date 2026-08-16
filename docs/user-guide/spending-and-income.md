[← Back to README](../../README.md)

# Spending Profiles and Income Sources

Spending profiles and income sources are the building blocks that feed into your retirement projections. Spending profiles define how much you plan to spend. Income sources define how much money comes in from Social Security, pensions, rental properties, and other streams.

There is also a third way to answer the spending question — let WealthView compute it for you with the **Spending Optimizer**. That's covered at the end of this page.

---

## Spending Profiles

### What They Are

A spending profile models your annual retirement spending, split into two categories:

- **Essential expenses** — Non-negotiable costs: housing, food, healthcare, insurance, utilities. These are always funded first.
- **Discretionary expenses** — Flexible costs: travel, dining out, hobbies, gifts. These get cut first when the money doesn't stretch.

That split is what makes the projection results useful. In a shortfall year, the engine funds essentials first and reduces discretionary spending, so you see what retirement would actually *feel* like rather than just a pass/fail on whether the money lasts.

The page describes it this way: *"Spending profiles define your retirement cost of living. Attach one to a projection scenario to see whether your portfolio can sustain your planned lifestyle."*

### Creating a Profile

1. Navigate to **Spending Profiles** in the sidebar.
2. Click **New Profile**.
3. Enter:
   - **Name** — A descriptive label, e.g. "Moderate Retirement".
   - **Essential Expenses (annual)** — *"Default non-negotiable annual costs when no spending tier matches the current age."*
   - **Discretionary Expenses (annual)** — *"Default flexible annual spending when no spending tier matches the current age."*
4. Optionally add spending tiers (below).
5. Click **Create Profile**.

Only the name is required. Each profile card in the list shows the annual total plus tiles for **Essential**, **Discretionary**, **Monthly Equivalent**, and how many **Spending Tiers** it has.

> **All amounts are in today's dollars.** This is important and it's different from how most retirement calculators work. WealthView projects in *real* terms — you enter what your lifestyle costs today and the engine keeps that purchasing power constant. You will not see your spending line climb year after year in the results, and that is correct.

### Spending Tiers

Retirement spending rarely stays flat for 30 years. Most people spend more early (travel, activities) and less later. Tiers let you model this as **age-based spending phases**.

The page's own description: *"Define age-based spending phases. When tiers are defined, spending varies by life stage instead of staying flat. Amounts are in today's dollars; inflation is applied automatically."*

Click **+ Add Spending Tier**. Each tier has exactly five fields:

| Field | Description | Example |
|-------|-------------|---------|
| **Phase Name** | A name for this phase. | "Go-Go Years" |
| **Start Age** | The age when this tier begins. | 65 |
| **End Age (blank = forever)** | The age when it ends. Leave blank for an open-ended final phase. | 74 |
| **Essential (annual)** | Annual essential spending for this phase. | $40,000 |
| **Discretionary (annual)** | Annual discretionary spending for this phase. | $25,000 |

A typical three-phase setup:

| Phase Name | Start Age | End Age | Essential | Discretionary | Total |
|------------|-----------|---------|-----------|---------------|-------|
| Go-Go Years | 65 | 74 | $40,000 | $25,000 | $65,000 |
| Slow-Go Years | 75 | 84 | $35,000 | $15,000 | $50,000 |
| No-Go Years | 85 | *(blank)* | $30,000 | $5,000 | $35,000 |

### How Tiers Are Matched

A tier matches an age when `start age ≤ age ≤ end age`. Both ends are inclusive, and a blank end age means the tier runs forever.

Two edge cases are worth knowing about, because WealthView handles them silently rather than warning you:

- **Overlapping tiers** — If two or more tiers match the same age, WealthView **averages** them. It does not pick the narrower one or the first one. Two overlapping tiers at $60,000 and $40,000 produce $50,000.
- **Gaps between tiers** — If no tier matches an age but there are bounded tiers on both sides, WealthView uses the **midpoint** of the two neighbours. If the gap is before your first tier or after your last bounded tier, it falls back to the profile-level **Essential Expenses** and **Discretionary Expenses** you set at the top of the form.

Neither case produces an error or a warning, so it's worth reading your tier ages back to yourself. The cleanest setup is contiguous, non-overlapping tiers with a blank end age on the last one.

### Editing and Deleting

Click a profile to edit it; the button becomes **Update Profile**. Deleting a profile is immediate — **there is no confirmation prompt**. If any scenario was using the profile, its reference is cleared and that scenario falls back to its withdrawal-rate strategy.

---

## Income Sources

### What They Are

Income sources represent retirement income beyond portfolio withdrawals. They're reusable definitions you attach to one or more projection scenarios.

The page explains: *"Income sources reduce how much you need to withdraw from your investment portfolio each year. They are separate from spending profiles (which define your expenses). Each income source has a type that determines how it is taxed in projections. You can reuse income sources across multiple scenarios — attach them when you create or edit a projection scenario."*

### Income Types

| Type | Notes |
|------|-------|
| **Social Security** | Federal retirement benefits. Selecting it defaults the start age to 67 and the inflation rate to 2%. |
| **Pension** | Defined-benefit payments from a former employer. |
| **Rental Property** | Income from a rental. Can be linked to a WealthView property to pull in depreciation. Defaults the inflation rate to 2%. |
| **Part-Time Work / Consulting** | Employment or consulting income in retirement. |
| **Annuity** | Payments from an annuity contract. |
| **Other** | Anything else. |

Changing the type resets the tax treatment to that type's first valid option, so pick the type first.

### Creating an Income Source

1. Navigate to **Income Sources** in the sidebar.
2. Click **New Income Source**.
3. Fill in the fields:

| Field | Description |
|-------|-------------|
| **Name** | A descriptive label. Required. |
| **Income Type** | One of the six above. *"Type determines how this income is taxed in projections."* |
| **Owner** | **Primary** or **Spouse**. *"Whose income this is, for household/survivor modeling."* |
| **Survivor Benefit / Survivor % (%)** | See below. |
| **Tax Treatment** | Pick a card. Options depend on the type — see below. |
| **Link to Property (optional)** | Rental Property only. See below. |
| **One-time payment** | Checkbox. See below. |
| **Annual Amount** | The yearly income in today's dollars. Must be greater than zero. |
| **Start Age** | When this income begins. |
| **End Age (blank = forever)** | *"Leave blank if this income continues for life."* |
| **Inflation Rate (%)** | *"Annual adjustment rate (e.g., 2 = 2%). SS COLA is typically ~2%."* |

4. Click **Create Income Source**.

Deleting an income source is immediate, with no confirmation prompt.

### Owner and Survivor Percent

These two fields exist for household scenarios (see [Retirement Projections](retirement-projections.md)).

**Owner** is **Primary** or **Spouse** — there is no "joint" option, because income belongs to one person even when the accounts don't.

**Survivor %** controls what happens to this income after its owner dies: *"Share of this income the survivor keeps after the owner's death (0–100%, default 100%)."* A single-life pension that stops at death would be 0%. A joint-and-survivor annuity at 50% would be 50%.

**Social Security is different.** For Social Security, the field is replaced with a read-only note: *"Statutory survivor rule applies automatically (survivor keeps the larger benefit)."* You don't set a percentage because the law already determines the answer — the surviving spouse keeps whichever of the two benefits is larger, and the smaller one stops. WealthView models that rule directly.

### One-Time Payments

Tick **One-time payment (e.g., deferred compensation, inheritance)** for a lump sum. The labels change: **Annual Amount** becomes **Payment Amount** and **Start Age** becomes **Payment Age**. The end age and inflation fields disappear, because a one-time payment is received in a single year and doesn't grow.

### Linking to a Property

For **Rental Property** income, a **Link to Property (optional)** dropdown appears listing your properties with their current values. The help text: *"Link to a property to pull depreciation data into projections. Leave unlinked for hypothetical planning."*

When linked, the **Annual Amount** field is relabelled **Annual Rent Amount**, and the projection engine nets the property's expenses and applies its depreciation as a deduction against the rental income — which can turn a cash-flow-positive rental into a taxable loss that shields other income. See [Rental Properties](rental-properties.md).

---

## Tax Treatments

Each income type offers a different set of tax treatments, shown as selectable cards with the app's own one-line explanation.

### Social Security

**Partially Taxable** — *"IRS formula determines 0–85% taxable based on provisional income."*

The page explains the mechanism: *"Social Security benefits are taxed based on 'provisional income' (your other income + 50% of SS benefits). For single filers: below $25,000 = 0% taxable; $25,000–$34,000 = up to 50% taxable; above $34,000 = up to 85% taxable. For married filing jointly: thresholds are $32,000 and $44,000. The projection engine applies this formula automatically."*

### Pension

- **Fully Taxable** — *"Most pensions are fully taxable as ordinary income."*
- **Tax-Free** — *"Some government pensions may be partially or fully tax-exempt."*

### Rental Property

- **Passive** — *"Losses only offset other passive income ($25k exception for MAGI < $150k)."*
- **Active - REPS** — *"Real Estate Professional Status: all losses offset any income type."*
- **Active - STR** — *"Short-Term Rental loophole: losses offset any income type."*

The page expands on all three:

> **Passive (default):** Rental losses can only offset other passive income. There is a $25,000 exception if your MAGI is below $100,000, phased out completely at $150,000.
>
> **REPS (Real Estate Professional Status):** If you qualify (750+ hours, >50% of services in real estate), all rental losses offset any income type. Discuss with your CPA.
>
> **STR (Short-Term Rental):** If average guest stay is 7 days or less and you materially participate (100+ hours), losses offset any income. Discuss with your CPA.

### Part-Time Work / Consulting

- **Self-Employment** — *"Subject to 15.3% SE tax (Social Security + Medicare) on 92.35% of net."*
- **W-2 Income** — *"Taxed as ordinary income. Employer handles payroll taxes."*

The page's explainer: *"Self-employment income is subject to a 15.3% SE tax (12.4% Social Security + 2.9% Medicare) on 92.35% of net earnings. The Social Security portion caps at the annual wage base (~$168,600 in 2024). Half of SE tax is deductible from AGI. W-2 income avoids SE tax because employers handle payroll taxes."*

### Annuity

- **Fully Taxable** — *"Qualified annuity distributions taxed as ordinary income."*
- **Tax-Free** — *"Roth annuity or return of after-tax basis."*

### Other

- **Taxable** — *"Ordinary income subject to federal income tax."*
- **Tax-Free** — *"Not subject to income tax (e.g., gifts, Roth distributions)."*

### The Disclaimer

The Income Sources page carries a **Talk to your CPA about...** section listing REPS qualification, the short-term rental rules, whether a cost segregation study makes sense, your provisional income estimate, and Roth conversion timing in rental-loss years. It closes with:

> *WealthView provides planning estimates only, not tax advice. All tax calculations are approximations.*

That applies to everything on this page.

---

## Per-Scenario Overrides

The same income source can be attached to multiple scenarios, and each scenario can set an **override amount** that replaces the default annual amount just for that scenario.

This is how you model:

- **Social Security timing** — one "Social Security" source attached to two scenarios, at full benefit in one and reduced early benefit in the other.
- **Optimistic vs. conservative rent** — $24,000 in one scenario, $20,000 in another.

The override changes only the amount. Start age, end age, tax treatment, owner, survivor percent, and inflation rate stay as defined on the income source itself.

The projection detail page shows all three numbers side by side — **Base Amount**, **Override**, and **Effective** — so it's always clear which is in play. For property-linked rental income the effective figure is shown as a *net* number next to the *gross* base amount, because expenses and depreciation have been netted out.

---

## Attaching Them to a Scenario

Both spending profiles and income sources are attached from the **scenario form** (Projections → New Scenario, or Edit on an existing one):

- Spending profiles appear in the unified **Spending Plan** dropdown.
- Income sources are added in the scenario's **Income Sources** section, where you can also set the per-scenario override.

Remember that a scenario has **at most one** spending plan. Choosing a spending profile clears any guardrail profile, and vice versa. Choosing **None (use withdrawal rate)** clears both and falls back to the withdrawal-rate strategy.

---

## The Spending Optimizer

Instead of guessing a spending number and checking whether it works, you can ask WealthView to find the highest spending your portfolio can support at a confidence level you choose. This is the **Monte Carlo spending optimizer**, and it produces a **guardrail profile**.

A guardrail profile is a spending plan just like a spending profile — the two are interchangeable in the Spending Plan dropdown, and a scenario can only have one of them at a time. The difference is where the numbers come from: you write a spending profile, the optimizer computes a guardrail profile.

### Starting an Optimization

1. Open a projection scenario.
2. Click **Optimize Spending**.

The top of the page shows a read-only summary of the scenario — inflation, retirement year, end age, account balances by type, and income sources — with the note: *"These values come from the projection scenario. Edit the scenario to change them."* To change any of that, go back and edit the scenario.

### Optimization Parameters

| Field | Default | What it means |
|-------|---------|---------------|
| **Profile Name** | "Optimized Spending Plan" | A label for the resulting plan. |
| **Essential Spending Floor (per year)** | $30,000 | The spending level you refuse to go below. Success is measured against this. |
| **Terminal Balance Target** | $0 | How much you want left at the end. Raise it if you want to leave an inheritance. |
| **Portfolio Safety Net** | $0 | *"Minimum portfolio balance to maintain during retirement."* |
| **Risk Tolerance** | moderate | See below. |
| **Spending Flexibility** | 5%/yr | *"Maximum annual spending change."* |
| **Phase Blending** | 1 year | *"Smooth transitions between life phases."* Off, 1 year, or 2 years. |
| **Gate on adaptive spending rules** | on | See below. |

### Risk Tolerance Is Your Target Success Probability

This is the single most important dial on the page. It isn't a vague "how bold are you" slider — it maps directly to the probability the optimizer must hit:

| Setting | Target success probability | The app's description |
|---------|---------------------------|----------------------|
| **conservative** | 95% | *"Very likely sustainable without adjustments"* |
| **moderate** | 90% | *"Sustainable with occasional adjustments in bad markets"* |
| **aggressive** | 80% | *"Expected spending, requires active management in downturns"* |

Aggressive isn't reckless — it's a deliberate trade. You spend more now and accept that roughly one in five simulated market histories would require you to cut back. Conservative buys certainty at the price of spending less than you probably could.

You can override the number directly under **Advanced Settings → Confidence Level** if you want something in between.

### What "Success" Means Here

Success is defined as **funding your essential floor every single year**. The optimizer reports the fraction of simulated market histories in which you never fall short of that floor. Discretionary spending above the floor may get cut in bad trials — that's expected, and it's why the floor is the thing being measured.

This is stricter and more meaningful than "the portfolio never hit zero", which is what most retirement calculators report.

**Gate on adaptive spending rules** decides which of two success numbers the optimizer must certify. Left on (the default), it assumes you'll actually follow the plan's spending-cut rule when markets go against you: *"Recommended spending assumes you follow the profile's spending-cut rule in downturns (certifies the 'With Guardrail Cuts' number). Uncheck for the conservative never-adjust gate."* Turn it off if you want the plan to hold up even if you never adjust.

### Spending Phases

The **Spending Phases** editor is where you tell the optimizer what you *want* to spend at each life stage: *"Set your desired annual spending for each life stage. The optimizer will find the best achievable plan within your portfolio's capacity."*

Each phase has four fields — a name, **Start**, **End** (blank for open-ended), and a **$ Target**. Rows can be dragged to reorder. Click **+ Add Phase** for more.

The defaults give you a sensible starting point:

| Phase | Ages | Target |
|-------|------|--------|
| Early retirement | 62–72 | $80,000 |
| Mid retirement | 73–82 | $60,000 |
| Late retirement | 83+ | $45,000 |

The targets are aspirations, not constraints. The optimizer reports how much of each one it could actually fund.

### Advanced Settings

| Field | Default | Description |
|-------|---------|-------------|
| **Cash Reserve** | 2 years | *"Years of spending held in cash to avoid selling during downturns."* |
| **Cash Rate** | 4% | *"Expected annual return on cash reserves (money market rate)."* |
| **Trial Count** | 5,000 | Number of simulated market histories. Options: 1,000 / 2,500 / 5,000 / 10,000. |
| **Confidence Level** | *(uses risk tolerance)* | A direct override, 50–99%. |
| **Dynamic-Sequencing Bracket Rate (%)** | Off | *"Target tax bracket for dynamic withdrawal sequencing."* |

### Roth Conversion Strategy

Tick the checkbox next to **Roth Conversion Strategy** to have the optimizer search for a conversion schedule at the same time as the spending plan: *"Optimize Roth conversions alongside spending to minimize lifetime taxes. Conversions shift money from Traditional to Roth accounts, paying tax now at a lower bracket to avoid higher RMD-driven taxes later."*

Three settings appear:

- **Conversion Bracket** — *"Maximum tax bracket to fill with conversions each year."* 10% through 37%, default 22%.
- **RMD Target Bracket** — *"Target bracket for RMDs after conversions are complete."* Default 12%. Can't exceed the conversion bracket.
- **RMD Bracket Headroom** — *"Reserve headroom for market growth years. Higher = more conservative."* Default 10%.

### Running It

Click **Run Optimization**. You'll see *"Running 5,000 Monte Carlo trials..."* (or whatever trial count you chose) while it works.

Running the optimizer **replaces** any previous guardrail profile on the scenario and **detaches** whatever spending profile the scenario had. That's the mutual exclusivity rule in action.

---

## Reading the Optimizer Results

### Headline Metrics

| Card | Meaning |
|------|---------|
| **Success Probability** (or **If Never Adjusted**) | The share of trials that funded your essential floor every year with no mid-course corrections. |
| **If Rules Followed** (or **With Guardrail Cuts**) | The same measure assuming you follow the plan's spending-cut rule in downturns. |
| **Failure Rate** | Colour-coded — green, amber above 10%, red above 20%. |
| **10th Percentile Final** | Final balance in a pessimistic outcome. |
| **25th Percentile Final** | Final balance in a below-average outcome. |
| **Median Final Balance** | Final balance in the middle outcome. |

One of the two success cards carries a green **Certified** badge — that's the number the optimizer actually gated on, determined by the **Gate on adaptive spending rules** checkbox.

### Warnings

A **Plan Warnings** banner appears when something needs your attention, with messages like *"Early retirement is only 74% funded"*, *"Failure rate exceeds 20%"*, or *"In a pessimistic scenario (10th percentile), portfolio depleted by age 88"*.

If your essential floor is simply more than the portfolio can support, you get: *"Your essential floor exceeds what the portfolio can sustain at this confidence. Results measure a REDUCED floor; against your original floor, success is X%."* That's a signal to either lower the floor, work longer, or save more — the optimizer can't conjure money that isn't there.

### Longevity-Aware Success

If the scenario has **Model Uncertain Lifespans** enabled (that toggle lives on the *scenario* form, not here), an extra card appears: *"Instead of the fixed death ages above, this samples each spouse's death year per Monte Carlo trial from an SSA mortality table. The fixed-death Success Probability above stays for comparison."*

It shows **Lifetime Success** (*"Never falls short while either spouse is alive"*) and **Longevity-Conditional Success** (*"If the survivor lives to age 95"*, or whatever longevity age you set), plus the sampled second-death age at the 10th percentile, median, and 90th percentile.

### The Tax Disclaimer

The results carry this note, and it's worth reading:

> **Note:** Spending recommendations account for income tax on traditional account withdrawals using your scenario's filing status and withdrawal ordering. Actual tax liability may vary based on deductions, credits, and state taxes not fully modeled in the Monte Carlo simulation.

### Charts and Tables

- **Outcome Range** — a band from **Pessimistic (p10)** to **Median (p50)** final portfolio balance.
- **Phase Achievement** — per phase: target, average recommended, and an achievement bar (green at 90%+, amber at 70%+, red below).
- **Portfolio Balance Projections** — the fan chart. *"The dark line shows the median outcome. The shaded bands show the range between pessimistic (10th percentile) and median (50th percentile) scenarios. The red dashed line is the worst-case floor."*
- **Spending Corridor** — *"The blue line shows recommended spending at your confidence level. The shaded band shows the adjustment range — spend near the top in good markets, cut toward the bottom in downturns. The green area represents income that offsets portfolio withdrawals."*
- **Year-by-Year Breakdown** — Age, Phase, Recommended, Floor, Discretionary, Income, Portfolio Draw, portfolio balance at p10/p25/p50, and the spending corridor range.
- **Near-Term Spending Guide** — the tactical view. A hero card for year one with the recommended amount broken into Essential, Discretionary, Income, and Portfolio Draw, then a short run of following years. Each year also shows what you could spend if the portfolio outperforms: a **Recommended (p25)** figure and an **Expected path (p50)** figure.
- **Roth Conversion Strategy** — when conversions were optimized: lifetime tax with and without conversions, estimated savings, the conversion and RMD bracket settings, a Traditional/Roth balance trajectory chart, and the full year-by-year conversion schedule.

### Living With the Plan

Back on **Spending Profiles**, guardrail profiles get their own section: *"Optimized spending plans generated by the Monte Carlo simulator. These override the standard spending profile on their attached scenario."*

Each shows a **$min – $max / year** range with tiles for **Essential Floor**, **Failure Rate**, **Median Final Balance**, **Trials**, **Cash Buffer**, and **Balance Range (P10–P50)**, plus **View**, **Re-optimize**, and **Delete** actions.

A **Stale** badge means the scenario changed after the optimization ran — balances moved, you added an account, you changed the retirement date. The numbers are no longer trustworthy; click **Re-optimize** to refresh them using the same confidence level and risk tolerance as before.

Deleting a guardrail profile asks first: *"Delete this guardrail profile? The scenario will revert to its spending profile for projections."*
