[← Back to README](../../README.md)

# Rental Properties

WealthView tracks real estate alongside your investment accounts, giving you a complete picture of your net worth. You can monitor property values, mortgage payoff progress, expenses, depreciation (including cost segregation), and investment metrics like cap rate and cash-on-cash return — then feed the whole thing into your retirement projections.

---

## Creating a Property

1. Navigate to **Properties** in the sidebar.
2. Click to open the **Create Property** form.
3. Fill in the details:

| Field | Description |
|-------|-------------|
| **Address** | The full street address. |
| **Purchase Price** | What you paid for the property. |
| **Purchase Date** | When you closed. |
| **Current Value** | Your estimate of the current market value. |
| **Mortgage Balance** | The outstanding balance. Can be overridden by computed amortization — see below. |
| **Property Type** | **Primary Residence**, **Investment**, or **Vacation**. |

4. Click **Create**.

Three optional panels sit below, each behind a **Show / Hide** toggle: **Loan Details**, **Financial Assumptions**, and **Depreciation**. You can fill them in now or come back later via **Edit** on the property detail page.

> **Property Type matters more than it looks.** Investment Metrics (cap rate, cash-on-cash, NOI) and the Hold vs. Sell analysis only appear for properties marked **Investment**. If you're tracking a rental, set it to Investment.

Only users with the **admin**, **member**, or **super-admin** role can create or edit properties. Viewers see the data but not the Edit button.

---

## Loan Details and Mortgage Tracking

There are two ways to track what you owe.

### Manual Balance

Enter the **Mortgage Balance** directly and update it when you feel like it — say, when your monthly statement arrives. Simple, and fine if you just want a rough equity number.

### Computed Amortization

Open **Loan Details** and provide all four fields, then tick **Use computed mortgage balance (amortization)**:

| Field | Description | Example |
|-------|-------------|---------|
| **Loan Amount** | The original loan principal. | 320,000 |
| **Annual Interest Rate (%)** | The annual rate as a percentage. | 6.5 |
| **Loan Term (months)** | Total loan term in months. | 360 (30 years) |
| **Loan Start Date** | When the loan originated. | 2020-06-01 |

WealthView then computes the remaining principal from a standard amortization schedule, so the balance stays accurate without you touching it. The property header shows a **Computed** or **Manual** badge so you always know which number you're looking at.

**All four or none.** If you supply some loan fields but not all of them, the save is rejected with: *"Loan details must be provided in full (loanAmount, annualInterestRate, loanTermMonths, loanStartDate) or not at all."* This prevents a half-configured loan producing a nonsense balance.

---

## Financial Assumptions

Open the **Financial Assumptions** panel to record the recurring costs and growth rate WealthView uses for cash flow, analytics, and projections:

| Field | Description |
|-------|-------------|
| **Annual Appreciation Rate (%)** | How fast you expect the property to appreciate, e.g. `3.0`. Used by the Hold vs. Sell analysis. |
| **Annual Property Tax ($)** | Yearly property tax. |
| **Annual Insurance Cost ($)** | Yearly homeowner's or landlord's insurance. |
| **Annual Maintenance Cost ($)** | Yearly maintenance budget. |

> **These take priority over logged expenses.** Once you set any of the three annual cost fields, WealthView switches to using them as the source of truth. Monthly cash flow derives `tax`, `insurance`, and `maintenance` from these annual figures (divided by 12), plus the amortized mortgage payment from your loan details — and it **excludes** any manually recorded expense in those same categories so nothing gets double-counted. Set them or log individual expenses, but understand which one is driving the numbers.

---

## Zillow Valuation

WealthView can pull an estimated value from Zillow.

### Enabling It

Zillow lookups are **off by default**. Your deployment must set `app.zillow.enabled=true` (the `ZILLOW_ENABLED` environment variable). If it isn't enabled, clicking refresh returns: *"Valuation service is not enabled. Set app.zillow.enabled=true to use this feature."*

### Weekly Sync

When enabled, WealthView looks up every property's value **every Sunday at 6:00 AM** and records a new valuation history entry sourced as `zillow`. A failure on one property is logged and skipped — it never aborts the run for the others.

### Manual Refresh and Picking the Right Property

