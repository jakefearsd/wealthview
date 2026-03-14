[← Back to README](../README.md)

# Feature Walkthrough

This walkthrough steps through every major feature in WealthView. It doubles as a manual test script -- follow it end-to-end to verify the application is working correctly.

## Prerequisites

Start the backend and frontend dev servers:

```bash
# Terminal 1: Start PostgreSQL (if using Docker)
docker compose up -d db

# Terminal 2: Start backend
cd backend
mvn clean install -DskipTests
mvn -pl wealthview-app spring-boot:run

# Terminal 3: Start frontend
cd frontend
npm install
npm run dev
```

Open the frontend in a browser (typically http://localhost:5173).

---

### 1. Login

1. Navigate to http://localhost:5173/login.
2. Enter `admin@wealthview.local` / `admin123`.
3. Click **Login**.
4. **Verify:** You are redirected to the Dashboard page.

---

### 2. Dashboard

1. After login, you land on the Dashboard (`/`).
2. **Verify:** The page shows a Net Worth summary card, an asset allocation pie chart, and account balance cards.
3. If this is a fresh database, values will be zero until you add accounts and holdings.

---

### 3. Create an Investment Account

1. Navigate to **Accounts** in the sidebar (or go to `/accounts`).
2. Click **Add Account**.
3. Fill in:
   - **Name:** `Fidelity Brokerage`
   - **Type:** `brokerage`
   - **Institution:** `Fidelity`
4. Click **Create**.
5. **Verify:** The new account appears in the accounts list.

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
6. **Verify:** Two holdings now appear (VOO and AAPL).

---

### 5. Add a Price and Check Valuation

1. Navigate to **Prices** in the sidebar (`/prices`).
2. Enter a price for `VOO`:
   - **Symbol:** `VOO`
   - **Price:** `540`
   - **Date:** today's date
3. Click **Add Price**.
4. **Verify:** The price appears in the table.
5. Navigate back to the **Dashboard**.
6. **Verify:** Net worth now reflects the market value of VOO holdings (10 x $540 = $5,400). AAPL falls back to cost basis ($5,500) since no price was entered.

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
2. **Verify:** Returns **400 Bad Request** with message "Loan details must be provided in full (loanAmount, annualInterestRate, loanTermMonths, loanStartDate) or not at all".

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

### 10g. Valuation Refresh (Zillow Disabled)

1. Click the **Refresh Valuation** button on any property detail page.
2. **Verify:** A toast error appears because Zillow scraping is disabled by default (503 Service Unavailable). This is expected behavior.

---

### 10h. Valuation Refresh (Zillow Enabled)

To test with Zillow enabled:

1. Set `app.zillow.enabled=true` in `application.yml` (or via environment variable) and restart the backend.
2. Navigate to a property detail page and click **Refresh Valuation**.
3. **Verify:** If the address is found on Zillow, a valuation is recorded and the history chart and table appear. The property's current value is updated to the Zestimate.
4. **Note:** Zillow may block or rate-limit scraping. Empty results are handled gracefully (no crash, warning logged server-side).

---

### 10i. Dashboard Reflects Computed Balances

1. Navigate to the **Dashboard** (`/`).
2. **Verify:**
   - Properties with `use_computed_balance: true` show equity computed from amortization (not the manual mortgage balance).
   - Properties with `use_computed_balance: false` show equity from the manual mortgage balance.
   - Net worth correctly sums all property equity, investment holdings, and cash.
   - The allocation pie chart includes a "property" slice.

---

### 10j. Property Analytics

1. Open the detail page for a property that has income, expenses, and loan details configured.
2. **Verify:** The **Investment Analytics** panel displays:
   - **Cap Rate** -- annual net operating income as a percentage of property value.
   - **Cash-on-Cash Return** -- annual cash flow relative to total cash invested.
   - **Equity Growth** -- change in equity over the selected period.
   - **Mortgage Progress** -- principal paid, remaining balance, and payoff percentage.
3. Each metric has an explanatory help icon or info section describing how it is calculated.
4. Optionally change the `year` parameter (if the UI exposes it) to see analytics for a specific year.
5. **Verify:** Properties without loan details show analytics where applicable and omit mortgage progress gracefully.

---

### 10k. Configure Property Depreciation

1. Open the detail page for an investment property (e.g., `123 Oak Street`).
2. Edit the property and add depreciation details:
   - **In-Service Date:** `2020-06-01`
   - **Land Value:** `70000` (non-depreciable portion)
   - **Depreciation Method:** `straight_line`
   - **Useful Life Years:** `27.5` (standard residential rental)
3. **Save** the property.
4. **Verify:** The property now shows depreciation configuration. For straight-line method, annual depreciation = (purchase_price - land_value) / useful_life_years = ($350,000 - $70,000) / 27.5 = ~$10,182/year.
5. This depreciation amount is used by the projection engine when computing taxes on rental income sources linked to this property.

---

### 11. Create a Basic Retirement Projection

1. Navigate to **Projections** in the sidebar (`/projections`).
2. Fill in the **Create Scenario** form:
   - **Name:** `Basic 4% Rule`
   - **Retirement Date:** 20-25 years from now (e.g., `2050-01-01`)
   - **End Age:** `90`
   - **Inflation Rate:** `0.03`
   - **Withdrawal Strategy:** `Fixed Percentage` (the default)
   - **Withdrawal Rate:** `0.04`
   - **Account 1:** Initial Balance `500000`, Annual Contribution `20000`, Expected Return `0.07`
3. Click **Create Scenario**.
4. **Verify:** The scenario card appears in the grid.
5. Click the scenario name to open the detail page.
6. Click **Run Projection**.
7. **Verify:**
   - The **Balance Over Time** chart shows growth during working years, then gradual drawdown in retirement.
   - The **Annual Flows** chart shows green contribution bars pre-retirement and red withdrawal bars post-retirement.
   - The **Data Table** shows year-by-year numbers with the retirement transition row highlighted.
   - Summary cards show Final Balance, Years in Retirement, Peak Balance, and Depletion Year (if the portfolio runs out).

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
   - **Ceiling:** `0.05` (5% max increase year-over-year)
   - **Floor:** `-0.025` (2.5% max decrease year-over-year)
2. Run the projection.
3. **Verify:** Withdrawals are smoothed -- year-over-year changes in the data table should never exceed +5% or drop below -2.5% relative to the previous year's withdrawal.

---

### 14. Create a Multi-Pool Scenario with Roth Conversion

1. Create a new scenario:
   - **Name:** `Roth Conversion Ladder`
   - **Retirement Date:** 10 years from now
   - **End Age:** `85`
   - **Inflation Rate:** `0.02`
   - **Filing Status:** `Single`
   - **Annual Roth Conversion:** `50000`
   - **Other Income:** `0`
   - **Account 1:** Initial Balance `500000`, Contribution `20000`, Return `0.07`, **Account Type:** `Traditional`
   - **Account 2:** Initial Balance `100000`, Contribution `7000`, Return `0.07`, **Account Type:** `Roth`
2. Click **Create Scenario**, then open and run the projection.
3. **Verify:**
   - The **Balance Over Time** chart shows stacked colored areas: orange (traditional), green (roth), blue (taxable).
   - The **Data Table** includes columns for Traditional Balance, Roth Balance, Roth Conversion Amount, and Tax Liability.
   - Each year with a conversion shows a non-null Tax Liability computed from federal tax brackets.
   - Traditional balance decreases as funds convert to Roth.
   - Once the traditional balance is fully converted, no further conversions or conversion tax appear.
   - In retirement, withdrawals are drawn tax-free from the Roth pool.

---

### 15. Create an All-Roth Portfolio

1. Create a scenario:
   - **Name:** `All Roth`
   - **Filing Status:** `Single`
   - **Account 1:** Initial Balance `500000`, Contribution `7000`, Return `0.07`, **Account Type:** `Roth`
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
   - **Essential Expenses:** `40000`
   - **Discretionary Expenses:** `20000`
4. Optionally add spending tiers for age-based phases:
   - **Tier 1:** Label `Active Retirement`, Start Age `65`, Essential `45000`, Discretionary `25000`
   - **Tier 2:** Label `Quiet Years`, Start Age `80`, Essential `30000`, Discretionary `10000`
5. Click **Create**.
6. **Verify:** The profile card appears showing essential, discretionary, and any spending tiers.

---

### 18. Create Income Sources

1. Navigate to **Income Sources** in the sidebar (`/income-sources`).
2. Click **New Income Source**.
3. Create a Social Security income source:
   - **Name:** `Social Security - Primary`
   - **Income Type:** `social_security`
   - **Annual Amount:** `24000`
   - **Start Age:** `67`
   - **End Age:** (leave blank for lifetime)
   - **Tax Treatment:** `partially_taxable`
   - **Inflation Rate:** `0.02`
4. Click **Create**.
5. Create a rental income source:
   - **Name:** `Oak Street Rental`
   - **Income Type:** `rental_property`
   - **Annual Amount:** `26400` ($2,200/month)
   - **Start Age:** `55` (current age)
   - **End Age:** (leave blank)
   - **Tax Treatment:** `rental_passive`
   - **Property:** select `123 Oak Street` (links depreciation deductions)
   - **Inflation Rate:** `0.02`
6. Click **Create**.
7. **Verify:** Both income sources appear in the list with their tax treatments displayed.

---

### 19. Link Income Sources and Spending Profile to a Scenario

1. Navigate to **Projections** and open the **Basic 4% Rule** scenario detail page.
2. Click **Edit**.
3. In the **Spending Profile** dropdown, select `Moderate Retirement`.
4. In the **Income Sources** section, link both `Social Security - Primary` and `Oak Street Rental`.
5. Click **Save & Re-run**.
6. **Verify:**
   - A spending profile summary card appears on the detail page.
   - Income sources are listed with their tax treatments.
   - A fourth tab **Spending Analysis** appears in the results tabs.
   - Click **Spending Analysis** to see the stacked area chart with essential expenses (red), discretionary after cuts (amber), withdrawal line, and income streams line.
   - The **Data Table** now includes additional columns: Essential, Discretionary, Income, Net Need, Surplus/Deficit, and Discretionary After Cuts.
   - Tax liability reflects the tax treatment of each income source (Social Security at 85% taxable, rental income with passive loss deductions including depreciation).
   - In early retirement years before Social Security starts (if applicable), the net spending need is higher because there's no SS income offset.
   - After Social Security kicks in at age 67, the net need drops and surplus increases.

---

### 20. Edit a Scenario

1. Open any scenario detail page.
2. Click **Edit** to enter edit mode.
3. Modify any fields -- e.g., change the withdrawal strategy from Fixed Percentage to Dynamic Percentage, add a second account, or change the end age.
4. Click **Save & Re-run**.
5. **Verify:** The scenario updates are saved, the projection re-runs automatically with the new parameters, and the results reflect the changes.
6. Click **Cancel** during editing to discard changes and return to the read-only view.

---

### 21. Verify Spending Viability with Shortfall

1. Create a scenario with low initial balance and high spending:
   - **Name:** `Shortfall Test`
   - **Account 1:** Initial Balance `200000`, Contribution `5000`, Return `0.05`
   - **Spending Profile:** `Moderate Retirement`
   - **Retirement Date:** 5 years from now
2. Run the projection.
3. **Verify:**
   - In the **Data Table**, some retirement years show a negative Surplus/Deficit value, meaning withdrawals don't cover spending.
   - The **Discretionary After Cuts** column shows reduced discretionary spending (below the original $20,000 inflated amount) in deficit years.
   - Essential expenses are always fully covered -- shortfalls come entirely from discretionary.
   - The **Spending Analysis** chart visually shows the gap between withdrawals and spending needs.

---

### 22. Delete a Scenario

1. From the **Projections** list, click the delete button on one of the test scenarios.
2. **Verify:** The scenario is removed from the list.

---

### 23. Invite a New User (Admin)

1. Navigate to **Settings** (`/settings`).
2. Under **Invite Codes**, click **Generate Invite Code**.
3. **Verify:** A new invite code appears in the list.
4. Copy the invite code.
5. Log out and navigate to `/register`.
6. Register a new user with the invite code.
7. **Verify:** Registration succeeds and the new user can log in. The new user belongs to the same tenant and can see the same accounts, properties, and projections.

---

### 24. Check Audit Log

1. Log in as an admin user.
2. Navigate to **Audit Log** (`/audit-log`).
3. **Verify:** The page shows a paginated list of all actions performed -- account creates, property updates, projection runs, login events, etc.
4. Filter by entity type to narrow results.

---

### 25. Export Data

1. Navigate to **Export** (`/export`).
2. Click **Export JSON** to download a full data export.
3. **Verify:** The JSON file contains all tenant data (accounts, transactions, holdings, properties, projections, etc.).
4. Click individual CSV export buttons (accounts, transactions, holdings).
5. **Verify:** Each CSV file contains the expected data with appropriate column headers.

---

### 26. Verify Backward Compatibility

1. Log back in as admin.
2. If pre-existing scenarios were created before income sources were added, open one and run it.
3. **Verify:**
   - The projection runs without errors.
   - Pool breakdown columns are absent (null) for legacy single-pool scenarios.
   - Spending viability columns are absent (null) for scenarios without a spending profile.
   - The default Fixed Percentage strategy is applied.

---

### 27. Check the Dashboard with Full Data

1. Navigate back to the **Dashboard** (`/`).
2. **Verify:** Net worth now includes:
   - Investment account holdings (valued at latest prices or cost basis).
   - Property equity (current value minus mortgage balance).
   - Cash account balances.
3. The allocation pie chart breaks down assets by type.

---

## Summary Checklist

| # | Feature | What to verify |
|---|---------|----------------|
| 1 | Login | Redirects to dashboard |
| 2 | Dashboard | Net worth, pie chart, account cards |
| 3 | Create account | Appears in list |
| 4 | Add transactions | Holdings auto-computed |
| 5 | Add price | Valuation updates on dashboard |
| 6 | CSV import | Transactions imported, dedup works |
| 7 | OFX import | Transactions parsed correctly |
| 8 | Portfolio history | Chart renders with historical prices |
| 9 | Create property (manual) | Equity = current value - manual mortgage balance |
| 10 | Rental income/expenses | Cash flow chart and net calculation |
| 10a | Property with loan details | Computed balance via amortization, "Computed Balance" badge |
| 10b | Loan details display | Loan panel on detail page, Computed badge on mortgage |
| 10c | Toggle computed/manual | Balance switches between amortization and manual on toggle |
| 10d | Partial loan validation | 400 error when only some loan fields provided |
| 10e | Paid-off loan | Zero balance for loans past their term |
| 10f | Valuation history empty | "No valuation history" placeholder with refresh button |
| 10g | Valuation refresh (disabled) | 503 when Zillow disabled |
| 10h | Valuation refresh (enabled) | Zestimate recorded, chart and table appear |
| 10i | Dashboard computed balances | Net worth uses computed balance for flagged properties |
| 10j | Property analytics | Cap rate, cash-on-cash, equity growth, mortgage progress |
| 10k | Property depreciation | Configure depreciation method and useful life |
| 11 | Fixed % projection | Balance chart, flows, data table, summary cards |
| 12 | Dynamic % projection | Withdrawals track current balance, never depletes |
| 13 | Vanguard dynamic | Withdrawal changes capped at ceiling/floor |
| 14 | Roth conversion | Pool tracking, tax computed, stacked chart |
| 15 | All-Roth portfolio | Zero tax liability on withdrawals |
| 16 | Compare scenarios | Overlay chart, summary table, 2-3 scenarios |
| 17 | Spending profiles | Create profile with essential/discretionary/spending tiers |
| 18 | Income sources | Create income sources with tax treatments |
| 19 | Link income + spending | Spending analysis with tax-aware income, depreciation deductions |
| 20 | Edit scenario | Save & re-run updates scenario and reruns projection |
| 21 | Spending shortfall | Discretionary absorbs deficit, essential fully covered |
| 22 | Delete scenario | Removed from list |
| 23 | Invite + register | New user joins tenant |
| 24 | Audit log | Paginated event history with filtering |
| 25 | Data export | JSON and CSV exports download correctly |
| 26 | Backward compat | Old scenarios run without errors, spending fields null |
| 27 | Dashboard with data | All assets reflected in net worth |

---

## Related Docs

- [Frontend Pages](reference/frontend-routes.md) — Route table reference
- [API Reference](reference/api-reference.md) — Full endpoint documentation
- [Development Guide](development.md) — How to start dev servers
