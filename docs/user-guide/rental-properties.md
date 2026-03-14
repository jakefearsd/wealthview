[← Back to README](../../README.md)

# Rental Properties

WealthView tracks real estate alongside your investment accounts, giving you a complete picture of your net worth. You can monitor property values, mortgage payoff progress, rental income, expenses, depreciation, and key analytics like cap rate and cash-on-cash return.

---

## Creating a Property

1. Navigate to **Properties** in the sidebar.
2. Click **Add Property**.
3. Fill in the property details:
   - **Address** — The full street address.
   - **Purchase Price** — What you paid for the property.
   - **Purchase Date** — When you closed on the property.
   - **Current Value** — Your estimate of the property's current market value (can be updated manually or via Zillow sync).
   - **Property Type** — One of:
     - **Primary Residence** — Your main home.
     - **Investment** — A property held for rental income or appreciation.
     - **Vacation** — A vacation or second home.
4. Click **Save**.

---

## Mortgage Tracking

WealthView offers two approaches for tracking your mortgage balance.

### Manual Balance

The simplest approach: enter the **mortgage balance** directly on the property and update it periodically (e.g., when you get your monthly statement).

- Set the `mortgage_balance` field to the current outstanding balance.
- Update it whenever you want — there is no automatic calculation.
- Best for people who just want a quick snapshot of equity.

### Computed Amortization

For more precise tracking, provide your loan details and let WealthView compute the current balance based on an amortization schedule.

Enable computed amortization by providing **all four** of these fields:

| Field | Description | Example |
|-------|-------------|---------|
| **Loan Amount** | The original loan principal. | 320,000 |
| **Annual Interest Rate** | The annual rate as a decimal. | 0.065 (for 6.5%) |
| **Loan Term (months)** | The total loan term in months. | 360 (for 30 years) |
| **Loan Start Date** | When the loan originated. | 2020-06-01 |

Then enable the **Use Computed Balance** toggle.

WealthView calculates the remaining principal based on a standard amortization formula, giving you an accurate balance at any point in time without manual updates.

### Partial Loan Fields

You must provide either **all four** loan fields or **none** of them. If you provide some but not all (e.g., loan amount without the interest rate), WealthView returns a 400 error. This prevents incorrect amortization calculations from incomplete data.

---

## Zillow Valuation

WealthView can automatically update your property's current value using Zillow's public data.

### Setup

1. Find your property's **ZPID** (Zillow Property ID). You can find this in the URL when viewing your property on Zillow.com — it is the numeric ID in the URL.
2. Edit your property in WealthView and enter the ZPID in the **Zillow ZPID** field.
3. Ensure the `ZILLOW_ENABLED` environment variable is set to `true` in your deployment configuration.

### How It Works

- WealthView syncs property values from Zillow every **Sunday at 6:00 AM**.
- The sync updates the property's `current_value` and creates a valuation history record.
- You can view the valuation history on the property detail page to see how the estimated value has changed over time.

### Manual Refresh

If you do not want to wait for the weekly sync, the property detail page has a **Refresh Valuation** button that triggers an immediate Zillow lookup for that property.

### Without Zillow

If you prefer not to use Zillow, simply leave the ZPID field empty. Update the `current_value` manually whenever you want to reflect a new estimate.

---

## Recording Income

Track rental income and other property-related revenue from the property detail page.

1. Navigate to the property's detail page.
2. Click **Add Income**.
3. Fill in:
   - **Date** — When the income was received.
   - **Amount** — The dollar amount.
   - **Category** — Either `rent` or `other`.
   - **Frequency** — How often this income recurs: `monthly` or `annual`.

Recording income allows WealthView to calculate cash flow metrics and display income trends on the property detail page.

---

## Recording Expenses

Track all property-related costs the same way.

1. From the property detail page, click **Add Expense**.
2. Fill in:
   - **Date** — When the expense was incurred.
   - **Amount** — The dollar amount.
   - **Category** — One of the following:

| Category | What It Covers |
|----------|---------------|
| **Mortgage** | Monthly mortgage payments (principal + interest). |
| **Tax** | Property taxes. |
| **Insurance** | Homeowner's or landlord's insurance premiums. |
| **Maintenance** | Routine repairs, landscaping, cleaning between tenants. |
| **CapEx** | Capital expenditures: roof replacement, HVAC, major renovations. |
| **HOA** | Homeowners association fees. |
| **Management Fee** | Property management company fees. |

   - **Frequency** — `monthly` or `annual`.

---

## Cash Flow Charts

The property detail page includes a **cash flow chart** showing monthly income vs. expenses over time. This visualization helps you:

- See whether the property is cash-flow positive or negative each month.
- Identify seasonal patterns (e.g., higher maintenance costs in certain months).
- Track trends as rents increase or expenses change.

---

## Depreciation

Depreciation is a tax concept that allows you to deduct a portion of the property's cost each year, reducing your taxable rental income. WealthView models depreciation for use in retirement projections.

### Configuration

On the property detail page, configure depreciation with these fields:

| Field | Description |
|-------|-------------|
| **In-Service Date** | When the property was placed in service as a rental. |
| **Land Value** | The value of the land (land cannot be depreciated). The depreciable basis is the purchase price minus land value. |
| **Depreciation Method** | Choose from: `none`, `straight_line`, or `cost_segregation`. |
| **Useful Life (years)** | The depreciation period. Standard residential rental property uses 27.5 years. |

### Depreciation Methods

**Straight Line** — The most common method for residential rental property. The depreciable basis (purchase price minus land value) is divided evenly across the useful life. For example, a $300,000 property with $50,000 land value depreciated over 27.5 years yields approximately $9,091 per year.

**Cost Segregation** — An accelerated method that uses a custom depreciation schedule. Cost segregation studies reclassify components of the property (appliances, carpeting, site improvements) into shorter depreciation periods. If you have had a cost segregation study done, use this method with the appropriate useful life.

**None** — No depreciation is calculated. Use this for your primary residence or properties you do not want to depreciate.

### Depreciation in Projections

When you link a property to an income source in a retirement projection, the annual depreciation amount is used as a deduction against that income source's rental income. This can significantly reduce your projected tax liability. See [Spending and Income](spending-and-income.md) for details on linking properties to income sources.

---

## Property Analytics

The property detail page includes an **analytics panel** with key real estate investment metrics:

### Cap Rate

**Cap Rate = Net Operating Income / Property Value**

Net Operating Income (NOI) is annual rental income minus annual operating expenses (excluding mortgage payments and depreciation). Cap rate measures the property's return independent of financing. A higher cap rate indicates a better return on the property's value.

### Cash-on-Cash Return

**Cash-on-Cash Return = Annual Cash Flow / Total Cash Invested**

Annual cash flow is rental income minus all expenses (including mortgage payments). Total cash invested includes your down payment, closing costs, and any capital improvements. This metric tells you the actual return on the money you have put into the property.

### Equity Growth

Shows how your equity in the property has changed over a given period. Equity grows through:

- Mortgage principal paydown (each payment reduces the balance).
- Property appreciation (the current value increasing).

### Mortgage Progress

Displays your mortgage payoff status:

- **Principal Paid** — How much of the original loan you have paid down.
- **Remaining Balance** — What you still owe.
- **Payoff Percentage** — How far along you are toward paying off the loan.

---

## Properties on the Dashboard

Your properties contribute to your overall financial picture on the dashboard:

- **Net Worth** includes property equity (current value minus mortgage balance) for all properties.
- The **Asset Allocation** pie chart shows property equity alongside your investment accounts, giving you a sense of how much of your wealth is in real estate vs. securities.

---

## Linking Properties to Projections

Properties can be connected to your retirement projections through **income sources**. When you create an income source of type `rental_property` and link it to a property, the projection engine:

- Uses the property's rental income as a revenue stream in retirement.
- Applies the property's depreciation as a tax deduction against that rental income.
- Models the appropriate tax treatment based on your rental activity classification.

See [Spending and Income](spending-and-income.md) and [Retirement Projections](retirement-projections.md) for details on how property income flows into your retirement scenarios.