The **Valuation History** card on the property detail page has a **Refresh Valuation** button. What happens next depends on what Zillow returns:

- If WealthView already knows this property's Zillow ID, it fetches that listing directly.
- Otherwise it searches by address.
  - **No results** — you get a toast: *"No Zillow results found for this address."*
  - **Exactly one result** — WealthView stores that property's Zillow ID automatically and fetches the value.
  - **Multiple results** — a **Multiple Properties Found** dialog appears: *"Zillow found multiple properties matching this address. Please select the correct one."* Each candidate shows its address, its Zillow ID, and its estimated value. Pick the right one and WealthView remembers it — every future refresh goes straight to that listing.

There is no field to type a Zillow ID into. Selecting it through this flow is the only way it gets set, which is deliberate: you're picking from listings Zillow actually returned rather than transcribing a number from a URL.

### Without Zillow

Just update **Current Value** by hand whenever you want. Everything else works the same.

---

## Valuation History

The **Valuation History** card charts value over time and lists each entry with its **Date**, **Value**, and **Source** (`zillow`, `appraisal`, or anything else you record).

---

## Recording Expenses

The **Monthly Expenses** card includes an **Add Expense** form:

| Field | Notes |
|-------|-------|
| **Date** | When the expense was incurred. |
| **Amount** | The dollar amount. |
| **Category** | See the table below. |
| **Frequency** | **Monthly** (default) or **Annual**. An annual entry is spread as amount ÷ 12 across the twelve months from its date. |
| **Description** | Free text. |

| Category | What It Covers |
|----------|---------------|
| **Mortgage** | Mortgage payments. |
| **Tax** | Property taxes. |
| **Insurance** | Homeowner's or landlord's insurance. |
| **Maintenance** | Routine repairs, landscaping, turnover cleaning. |
| **CapEx** | Capital expenditures: roof, HVAC, major renovations. |
| **HOA** | Homeowners association fees. |
| **Management Fee** | Property management company fees. |

Recorded expenses appear in a **Recorded Expenses** table (Date, Category, Amount, Frequency, Description) with a **Delete** action for admins and members.

---

## Where Rental Income Comes From

**You do not enter rental income on the property page.** Rental income lives on an **income source** of type Rental Property that you link to this property. That's what feeds projections, analytics, and the ROI comparison. See [Spending and Income](spending-and-income.md).

The property detail page shows a **Linked Income Sources** card listing each linked source with its annual and monthly amount, plus a link to manage them. If nothing is linked yet it says *"No income source linked"* and points you to the Income Sources page.

Because of this, the **Monthly Expenses** chart on the property page is exactly that — expenses only. To see rent against expenses, look at the chart under the linked income source on the Income Sources page.

---

## Cash Flow Views

Two different views, in two different places:

**Monthly Expenses** (property detail page) — a bar chart of total expenses per month over the trailing twelve months.

**Rent vs Expenses** (Income Sources page, under a property-linked income source) — the fuller picture. A segmented control switches between **Trailing 12 Mo**, **5 Year**, **10 Year**, **15 Year**, and **20 Year**.

- In **Trailing 12 Mo** mode you get tiles for **Total Income**, **Total Expenses**, and **Net Cash Flow**, with monthly bars broken out by expense category (Mortgage, Tax, Insurance, Maintenance, HOA, CapEx, Mgmt Fee) alongside a Rent Estimate and Net Cash Flow.
- In the multi-year modes the title changes to **Projected Cash Flow & Depreciation** and you get **Rental Income**, **Operating Expenses**, **Depreciation**, and **Net Cash Flow** bars, with tiles for each running total. Hovering a year shows **Taxable Income**, marked *(tax loss)* when depreciation pushes it negative.

That view carries an explanation worth internalizing: *"Depreciation is a non-cash tax deduction that reduces taxable income but does not affect cash flow. Years where depreciation exceeds net income create a tax loss that can shield other income from taxes."*

---

## Depreciation

Depreciation lets you deduct part of the property's cost each year, reducing taxable rental income. It's a paper expense — no cash leaves your pocket — which is why a rental can be cash-flow positive and show a tax loss at the same time.

Configure it in the **Depreciation** panel of the property form.

### Method

