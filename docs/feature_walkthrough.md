[← Back to README](../README.md)

# Feature Walkthrough

This walkthrough steps through every major feature in WealthView. It doubles as a manual test script -- follow it end-to-end to verify the application is working correctly. An appendix at the bottom traces one vertical slice through the code, so you can see which class handles each step.

## Prerequisites

```bash
# Once, at the repo root: create .env and wire the npm workspaces
cp .env.example .env
npm install

# Terminal 1: Start PostgreSQL (published on localhost:5433)
docker compose up -d db

# Terminal 2: Start backend
cd backend
mvn clean install -DskipTests
mvn -pl wealthview-app spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3: Start frontend
cd frontend
npm run dev
```

The `-Dspring-boot.run.profiles=dev` flag is required: the default profile declares `${DB_PASSWORD}` and `${JWT_SECRET}` with no fallback, so the app will not boot without it. `npm install` must run at the **repo root** — that is what creates the `node_modules/@wealthview/shared` symlink the frontend imports from.

Open the frontend in a browser (typically http://localhost:5173).

---

### 1. Login

1. Navigate to http://localhost:5173/login.
2. Enter `admin@wealthview.local` / `admin123`.
3. Click **Login**.
4. **Verify:** You are redirected to the Dashboard page.

The `dev` profile also seeds `demo@wealthview.local` / `demo123` and `demo-admin@wealthview.local` / `demo123`.

---

### 2. Dashboard

1. After login, you land on the Dashboard (`/`).
2. **Verify:** The page shows a Net Worth summary card, an asset allocation pie chart, and account balance cards.
3. **Verify:** A **Recent stock splits** panel is present. On a fresh database it reads "No stock splits affect your portfolio yet. New splits are detected automatically each night."
4. If this is a fresh database, values will be zero until you add accounts and holdings.

---

### 3. Create an Investment Account

1. Navigate to **Accounts** in the sidebar (or go to `/accounts`).
2. Click **New Account**.
3. Fill in the four fields:
   - **Name:** `Fidelity Brokerage`
   - **Type:** select `Brokerage` (the dropdown offers Brokerage, IRA, 401(k), Roth IRA, Bank)
   - **Institution:** `Fidelity`
   - **Currency:** leave as `USD`
4. Click **Create**.
5. **Verify:** A "Account created" toast appears and the new account card appears in the accounts list with a type badge, a balance, and Institution / Created tiles.

---

### 3a. Multi-Currency Account

1. From **Accounts**, click **New Account** again.
2. Fill in **Name:** `Euro Savings`, **Type:** `Bank`, **Institution:** `N26`, and type `EUR` in the **Currency (e.g. USD, EUR)** field (it accepts three characters and auto-uppercases).
3. Click **Create**.
4. **Verify:** The account card shows a pink `EUR` badge and its balance is formatted in euros.
5. **Verify:** The Dashboard net worth only converts this account once an exchange rate exists — see step 24a.

---

### 4. Add Transactions

1. Click on **Fidelity Brokerage** to open the account detail page.
2. In the **Add Transaction** form:
   - **Date:** `2025-01-15`
   - **Type:** `buy`
   - **Symbol:** `VOO`
   - **Quantity:** `10`
   - **Amount:** `5000`
3. Click **Add Transaction**.
4. **Verify:** The transaction appears in the history table and a **VOO** holding is auto-created with quantity 10 and cost basis $5,000.
5. Add a second transaction:
   - **Date:** `2025-02-01`, **Type:** `buy`, **Symbol:** `AAPL`, **Quantity:** `25`, **Amount:** `5500`
6. **Verify:** Two holdings now appear (VOO and AAPL) in the Holdings table, which has Quantity and **Cost Basis** columns that can be edited inline.

---

### 5. Add a Price and Check Valuation

1. Navigate to **Prices** in the sidebar (`/prices`). The **Add Manual Price** card only
   renders for admins and super-admins; a member or viewer sees the price table alone.
2. In the **Add Manual Price** card, enter:
   - **Symbol:** `VOO`
   - **Date:** today's date
   - **Price:** `540`
3. Click **Save**.
4. **Verify:** The price appears in the **Latest Prices** table (Symbol / Latest Date / Close Price / Source) and in **Recently Added**.
5. Navigate back to the **Dashboard**.
6. **Verify:** Net worth now reflects the market value of VOO holdings (10 x $540 = $5,400). AAPL falls back to cost basis ($5,500) since no price was entered.

---

### 5a. Holding Detail and Cost Basis

1. From the account detail page, click a holding (e.g. **VOO**) to open `/holdings/:id`.
2. **Verify:** The **Holding Summary** card shows a **Cost Basis** value that can be edited inline.
3. **Verify:** A **Splits affecting VOO** panel is present, listing any stock splits applied to that symbol (or the empty-state message).

---

### 6. Import Transactions from CSV

1. Navigate to the **Fidelity Brokerage** account detail page.
2. Click **Import** (or navigate to `/accounts/{id}/import`).
3. Select **Format:** `fidelity`.
4. Upload a Fidelity CSV export file (or any CSV with columns matching the Fidelity format).
5. Click **Import**.
6. **Verify:** Imported transactions appear in the account's transaction list. Holdings are recomputed. Duplicate transactions (if re-importing the same file) are skipped via content-hash deduplication.

---

### 7. Import from OFX/QFX

1. From the import page, switch to **OFX** format.
2. Upload an OFX or QFX file downloaded from your brokerage.
3. **Verify:** Transactions are parsed and imported. The import job appears in the import history.

---

### 8. Portfolio History Chart

1. Navigate to an account with holdings and historical price data (seed data covers VOO, VTI, AAPL, etc.).
2. Scroll to the **Portfolio History** chart section.
3. **Verify:** A line chart shows the theoretical historical portfolio value based on holdings multiplied by historical daily prices. Symbols without price data (e.g., money market funds like SPAXX) are skipped gracefully.

---

### 9. Create a Rental Property (Manual Balance)

1. Navigate to **Properties** in the sidebar (`/properties`).
2. Click **New Property**.
3. Fill in:
   - **Address:** `123 Oak Street`
   - **Purchase Price:** `350000`
   - **Purchase Date:** `2020-06-01`
   - **Current Value:** `400000`
   - **Mortgage Balance:** `280000`
4. Leave the **Loan Details** section collapsed (do not provide loan fields).
5. Click **Create**.
6. **Verify:** The property appears in the properties list with equity displayed ($400,000 - $280,000 = $120,000). No "Computed Balance" badge.

---

### 10. Add Rental Income and Expenses

1. Click on **123 Oak Street** to open the property detail page.
2. **Verify:** The mortgage shows a **Manual** badge.
3. Add income:
   - **Date:** `2025-01-01`, **Amount:** `2200`, **Category:** `rent`
4. Add an expense:
   - **Date:** `2025-01-15`, **Amount:** `1500`, **Category:** `mortgage`
5. Add another expense:
   - **Date:** `2025-01-20`, **Amount:** `150`, **Category:** `insurance`
6. **Verify:** The cash flow chart shows January with $2,200 income and $1,650 expenses, yielding $550 net cash flow. The Dashboard net worth now includes property equity.

---

### 10a. Create a Property with Loan Details (Computed Balance)

1. Go back to **Properties** (`/properties`).
2. Click **New Property**.
3. Fill in the basic fields:
   - **Address:** `456 Elm Avenue`
   - **Purchase Price:** `300000`
   - **Purchase Date:** `2020-01-01`
   - **Current Value:** `350000`
   - **Mortgage Balance:** `250000`
4. Click **Show Loan Details** to expand the loan section.
5. Fill in:
   - **Loan Amount:** `280000`
   - **Annual Interest Rate:** `6.5`
   - **Loan Term (months):** `360`
   - **Loan Start Date:** `2020-01-01`
   - Check **Use computed mortgage balance (amortization)**
6. Click **Create**.
7. **Verify:**
   - The property appears with a **Computed Balance** badge in the list.
   - The mortgage balance is NOT $250,000 (the manual value); it is the amortization-computed remaining balance.
   - Equity = Current Value minus the computed balance.

---

### 10b. View Loan Details on Property Detail Page

1. Click on **456 Elm Avenue** to open the detail page.
2. **Verify:**
   - The mortgage row shows a **Computed** badge (blue).
   - A **Loan Details** panel appears below the summary showing: Amount ($280,000), Rate (6.5%), Term (360 months), Start (2020-01-01).
   - The mortgage balance and equity are computed from amortization, not the manual $250,000.

---

### 10c. Toggle Between Computed and Manual Balance

1. Using the API, update the property to set `use_computed_balance: false`:
   ```bash
   curl -X PUT http://localhost:8080/api/v1/properties/{id} \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"address":"456 Elm Avenue","purchase_price":300000,"purchase_date":"2020-01-01",
          "current_value":350000,"mortgage_balance":250000,
          "loan_amount":280000,"annual_interest_rate":6.5,"loan_term_months":360,
          "loan_start_date":"2020-01-01","use_computed_balance":false}'
   ```
2. **Verify:** The mortgage balance reverts to $250,000 (manual) and equity becomes $100,000.
3. Toggle back to `use_computed_balance: true` and verify the computed balance returns.

---

### 10d. Partial Loan Details Validation

1. Using the API, try to create a property with only some loan fields:
   ```bash
   curl -X POST http://localhost:8080/api/v1/properties \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"address":"Bad Property","purchase_price":200000,"purchase_date":"2023-01-01",
          "current_value":210000,"loan_amount":180000}'
   ```
2. **Verify:** Returns **400 Bad Request** with the standard error envelope `{ "error": ..., "message": ..., "status": 400 }`, where the message explains that loan details must be provided in full or not at all.

---

### 10e. Fully Paid-Off Loan

1. Create a property with a loan that started 30+ years ago:
   ```bash
   curl -X POST http://localhost:8080/api/v1/properties \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"address":"700 Paid Off Circle","purchase_price":150000,"purchase_date":"1990-01-01",
          "current_value":400000,"mortgage_balance":0,
          "loan_amount":120000,"annual_interest_rate":8.0,"loan_term_months":360,
          "loan_start_date":"1990-01-01","use_computed_balance":true}'
   ```
2. **Verify:** Mortgage balance is $0 and equity equals the full current value ($400,000). The amortization calculator returns zero for loans past their term.

---

### 10f. Valuation History (Empty State)

1. Open any property's detail page.
2. **Verify:** If no valuations have been recorded, the page shows "No valuation history yet" with a **Refresh Valuation** button.

---

### 10g. Valuation Refresh

Zillow scraping is controlled by `app.zillow.enabled`. Note the profile difference:

- `application.yml` (the default, and therefore the `docker` / `prod` stacks) sets `enabled: false`.
- `application-dev.yml` sets `enabled: true`, so a **local `dev` run has Zillow enabled**.

1. Click the **Refresh Valuation** button on a property detail page.
2. **Verify (dev profile, Zillow enabled):** If the address is found on Zillow, a valuation is recorded and the history chart and table appear; the property's current value is updated to the Zestimate. Zillow may block or rate-limit scraping — empty results are handled gracefully (no crash, warning logged server-side).
3. **Verify (Zillow disabled):** A toast error appears; the endpoint returns **503 Service Unavailable**. This is expected behaviour. To reproduce locally, set `APP_ZILLOW_ENABLED=false` (or `app.zillow.enabled=false`) and restart the backend.

---

### 10h. Dashboard Reflects Computed Balances

1. Navigate to the **Dashboard** (`/`).
2. **Verify:**
   - Properties with `use_computed_balance: true` show equity computed from amortization (not the manual mortgage balance).
   - Properties with `use_computed_balance: false` show equity from the manual mortgage balance.
   - Net worth correctly sums all property equity, investment holdings, and cash.
   - The allocation pie chart includes a "property" slice.

---

### 10i. Property Analytics

1. Open the detail page for a property that has income, expenses, and loan details configured.
2. **Verify:** The **Investment Analytics** panel displays:
   - **Cap Rate** -- annual net operating income as a percentage of property value.
   - **Cash-on-Cash Return** -- annual cash flow relative to total cash invested.
   - **Equity Growth** -- change in equity over the selected period.
   - **Mortgage Progress** -- principal paid, remaining balance, and payoff percentage.
3. Each metric has an explanatory help icon or info section describing how it is calculated.
4. **Verify:** Properties without loan details show analytics where applicable and omit mortgage progress gracefully.

---

### 10j. Configure Property Depreciation

1. Open the detail page for an investment property (e.g., `123 Oak Street`).
2. Edit the property and add depreciation details:
   - **In-Service Date:** `2020-06-01`
   - **Land Value:** `70000` (non-depreciable portion)
   - **Depreciation Method:** `straight_line`
   - **Useful Life Years:** `27.5` (standard residential rental)
3. **Save** the property.
4. **Verify:** The property now shows depreciation configuration. For straight-line method, annual depreciation = (purchase_price - land_value) / useful_life_years = ($350,000 - $70,000) / 27.5 = ~$10,182/year.
5. This depreciation amount is used by the projection engine when computing taxes on rental income sources linked to this property.
6. **Verify (cost segregation):** If you supply structured asset-class allocations instead of a single straight-line life, the detail page shows a per-class breakdown including bonus depreciation and any 481(a) catch-up.

---

### 11. Create a Basic Retirement Projection

1. Navigate to **Projections** in the sidebar (`/projections`).
2. Click **New Scenario** and fill in the **Create Scenario** form.

   **All rate fields on this form are entered as percentages, not decimals** — type `3` for 3%, not `0.03`.

   - **Name:** `Basic 4% Rule`
   - **Retirement Date:** 20-25 years from now (e.g., `2050-01-01`)
   - **Birth Year:** your birth year
   - **End Age:** `90`
   - **Inflation Rate (%):** `3`
   - **Withdrawal Rate (%):** `4`
   - **Spending Plan:** `None (use withdrawal rate)`
   - **Withdrawal Strategy:** `Fixed Percentage` (the default)
   - **Account 1:** Initial Balance `500000`, Annual Contribution `20000`, Expected Return `7`
3. Click **Create Scenario**.
4. **Verify:** The scenario card appears in the grid.
5. Click the scenario name to open the detail page.
6. Click **Run Projection**.
7. **Verify:**
   - The **Balance Over Time** chart shows growth during working years, then gradual drawdown in retirement.
   - The **Annual Flows** chart shows green contribution bars pre-retirement and red withdrawal bars post-retirement.
   - The **Data Table** shows year-by-year numbers with the retirement transition row highlighted.
   - Summary cards show Final Balance, Years in Retirement, Peak Balance, and Depletion Year (if the portfolio runs out).

The scenario form also exposes **Dividend Yield (%)**, **Bond Interest Yield (%)**, **Investment Fees (%)** and an **Include 1928–1971 market history** toggle; leave these at their defaults for the basic run.

---

### 11a. Account Allocation Editor and Cost Basis

1. Open any scenario in **Edit** mode (button **Edit** on the detail page, or create a new one).
2. In an account card, find the **Allocation** field. By default it reads e.g. "Derived from holdings (60.0% US / 20.0% Intl / 15.0% Bond / 5.0% Cash)" with a **Customize allocation** button.
3. Click **Customize allocation**.
4. **Verify:** Four percentage inputs appear — **US Stocks (%)**, **Intl Stocks (%)**, **Bonds (%)**, **Cash (%)** — plus a running `Total: n%` readout and a **Reset to derived** button.
5. Set the four values to something that does not total 100 (e.g. 50/20/20/5).
6. **Verify:** The total shows in red with "— must sum to 100%", and clicking **Save & Re-run** is blocked with the message "One or more account allocations must sum to 100% before saving."
7. Correct the values to total 100 and save.
8. **Verify:** In the same account card, the **Cost Basis** field is an editable currency input for a Manual Entry account, and read-only grey text for an account linked to a real WealthView account (showing either the derived amount or "Available after first run"). Cost basis drives the capital-gains tax modelling on taxable pools.

---

### 11b. Household / Spouse Modeling

1. Edit a scenario and scroll to the **Spouse / Household** section.
2. Enter a **Spouse Birth Year** (this is a year, not a date). Leaving it blank models a single-person household.
3. **Verify:** Additional fields appear once a spouse birth year is set:
   - **Primary Death Age** and **Spouse Death Age**
   - **Survivor Spending Factor (%)** (defaults to 75)
   - **Community Property State** checkbox
   - **Model Uncertain Lifespans** checkbox
4. **Verify:** In the accounts section, each account card now also shows an **Owner** select (Primary / Spouse / Joint — Joint is disabled unless the account type is Taxable).
5. Tick **Model Uncertain Lifespans**.
6. **Verify:** **Primary Sex**, **Spouse Sex** (Blended (unset) / Male / Female) and **Longevity Age** appear, and the help text notes this affects the guardrail optimizer's Monte Carlo only, not the deterministic projection.
7. Save and re-run.
8. **Verify:** After the modeled first death, the data table shows survivor spending scaled by the survivor factor and the filing status flipping from married-filing-jointly to single.

Spouse Social Security is **not** on this form — model it as an Income Source with **Owner: Spouse** (see step 18).

---

### 12. Test Dynamic Percentage Strategy

1. Go back to **Projections** (`/projections`).
2. Create a new scenario:
   - **Name:** `Dynamic 4%`
   - Same parameters as above, but set **Withdrawal Strategy** to `Dynamic Percentage`.
3. Run the projection.
4. **Verify:** Unlike the fixed percentage strategy, withdrawals fluctuate with the portfolio balance. The portfolio should never fully deplete to zero (since you always withdraw a percentage of the remaining balance). Compare the data table withdrawals -- they should be `current_balance * 0.04` each year.

---

### 13. Test Vanguard Dynamic Spending Strategy

1. Create another scenario:
   - **Name:** `Vanguard Dynamic`
   - Set **Withdrawal Strategy** to `Vanguard Dynamic Spending`.
   - **Ceiling:** `5` (5% max increase year-over-year)
   - **Floor:** `-2.5` (2.5% max decrease year-over-year)
2. Run the projection.
3. **Verify:** Withdrawals are smoothed -- year-over-year changes in the data table should never exceed +5% or drop below -2.5% relative to the previous year's withdrawal.

---

### 14. Create a Multi-Pool Scenario with Roth Conversion

1. Create a new scenario:
   - **Name:** `Roth Conversion Ladder`
   - **Retirement Date:** 10 years from now
   - **End Age:** `85`
   - **Inflation Rate (%):** `2`
   - **Filing Status:** `Single`
   - **Annual Roth Conversion:** `50000`
   - **Other Income:** `0`
   - **Account 1:** Initial Balance `500000`, Contribution `20000`, Return `7`, **Account Type:** `Traditional`
   - **Account 2:** Initial Balance `100000`, Contribution `7000`, Return `7`, **Account Type:** `Roth`
2. Click **Create Scenario**, then open and run the projection.
3. **Verify:**
   - The **Balance Over Time** chart shows stacked colored areas: orange (traditional), green (roth), blue (taxable).
   - The **Data Table** includes columns for Traditional Balance, Roth Balance, Roth Conversion Amount, and Tax Liability.
   - Each year with a conversion shows a non-null Tax Liability computed from federal tax brackets.
   - Traditional balance decreases as funds convert to Roth.
   - Once the traditional balance is fully converted, no further conversions or conversion tax appear.
   - In retirement, withdrawals are drawn tax-free from the Roth pool.
   - Once the owner reaches RMD age, the data table shows a required-minimum-distribution amount for the traditional pool.

---

### 15. Create an All-Roth Portfolio

1. Create a scenario:
   - **Name:** `All Roth`
   - **Filing Status:** `Single`
   - **Account 1:** Initial Balance `500000`, Contribution `7000`, Return `7`, **Account Type:** `Roth`
2. Run the projection.
3. **Verify:** Tax Liability is zero (or null) for every year. Roth withdrawals in retirement are entirely tax-free.

---

### 16. Compare Scenarios

1. Navigate to **Projections** and click the **Compare Scenarios** link (or go to `/projections/compare`).
2. Select 2-3 scenarios from the dropdowns (e.g., `Basic 4% Rule`, `Dynamic 4%`, `Roth Conversion Ladder`).
3. Click **Compare**.
4. **Verify:**
   - An overlay area chart displays all selected scenarios with distinct colors (blue, green, purple).
   - A summary table below shows each scenario's Final Balance, Peak Balance, Depletion Year, and Years in Retirement.
   - Selecting fewer than 2 or more than 3 scenarios is prevented by the UI.

---

### 17. Create a Spending Profile

1. Navigate to **Spending Profiles** in the sidebar (`/spending-profiles`).
2. Click **New Profile**.
3. Fill in:
   - **Name:** `Moderate Retirement`
   - **Essential Expenses (annual):** `40000`
   - **Discretionary Expenses (annual):** `20000`
4. Optionally add **Spending Tiers** for age-based phases. Each tier has **Phase Name**, **Start Age**, **End Age (blank = forever)**, **Essential (annual)** and **Discretionary (annual)**:
   - **Tier 1:** Phase Name `Active Retirement`, Start Age `65`, Essential `45000`, Discretionary `25000`
   - **Tier 2:** Phase Name `Quiet Years`, Start Age `80`, Essential `30000`, Discretionary `10000`
5. Click **Create**.
6. **Verify:** The profile card appears with Essential, Discretionary, Monthly Equivalent and Spending Tiers tiles.
7. **Verify:** Below the profile list, a **Monte Carlo Guardrail Profiles** section lists any optimizer-generated profiles with Essential Floor, Failure Rate, Median Final Balance, Trials, Cash Buffer and Balance Range (P10-P50) tiles.

Guardrail profiles are spending profiles — a scenario has **at most one** active spending plan at a time, and the scenario form presents both kinds in a single **Spending Plan** dropdown.

---

### 18. Create Income Sources

1. Navigate to **Income Sources** in the sidebar (`/income-sources`).
2. Click **New Income Source**.
3. Create a Social Security income source:
   - **Name:** `Social Security - Primary`
   - **Income Type:** `Social Security` (the dropdown offers Social Security, Pension, Rental Property, Part-Time Work / Consulting, Annuity, Other)
   - **Owner:** `Primary`
   - **Tax Treatment:** click the `Partially Taxable` card (tax treatments are selectable cards, not a dropdown, and the available set depends on the income type)
   - **Annual Amount:** `24000`
   - **Start Age:** `67`
   - **End Age (blank = forever):** leave blank
   - **Inflation Rate (%):** `2` (entered as a percentage — the help text notes SS COLA is typically ~2%)
4. Click **Create**.
5. Create a rental income source:
   - **Name:** `Oak Street Rental`
   - **Income Type:** `Rental Property`
   - **Link to Property (optional):** select `123 Oak Street` (this links depreciation deductions and switches the amount label to **Annual Rent Amount**)
   - **Tax Treatment:** `Passive` (the alternatives are `Active - REPS` and `Active - STR`)
   - **Annual Rent Amount:** `26400` ($2,200/month)
   - **Start Age:** `55` (current age)
   - **Inflation Rate (%):** `2`
6. Click **Create**.
7. **Verify:** Both income sources appear in the list with Tax Treatment, Annual Adjustment and Monthly Equivalent tiles.
8. **Optional (household):** create a third source with **Owner:** `Spouse`, tick **Survivor Benefit** and set **Survivor % (%)** to model what the surviving spouse keeps.
9. **Optional (one-time):** tick **One-time payment (e.g., deferred compensation, inheritance)** and note that the form collapses to **Payment Amount** + **Payment Age**.

---

### 19. Link Income Sources and a Spending Plan to a Scenario

1. Navigate to **Projections** and open the **Basic 4% Rule** scenario detail page.
2. Click **Edit**.
3. In the **Spending Plan** dropdown, select `Moderate Retirement`. (This one dropdown lists regular spending profiles and any guardrail profile for the scenario — they are mutually exclusive; selecting one clears the other, and `None (use withdrawal rate)` clears both.)
4. In the **Income Sources** section, tick both `Social Security - Primary` and `Oak Street Rental`. Each has an optional inline **Override:** amount field (placeholder "Use default").
5. Click **Save & Re-run**.
6. **Verify:**
   - A spending plan summary card appears on the detail page.
   - Income sources are listed with their tax treatments.
   - The results tab bar now includes **Spending Analysis**, **Income & Tax** and **Income Streams** alongside **Balance Over Time**, **Annual Flows** and **Data Table** (a **Tax Shield** tab appears when rental deductions are in play).
   - Click **Spending Analysis** to see the stacked area chart with essential expenses (red), discretionary after cuts (amber), withdrawal line, and income streams line.
   - The **Data Table** now includes additional columns: Essential, Discretionary, Income, Net Need, Surplus/Deficit, and Discretionary After Cuts.
   - Tax liability reflects the tax treatment of each income source (Social Security via the IRS provisional-income formula, rental income with passive loss deductions including depreciation).
   - In early retirement years before Social Security starts, the net spending need is higher because there's no SS income offset. After Social Security kicks in at age 67, the net need drops and surplus increases.
   - **Rental income is reported NET** of property expenses (tax, insurance, maintenance, mortgage interest), not gross.

---

### 19a. Reclassify Unclassified Holdings

1. Run a projection for a scenario whose accounts are linked to real WealthView accounts holding an unusual or unseeded symbol.
2. **Verify:** If the engine could not classify a symbol, an orange notice appears above the results: "These holdings were modeled as US Stock because we couldn't classify them:".
3. For each listed symbol, pick an asset class from the dropdown — **US Stock**, **Intl Stock**, **Bonds** or **Cash**.
4. Click **Apply & re-run**.
5. **Verify:** The classification is saved and the projection re-runs with the corrected allocation.

This banner is the only place in the UI that sets a security's asset class; there is no general-purpose reclassification screen, and an already-classified symbol cannot be overridden from the web app.

---

### 20. Edit a Scenario

1. Open any scenario detail page.
2. Click **Edit** to enter edit mode.
3. Modify any fields -- e.g., change the withdrawal strategy from Fixed Percentage to Dynamic Percentage, add a second account, or change the end age.
4. Click **Save & Re-run**.
5. **Verify:** The scenario updates are saved, the projection re-runs automatically with the new parameters, and the results reflect the changes.
6. Click **Cancel Edit** during editing to discard changes and return to the read-only view.

---

### 21. Verify Spending Viability with Shortfall

1. Create a scenario with low initial balance and high spending:
   - **Name:** `Shortfall Test`
   - **Account 1:** Initial Balance `200000`, Contribution `5000`, Return `5`
   - **Spending Plan:** `Moderate Retirement`
   - **Retirement Date:** 5 years from now
2. Run the projection.
3. **Verify:**
   - In the **Data Table**, some retirement years show a negative Surplus/Deficit value, meaning withdrawals don't cover spending.
   - The **Discretionary After Cuts** column shows reduced discretionary spending (below the original $20,000 inflated amount) in deficit years.
   - Essential expenses are always fully covered -- shortfalls come entirely from discretionary.
   - The **Spending Analysis** chart visually shows the gap between withdrawals and spending needs.

---

### 21a. Optimize Spending (Monte Carlo Guardrails)

1. From a scenario detail page, click **Optimize Spending** (route `/projections/:id/optimize`).
2. Run the optimization.
3. **Verify:** A guardrail profile is produced with a per-year spending schedule, and the scenario's **Spending Plan** now points at it (the previously selected spending profile is cleared — the two are mutually exclusive).
4. **Verify:** Risk tolerance maps to a target success probability: conservative 0.95, moderate 0.90, aggressive 0.80.

---

### 22. Delete a Scenario

1. From the **Projections** list, click the delete button on one of the test scenarios.
2. **Verify:** The scenario is removed from the list.

---

### 23. Invite a New User (Admin)

`/settings` no longer exists as a page — it redirects to the consolidated **Admin** area.

1. Navigate to **Admin** in the sidebar (`/admin`). The link only appears for `admin` and `super_admin` roles.
2. In the left sub-navigation, click **Invite Codes**.
3. Choose an expiry (**1 day**, **7 days**, **30 days**, **90 days**) and click **Generate Code**.
4. **Verify:** A new invite code appears in the table (Code / Created By / Created / Expires / Status / Used By / Actions) with **Copy** and **Revoke** row actions.
5. Click **Copy** to copy the code.
6. Log out and navigate to `/register`.
7. Register a new user with the invite code.
8. **Verify:** Registration succeeds and the new user can log in. The new user belongs to the same tenant and can see the same accounts, properties, and projections.
9. Back in **Admin → Invite Codes**, **Verify:** the code's status now shows it as used, and **Revoke** on an unused code flips its status to `Revoked`.

---

### 24. Check the Audit Log

`/audit-log` no longer exists as a page — it redirects to `/admin`.

1. Log in as an admin user.
2. Navigate to **Admin** (`/admin`) and click **Audit Log** in the left sub-navigation.
3. **Verify:** The section shows a paginated list of actions performed -- account creates, property updates, projection runs, login events, etc., with Time / Action / Entity Type / Details columns.
4. Use the filter dropdown (default **All**) to narrow by entity type.
5. **Verify:** An empty result set renders "No audit log entries" rather than a blank table.

---

### 24a. Manage Exchange Rates (Admin)

1. Navigate to **Admin** → **Exchange Rates**.
2. **Verify:** With no rates configured the section reads "No exchange rates configured. All accounts use USD."
3. Click **Add Currency**, then in the **Add Exchange Rate** card enter **Currency Code** `EUR` and a value in the **1 EUR = ? USD** field (e.g. `1.08`). Click **Save**.
4. **Verify:** The table shows `1 EUR = 1.08 USD` with Currency / Rate to USD / Last Updated columns and **Edit** / **Delete** row actions.
5. Return to the **Dashboard**.
6. **Verify:** The `Euro Savings` account from step 3a is now converted at the configured rate when rolled into net worth.
7. Try **Delete** on a rate still used by an account.
8. **Verify:** The delete is rejected with a toast explaining that accounts may still use this currency.

---

### 24b. Price Administration and Stock Splits (Admin)

1. Navigate to **Admin** → **Prices**. `/admin/prices` also redirects here.
2. **Verify:** Four tabs are present — **Finnhub Sync**, **Yahoo Finance**, **CSV Upload**, **Browse**.
3. On **Finnhub Sync**, **Verify:** a **Price Sync Status** table (Symbol / Latest Date / Source / Status) and a **Sync All Holdings** button.
4. On **Yahoo Finance**, **Verify:** a symbols field, **Fetch Preview**, **Save All** and **Sync All Holdings from Yahoo**.
5. On **CSV Upload**, **Verify:** an **Upload Price CSV** form. On **Browse**, **Verify:** a symbol **Search**.
6. Navigate to **Admin** → **Stock Splits** (super-admin only).
7. **Verify:** three cards — **Sync from Finnhub** (button **Sync now**), **Add manual split** (Symbol, effective date, **Numerator** default 4, **Denominator** default 1, button **Add split**) and **Applied splits** (Symbol / Effective date / Ratio / Source / Applied / Actions).
8. Add a manual 4:1 split for a symbol you hold.
9. **Verify:** A "Split applied for {SYM}" toast appears; the holding's quantity is multiplied by 4 and its per-share cost basis divided by 4; historical prices for that symbol are adjusted; and the split now shows on the Dashboard's **Recent stock splits** panel and on the holding detail page.
10. Click **Un-apply** on that split and confirm the prompt.
11. **Verify:** Transactions and prices are restored to their pre-split values and a "Split unapplied" toast appears. There is no hard delete — only un-apply.

Splits are also detected automatically: a daily Finnhub sync plus a one-time backfill keep transactions, holdings and historical prices split-adjusted, so the manual entry above is a fallback rather than the normal path.

---

### 24c. Users, Tenants and System Config (Admin)

1. Navigate to **Admin** → **Users**.
2. **Verify:** A table of Email / Role / Tenant / Joined / Status / Actions with an inline role select. A super admin sees Super Admin / Admin / Member / Viewer; a tenant admin sees the same list without Super Admin.
3. **Verify:** Row actions are **Reset PW** (opens a **Reset Password** modal), **Deactivate** / **Activate**, and **Delete** (super admin) or **Remove** (tenant admin). There is no "create user" button — new users arrive via invite codes.
4. As a super admin, navigate to **Admin** → **Dashboard**.
5. **Verify:** Stat cards for Total Users, Active Users (30d), Tenants, Accounts, Holdings, Transactions, Database Size, Symbols Tracked and Stale Symbols, plus a **Recent Login Activity** table (Email / Time / IP Address / Status).
6. Navigate to **Admin** → **Tenants** (super admin only).
7. **Verify:** A **Create Tenant** form and a table of Name / Users / Accounts / Status / Created.
8. Navigate to **Admin** → **System Config** (super admin only).
9. **Verify:** Grouped cards — **API Keys**, **Application Settings**, **Price Sync** and **Other** — with per-row **Edit** → **Save** / **Cancel**, **Show** / **Hide** for secret values, and a toggle for boolean keys.

MFA (TOTP), active-session management, notification preferences and mobile app version pinning are **backend-only** at present — the REST endpoints exist but there is no web UI for them, so they cannot be exercised from this walkthrough.

---

### 25. Export Data

1. Navigate to **Export** (`/export`).
2. In the **Full Export (JSON)** card, click **Download JSON**.
3. **Verify:** A "JSON export downloaded" toast appears and the file contains all tenant data (accounts, transactions, holdings, properties, projections, etc.).
4. In the **CSV Export** card, click each of the four buttons: **Accounts**, **Transactions**, **Holdings**, **Properties**.
5. **Verify:** Each CSV file downloads with the expected data and column headers, and a "{type} export downloaded" toast appears.

---

### 26. Verify Backward Compatibility

1. Log back in as admin.
2. If pre-existing scenarios were created before income sources were added, open one and run it.
3. **Verify:**
   - The projection runs without errors.
   - Pool breakdown columns are absent (null) for legacy single-pool scenarios.
   - Spending viability columns are absent (null) for scenarios without a spending plan.
   - The default Fixed Percentage strategy is applied.

---

### 27. Check the Dashboard with Full Data

1. Navigate back to the **Dashboard** (`/`).
2. **Verify:** Net worth now includes:
   - Investment account holdings (valued at latest prices or cost basis).
   - Property equity (current value minus mortgage balance).
   - Cash account balances.
   - Non-USD accounts converted at their configured exchange rate.
3. The allocation pie chart breaks down assets by type.

---

## Summary Checklist

| # | Feature | What to verify |
|---|---------|----------------|
| 1 | Login | Redirects to dashboard |
| 2 | Dashboard | Net worth, pie chart, account cards, recent-splits panel |
| 3 | Create account | Appears in list; type select, currency field |
| 3a | Multi-currency account | Non-USD badge, currency-formatted balance |
| 4 | Add transactions | Holdings auto-computed |
| 5 | Add manual price | Valuation updates on dashboard |
| 5a | Holding detail | Editable cost basis, per-symbol splits panel |
| 6 | CSV import | Transactions imported, dedup works |
| 7 | OFX import | Transactions parsed correctly |
| 8 | Portfolio history | Chart renders with historical prices |
| 9 | Create property (manual) | Equity = current value - manual mortgage balance |
| 10 | Rental income/expenses | Cash flow chart and net calculation |
| 10a | Property with loan details | Computed balance via amortization, "Computed Balance" badge |
| 10b | Loan details display | Loan panel on detail page, Computed badge on mortgage |
| 10c | Toggle computed/manual | Balance switches between amortization and manual on toggle |
| 10d | Partial loan validation | 400 error envelope when only some loan fields provided |
| 10e | Paid-off loan | Zero balance for loans past their term |
| 10f | Valuation history empty | "No valuation history yet" placeholder with refresh button |
| 10g | Valuation refresh | Enabled on `dev`; 503 when `app.zillow.enabled=false` |
| 10h | Dashboard computed balances | Net worth uses computed balance for flagged properties |
| 10i | Property analytics | Cap rate, cash-on-cash, equity growth, mortgage progress |
| 10j | Property depreciation | Straight-line config; cost-seg class breakdown |
| 11 | Fixed % projection | Balance chart, flows, data table, summary cards |
| 11a | Allocation editor + cost basis | Four-way allocation must sum to 100%; cost basis field |
| 11b | Household / spouse | Spouse fields, per-account owner, survivor factor, mortality toggle |
| 12 | Dynamic % projection | Withdrawals track current balance, never depletes |
| 13 | Vanguard dynamic | Withdrawal changes capped at ceiling/floor |
| 14 | Roth conversion | Pool tracking, tax computed, stacked chart, RMDs |
| 15 | All-Roth portfolio | Zero tax liability on withdrawals |
| 16 | Compare scenarios | Overlay chart, summary table, 2-3 scenarios |
| 17 | Spending profiles | Essential/discretionary/tiers; guardrail profiles listed |
| 18 | Income sources | Types, owner, tax-treatment cards, property link |
| 19 | Link income + spending plan | Spending analysis, NET rental income, extra tabs |
| 19a | Reclassify unclassified symbols | Orange notice, asset-class select, Apply & re-run |
| 20 | Edit scenario | Save & Re-run updates scenario and reruns projection |
| 21 | Spending shortfall | Discretionary absorbs deficit, essential fully covered |
| 21a | Optimize spending | Guardrail profile created, clears spending profile |
| 22 | Delete scenario | Removed from list |
| 23 | Invite + register (Admin) | `/settings` redirects to `/admin` → Invite Codes |
| 24 | Audit log (Admin) | `/audit-log` redirects to `/admin` → Audit Log |
| 24a | Exchange rates (Admin) | Add/edit/delete rate; dashboard converts |
| 24b | Prices + stock splits (Admin) | Four price tabs; apply/un-apply split |
| 24c | Users/tenants/config (Admin) | Role select, stat cards, grouped config |
| 25 | Data export | JSON + four CSV downloads |
| 26 | Backward compat | Old scenarios run without errors, spending fields null |
| 27 | Dashboard with data | All assets reflected in net worth |

---

## Appendix: One Vertical Slice Through the Code

Step 3 (create an account) and step 4 (add a transaction) touch every layer. Here is the actual path, useful when a walkthrough step misbehaves and you need to know where to look.

**Frontend**

| Layer | File |
|---|---|
| Page | `frontend/src/pages/AccountsListPage.tsx` (list + create/edit form), `frontend/src/pages/AccountDetailPage.tsx` |
| API client | `frontend/src/api/accounts.ts` — `listAccounts`, `getAccount`, `createAccount`, `updateAccount`, `deleteAccount`, `getTheoreticalHistory` |
| Hooks | `frontend/src/hooks/useApiQuery.ts`, `frontend/src/hooks/useApiMutation.ts` |
| Axios instance | `frontend/src/api/client.ts`, which calls `createApiClient` from `shared/src/api/client.ts` with `baseURL: '/api/v1'` |
| Types | `frontend/src/types/account.ts` (`AccountRequest`), re-exporting `AccountResponse` from `shared/src/api/types.ts` |

The web app authenticates with **HttpOnly cookies**, not a bearer header: `transport: 'cookie'` means `withCredentials` carries the tokens and CSRF uses a double-submit `XSRF-TOKEN` → `X-XSRF-TOKEN` echo. The `Authorization: Bearer` interceptor in the shared factory only runs for the mobile app's `transport: 'bearer'`. A 401 triggers a single coalesced `POST /auth/refresh` and one replay of the original request; if that fails the app redirects to `/login`.

**Backend**

| Layer | Class / file |
|---|---|
| Controller | `backend/wealthview-api/src/main/java/com/wealthview/api/controller/AccountController.java` — `@RequestMapping("/api/v1/accounts")` |
| Request/response records | `com.wealthview.core.account.dto.AccountRequest` / `AccountResponse` (in **wealthview-core**, not the api module) |
| Tenant identity | `com.wealthview.api.security.TenantUserPrincipal` — a record injected via `@AuthenticationPrincipal`; `tenantId()` is passed explicitly as the first argument to every service call, never read from a request parameter |
| Service | `backend/wealthview-core/src/main/java/com/wealthview/core/account/AccountService.java` — carries the `@Transactional` boundary (`readOnly = true` on reads); `computeAllBalances` is `@Cacheable("accountBalances")` |
| Repository | `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/AccountRepository.java`, extending `TenantScopedRepository<AccountEntity>` which supplies `findByTenant_IdAndId(UUID, UUID)` |
| Entity | `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/AccountEntity.java` |
| Migrations | `V003__create_accounts_table.sql` creates the table; `V053__add_currency_to_accounts.sql` adds the per-account `currency` column used in step 3a |
| Holdings recompute | `backend/wealthview-core/src/main/java/com/wealthview/core/holding/HoldingsComputationService.java` — `recomputeForAccountAndSymbol(...)`, called from `TransactionService.create(...)`, which also evicts the `accountBalances` cache |
| Error envelope | `backend/wealthview-api/src/main/java/com/wealthview/api/exception/GlobalExceptionHandler.java` returning `com.wealthview.api.dto.ErrorResponse` — `record ErrorResponse(String error, String message, int status)` |

Note that the `owner` concept exercised in step 11b lives on **projection accounts and income sources** (`V078__add_owner_to_projection_accounts.sql`, `V079__add_owner_survivor_to_income_sources.sql`), not on the `accounts` table.

---

## Related Docs

- [Frontend Pages](reference/frontend-routes.md) — Route table reference
- [API Reference](reference/api-reference.md) — Full endpoint documentation
- [Development Guide](development.md) — How to start dev servers
</content>