| Method | When to use |
|--------|-------------|
| **None** | Your primary residence, or anything you don't depreciate. |
| **Straight-Line** | The standard approach for residential rental property. |
| **Cost Segregation** | You've had a cost segregation study done and want to accelerate deductions. |

When you switch away from **None**, the In-Service Date auto-fills from your purchase date (marked *"Defaulted to purchase date"*) if it's blank.

### Common Fields

| Field | Description |
|-------|-------------|
| **In-Service Date** | When the property was placed in service as a rental. Required for any method other than None. |
| **Land Value ($)** | Land can't be depreciated. **Depreciable basis = purchase price − land value.** |

### Straight-Line

Add **Useful Life (years)** — 27.5 for standard residential rental property. WealthView shows a live summary of your **Depreciable Basis** and **Annual Depreciation**.

The engine applies the IRS **mid-month convention**: your first year is prorated based on the month it went into service, and the final partial year picks up the remainder so the total across all years exactly equals the basis.

If land value is greater than or equal to purchase price you get: *"Land value must be less than purchase price for depreciation."*

### Cost Segregation

A cost segregation study reclassifies parts of a building into shorter recovery periods so you can deduct them much faster. WealthView models this properly rather than just shortening the useful life.

Under **Asset Class Allocations**, split your depreciable basis across four buckets:

| Bucket | Typical contents |
|--------|------------------|
| **5-Year Property ($)** | Appliances, carpeting, fixtures |
| **7-Year Property ($)** | Office furniture, equipment |
| **15-Year Property ($)** | Land improvements, landscaping, fencing |
| **27.5-Year Structural ($)** | Everything else — auto-computed as the remainder, but you can edit it to override |

As you type into the 5-, 7-, and 15-year fields, the structural bucket recalculates automatically as whatever is left over.

Two more fields:

- **Bonus Depreciation Rate (%)** — Defaults to **100**. This share of your 5-, 7-, and 15-year allocations is deducted entirely in the in-service year. **The 27.5-year structural bucket gets no bonus** — it's always straight-lined over 27.5 years.
- **Study Year (optional)** — The year the cost segregation study was performed. *"If later than in-service year, triggers 481(a) catch-up."*

A live summary shows your **Year-1 Bonus Deduction** and **Annual Structural Depreciation**.

> **The allocations must equal the depreciable basis exactly.** The form warns you when they don't (*"Allocations total ($X) does not equal depreciable basis ($Y)"*), and the server rejects the save outright: *"Cost segregation allocations (…) must equal depreciable basis (…)."* A single penny of rounding will bounce it, so make the numbers tie out.

### The 481(a) Catch-Up

If you have owned the property for a few years, depreciated it straight-line, and *then* commissioned a cost segregation study, you don't lose the deductions you should have been taking. Section 481(a) lets you claim the difference in one lump.

Set **Study Year** to a year later than your in-service year and WealthView does this automatically:

1. It keeps the straight-line amounts you actually claimed for the years before the study.
2. It computes what you *would* have claimed under the accelerated method for those same years.
3. The difference lands entirely in the study year as a catch-up adjustment.
4. From the study year forward, the accelerated schedule continues normally.

This is often a very large one-year deduction. Talk to your CPA about whether it's the right move — the form gives you the modeling, not the advice.

### The Depreciation Schedule

Once configured, the property detail page shows a **Depreciation Schedule** card:

- A header line stating **Cost Segregation** or **Straight-Line Depreciation**, your **Depreciable Basis**, the **Bonus Rate**, and (for straight-line) the **Annual** amount.
- For cost segregation, an **Asset Class Breakdown** table with **Class**, **Allocation**, **Bonus**, **Annual SL**, and **SL Years** per bucket.
- A year-by-year table with **Tax Year**, **Annual Depreciation**, **Cumulative Taken**, and **Remaining Basis**. The current year's row is highlighted.

---

## Property Overview and Analytics

### Property Overview

A year selector at the top lets you look at **Trailing 12 Months** or any specific year since purchase.

- **Total Appreciation** — current value minus purchase price.
- **Appreciation %** — that, as a percentage of purchase price.
- **Months Remaining** — on the mortgage, when loan details exist.
- **Mortgage Payoff Progress** — a progress bar with **Principal Paid**, **Balance**, **Payoff Date**, and remaining months.
- **Equity Growth** — a chart with three lines: **Equity**, **Property Value**, and **Mortgage**, from your purchase month to today.

### Investment Metrics

This card only appears for properties typed **Investment** that have enough data to compute a cap rate. Five tiles, with the app's own explanations:

| Metric | Definition |
|--------|-----------|
| **Cap Rate** | Annual NOI ÷ property value. Measures return independent of financing. |
| **Cash-on-Cash Return** | Annual net cash flow ÷ cash invested. Your actual return accounting for leverage. |
| **Annual NOI** | Net Operating Income: rental income minus operating expenses, excluding mortgage. |
| **Net Cash Flow** | Cash remaining after all expenses including mortgage payments. |
| **Cash Invested** | Your out-of-pocket investment: purchase price minus loan amount. |

Rental income here is the sum of the annual amounts on income sources linked to this property. Operating expenses come from your **Financial Assumptions** fields if you've set any; otherwise WealthView falls back to summing your logged expenses over the selected window (excluding the mortgage category, since NOI is defined before financing).

The card's own summary: *"Cap Rate and NOI measure the property's operating performance independent of financing. Cash-on-Cash Return measures your actual return based on the cash you invested, factoring in leverage from your mortgage. A higher cap rate or cash-on-cash return indicates a stronger investment."*

---

## Hold vs. Sell Analysis

The question every landlord eventually asks: *would I be better off selling this and putting the money in the market?*

The **Hold vs. Sell Analysis** appears for **Investment** properties with at least one linked income source — one card per income source.

### Your Assumptions

| Input | Default | Range |
|-------|---------|-------|
| **Period** | 10 years | 5 / 10 / 15 / 20 years |
| **Investment Return %** | 7 | 0–20 |
| **Rent Growth %** | 3 | 0–10 |
| **Expense Inflation %** | 3 | 0–10 |

Change any of them and the analysis recalculates.

### What You Get

**Hold & Rent** — Property Value (grown at your property's appreciation rate), Mortgage Balance (amortized), Cumulative Cash Flow (rent grown, expenses inflated, mortgage payments until payoff), and the resulting **Net Worth**.

**Sell & Invest** — Gross Proceeds, **Selling Costs (6%)**, **Depreciation Recapture Tax**, **Capital Gains Tax**, **Net Proceeds (Invested)**, and the **Net Worth** after compounding those proceeds at your investment return.

A banner delivers the verdict: *"Holding is better by $X over 10 years"* (or *"Selling is better by…"*).

### The Assumptions Baked In

These are fixed and worth knowing before you act on the answer:

- Selling costs are **6%** of the sale price.
- Depreciation recapture is taxed at **25%** on all depreciation taken through the current year.
- Capital gains are taxed at a flat **15%**, applied to the gain remaining after recapture.
- If the property has no **Loan Details** configured, the hold side treats the mortgage as fully paid off — a manually entered balance is deliberately *not* projected forward, because there's no amortization schedule to project it with.
- If **Annual Appreciation Rate** is blank, the hold side assumes zero appreciation.

This card does not carry a disclaimer of its own, so here's one: those are simplified flat rates. Your actual recapture, capital gains bracket, state tax, and closing costs will differ. Treat the verdict as a directional comparison, not a number to sign a listing agreement over.

---

## Properties on the Dashboard

Your properties contribute to your overall financial picture:

- **Net Worth** includes property equity (current value minus the effective mortgage balance).
- The **Asset Allocation** view shows property equity alongside your investment accounts.

---

## Linking Properties to Projections

Properties reach your retirement projections through **income sources**. Create an income source of type Rental Property, link it to the property, and the projection engine will:

- Treat the rental income as a retirement income stream.
- Apply the property's depreciation as a deduction against that rental income, following the passive-loss rules for the tax treatment you chose.
- Include property equity in the projection's final **Net Worth**.
- Surface the depreciation benefit on the projection's **Tax Shield** tab.

See [Spending and Income](spending-and-income.md) for the income source setup and [Retirement Projections](retirement-projections.md) for how it flows through.

> WealthView provides planning estimates only, not tax advice. All tax calculations are approximations — especially around cost segregation, recapture, and passive-loss limits. Discuss these with your CPA.
