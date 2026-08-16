# Manual Test Cases

## Prerequisites

- App running via `./wv up` (which wraps `docker compose up --build -d` and waits for health)
- A `.env` at the repo root (`cp .env.example .env`) — the `db` service refuses to start without `DB_PASSWORD`
- URL: http://localhost:80
- Login: `demo@wealthview.local` / `demo123`
- `jq` and `python3` on the PATH for the API steps

### Auth pattern

The **web** login endpoint (`/api/v1/auth/login`) sets HttpOnly `access_token` / `refresh_token`
cookies and returns only an identity body — it no longer returns a token you can read with `jq`. For
scripted API testing use the **bearer** endpoint under `/api/v1/auth/token`, which returns the token
pair in the response body:

```bash
export TOKEN=$(curl -s http://localhost/api/v1/auth/token/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@wealthview.local","password":"demo123"}' \
  | jq -r '.access_token')
```

Bearer-authenticated requests are exempt from CSRF (an attacker page cannot set an `Authorization`
header), so `POST`/`PUT`/`DELETE` with `-H "Authorization: Bearer $TOKEN"` work without an
`X-XSRF-TOKEN` echo. Cookie-authenticated requests do need one.

### Resolving IDs

Do **not** hard-code entity UUIDs — they differ per database. Resolve them by name at the top of each
run:

```bash
# Pick a projection scenario
SCENARIO_ID=$(curl -s http://localhost/api/v1/projections -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[0].id')

# Pick a property by address substring
PROP_ID=$(curl -s http://localhost/api/v1/properties -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[] | select(.address | test("Escadera")) | .id')

# Map income-source names to IDs
curl -s http://localhost/api/v1/income-sources -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[] | "\(.id)  \(.name)  \(.income_type)"'
```

**Watch the response shape.** `/projections`, `/properties`, `/income-sources`, `/stock-splits` and
the per-account holdings endpoint return a **plain JSON array** — index them with `.[]`. `/accounts`
is paged and wraps its rows in a `data` field (not `content`) — index it with `.data[]`.

### Route changes to be aware of

`/settings`, `/audit-log` and `/admin/prices` are no longer pages. All three `Navigate` to the
consolidated **`/admin`** area, which uses a left sub-navigation rather than a tab bar. Any older test
script that navigates to those paths must be re-pointed at `/admin` and the correct sub-section.

---

## TC-PROJ-001: Projection Income Streams Show NET (Not GROSS) Rental Income

**Purpose:** Verify that the Income Streams chart and all projection data use net rental income (gross minus property expenses) rather than the gross annual amount.

**Background:** Rental income sources are linked to properties that have annual expenses (property tax, insurance, maintenance, mortgage interest). The projection engine must deduct these before reporting income.

**Setup:** A scenario with at least one linked `rental_property` income source whose property carries annual expenses. Resolve `SCENARIO_ID` and the rental source's ID as shown above.

### Step 1 — API: Run projection and verify income_by_source field exists

```bash
curl -s "http://localhost/api/v1/projections/$SCENARIO_ID/run" \
  -H "Authorization: Bearer $TOKEN" > /tmp/projection_result.json

python3 -c "
import json
with open('/tmp/projection_result.json') as f:
    data = json.load(f)
retired_with_ibs = [y for y in data['yearly_data'] if y['retired'] and y.get('income_by_source')]
print(f'Retired years with income_by_source: {len(retired_with_ibs)}')
assert len(retired_with_ibs) > 0, 'FAIL: No retired years have income_by_source'
print('PASS')
"
```

**Expected:** Multiple retired years have a non-null `income_by_source` map.

### Step 2 — API: Verify rental NET = GROSS minus property expenses

Hard-coded dollar expectations go stale the moment the demo data changes, so this step prints the
inputs and the projected figure side by side for inspection rather than asserting a magic number.

```bash
RENTAL_ID=$(curl -s http://localhost/api/v1/income-sources -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[] | select(.income_type=="rental_property") | .id' | head -1)
RENTAL_PROP=$(curl -s http://localhost/api/v1/income-sources -H "Authorization: Bearer $TOKEN" \
  | jq -r --arg id "$RENTAL_ID" '.[] | select(.id==$id) | .property_id')

curl -s "http://localhost/api/v1/income-sources/$RENTAL_ID" -H "Authorization: Bearer $TOKEN" > /tmp/src.json
curl -s "http://localhost/api/v1/properties/$RENTAL_PROP"   -H "Authorization: Bearer $TOKEN" > /tmp/prop.json

python3 -c "
import json
proj = json.load(open('/tmp/projection_result.json'))
src  = json.load(open('/tmp/src.json'))
prop = json.load(open('/tmp/prop.json'))

sid = src['id']
expenses = sum(prop.get(k) or 0 for k in
               ('annual_property_tax', 'annual_insurance_cost', 'annual_maintenance_cost'))

print('source          :', src['name'])
print('gross base      :', src['annual_amount'])
print('inflation rate  :', src['inflation_rate'])
print('property expense:', expenses)
print()

years = [y for y in proj['yearly_data'] if (y.get('income_by_source') or {}).get(sid)]
for y in years[:8]:
    print(f\"  year {y['year']}  age {y['age']}  projected={y['income_by_source'][sid]:.2f}\")
"
```

**Expected:** In each full (non-transition) year the projected figure equals the inflated gross rent
**minus** the property's annual expenses shown above — it must be visibly lower than the gross base,
not equal to or above it. The first and last year of the source's window are halved transition years,
so compare a middle year.

Mortgage interest is also deducted when the property carries loan details, so the gap will exceed the
three expense fields printed here for a mortgaged property.

### Step 3 — API: Verify income_streams_total equals sum of income_by_source

```bash
python3 -c "
import json
with open('/tmp/projection_result.json') as f:
    data = json.load(f)
mismatches = 0
for y in data['yearly_data']:
    if y.get('income_by_source'):
        total = sum(y['income_by_source'].values())
        ist = y['income_streams_total']
        if abs(total - ist) > 0.01:
            print(f'MISMATCH year {y[\"year\"]}: sum={total}, total={ist}')
            mismatches += 1
assert mismatches == 0, f'FAIL: {mismatches} mismatches'
print('All retired years: sum(income_by_source) == income_streams_total')
print('PASS')
"
```

**Expected:** Every retired year's `income_streams_total` exactly equals the sum of all values in its `income_by_source` map. This invariant is data-independent.

### Step 4 — UI: Income Streams chart shows NET amounts

1. Navigate to http://localhost:80 and log in
2. Go to **Projections** and open the scenario used above
3. Click the **Income Streams** tab (the tab bar is: Balance Over Time, Annual Flows, Data Table, Spending Analysis, Income & Tax, Income Streams, and Tax Shield when rental deductions apply)
4. Hover over a rental-source bar in a full (non-transition) retirement year

**Expected:** The tooltip value matches the NET figure printed by Step 2, not the gross annual amount.

### Step 5 — UI: Data Table Income column shows NET

1. On the same projection detail page, click the **Data Table** tab
2. Scroll to a retired year where rental income is active
3. Check the "Income" column value

**Expected:** The Income column matches `income_streams_total` from the API (NET, not GROSS).

---

## TC-PROJ-002: Income Source Age Boundaries in Projections

**Purpose:** Verify that income sources activate/deactivate at the correct ages and apply transition-year halving.

### Step 1 — API: Verify a source with an end_age stops after it

```bash
python3 -c "
import json
proj = json.load(open('/tmp/projection_result.json'))
srcs = json.load(open('/tmp/sources.json'))   # curl .../income-sources > /tmp/sources.json

for s in srcs:
    if s.get('end_age') is None:
        continue
    sid, end_age = s['id'], s['end_age']
    for y in proj['yearly_data']:
        if y['age'] in (end_age - 1, end_age, end_age + 1) and y['retired']:
            val = (y.get('income_by_source') or {}).get(sid, 0)
            print(f'{s[\"name\"]}  age {y[\"age\"]}: {val:.2f}')
"
```

**Expected, for each source with an `end_age`:**
- `end_age - 1`: present, full amount
- `end_age`: present, halved (transition year)
- `end_age + 1`: not present (0 or absent)

### Step 2 — API: Verify a source with a start_age begins at it

**Expected, for each source with a `start_age` after retirement:**
- `start_age - 1`: not present
- `start_age`: present, halved (transition year)
- `start_age + 1`: present, full amount

---

## TC-PROP-001: Property Expense Listing and Deletion

**Purpose:** Verify that recorded one-time expenses on a property can be listed and deleted via the API and UI.

### Step 1 — API: List existing expenses

```bash
curl -s "http://localhost/api/v1/properties/$PROP_ID/expenses" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**Expected:** Returns a JSON array with expense objects containing `id`, `date`, `amount`, `category`, `description`, `frequency`.

### Step 2 — API: Add a test expense

```bash
curl -s -X POST "http://localhost/api/v1/properties/$PROP_ID/expenses" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"date":"2025-06-01","amount":750,"category":"maintenance","description":"Test gutter repair"}' \
  -w "\nHTTP Status: %{http_code}\n"
```

**Expected:** HTTP 201 Created.

### Step 3 — API: Verify expense appears in list

```bash
curl -s "http://localhost/api/v1/properties/$PROP_ID/expenses" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import json, sys
expenses = json.load(sys.stdin)
test_exp = [e for e in expenses if e['description'] == 'Test gutter repair']
assert len(test_exp) == 1, 'FAIL: Test expense not found'
print(f'Found test expense: id={test_exp[0][\"id\"]}, amount={test_exp[0][\"amount\"]}')
print('PASS')
"
```

**Expected:** The newly added expense appears with correct fields.

### Step 4 — API: Delete the test expense

```bash
EXPENSE_ID=$(curl -s "http://localhost/api/v1/properties/$PROP_ID/expenses" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import json, sys
expenses = json.load(sys.stdin)
test = [e for e in expenses if e['description'] == 'Test gutter repair']
print(test[0]['id'] if test else '')
")

curl -s -X DELETE "http://localhost/api/v1/properties/$PROP_ID/expenses/$EXPENSE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -w "HTTP Status: %{http_code}\n"
```

**Expected:** HTTP 204 No Content.

### Step 5 — API: Verify expense is gone

```bash
curl -s "http://localhost/api/v1/properties/$PROP_ID/expenses" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import json, sys
expenses = json.load(sys.stdin)
test_exp = [e for e in expenses if e['description'] == 'Test gutter repair']
assert len(test_exp) == 0, 'FAIL: Test expense still present'
print(f'{len(expenses)} expenses remaining (test expense removed)')
print('PASS')
"
```

**Expected:** Test expense no longer appears.

### Step 6 — UI: Property detail shows expense table

1. Navigate to **Properties** page
2. Click the property used above
3. Scroll to **Recorded Expenses** section

**Expected:** Table shows existing expenses with Date, Category, Amount, Frequency, Description columns and a red Delete button on each row.

---

## TC-PROP-002: Property Edit from Detail Page

**Purpose:** Verify that a property can be edited from its detail page.

### Step 1 — UI: Open edit form

1. Navigate to **Properties** and open a property
2. Click the **Edit** button in the property card header

**Expected:** The property card is replaced by an edit form pre-populated with current values.

### Step 2 — UI: Modify and save

1. Change the `Annual Maintenance Cost` to a new value (note the original first)
2. Click **Update**

**Expected:** Form closes, property card reappears showing the updated maintenance cost.

### Step 3 — API: Verify change persisted

```bash
curl -s "http://localhost/api/v1/properties/$PROP_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.annual_maintenance_cost'
```

**Expected:** The value you entered in the UI.

### Step 4 — Cleanup: Revert the change

```bash
ORIGINAL=1600   # substitute the value you noted in Step 2

curl -s "http://localhost/api/v1/properties/$PROP_ID" -H "Authorization: Bearer $TOKEN" \
  | jq --argjson v "$ORIGINAL" '{
        address, purchase_price, purchase_date, current_value, mortgage_balance,
        property_type, annual_appreciation_rate, annual_property_tax,
        annual_insurance_cost, depreciation_method,
        annual_maintenance_cost: $v
      }' \
  | curl -s -X PUT "http://localhost/api/v1/properties/$PROP_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d @- \
  | jq '.annual_maintenance_cost'
```

**Expected:** The original value is restored.

---

## TC-SPLIT-001: Stock Splits (Admin UI + API)

**Purpose:** Verify a stock split can be entered manually, is applied to transactions/holdings/prices, is surfaced to the user, and can be un-applied.

**Prerequisite:** Log in as a **super admin** — the Stock Splits section is super-admin only.

### Step 1 — UI: Open the admin section

1. Navigate to **Admin** in the sidebar (`/admin`)
2. Click **Stock Splits** in the left sub-navigation

**Expected:** Three cards — **Sync from Finnhub** (button **Sync now**), **Add manual split** (Symbol, effective date, **Numerator** default 4, **Denominator** default 1, button **Add split**), and **Applied splits** (Symbol / Effective date / Ratio / Source / Applied / Actions). With no splits recorded the table reads "No splits have been applied yet."

### Step 2 — Record the pre-split state

```bash
ACCOUNT_ID=$(curl -s http://localhost/api/v1/accounts -H "Authorization: Bearer $TOKEN" | jq -r '.data[0].id')
curl -s "http://localhost/api/v1/accounts/$ACCOUNT_ID/holdings" \
  -H "Authorization: Bearer $TOKEN" | jq '[.[] | {symbol, quantity, cost_basis}]'
```

Note the quantity and cost basis for the symbol you will split.

### Step 3 — UI: Add a manual 4:1 split

1. In **Add manual split**, enter the symbol, an effective date, Numerator `4`, Denominator `1`
2. Click **Add split**

**Expected:** A "Split applied for {SYM}" toast; the split appears in **Applied splits** with source `Manual`.

### Step 4 — API: Verify the split was applied

```bash
curl -s http://localhost/api/v1/stock-splits -H "Authorization: Bearer $TOKEN" | jq
curl -s "http://localhost/api/v1/accounts/$ACCOUNT_ID/holdings" \
  -H "Authorization: Bearer $TOKEN" | jq '[.[] | {symbol, quantity, cost_basis}]'
```

**Expected:** Quantity for that symbol is 4x the pre-split value; total cost basis is unchanged (per-share basis is quartered). Historical prices for the symbol are back-adjusted.

### Step 5 — UI: Split is surfaced to the user

1. Go to the **Dashboard** (`/`)
2. Look at the **Recent stock splits** panel
3. Open the affected holding (`/holdings/:id`)

**Expected:** The Dashboard panel lists the split with the source label "Manually entered". The holding detail page shows a **Splits affecting {SYMBOL}** panel containing the same entry.

### Step 6 — UI: Un-apply the split

1. Back in **Admin → Stock Splits**, click **Un-apply** on the row
2. Confirm the browser prompt ("Un-apply {SYM} split on {date}? Transactions and prices will be restored to their pre-split values.")

**Expected:** A "Split unapplied" toast; holdings and prices return to the Step 2 values. Note there is **no delete** action — un-apply is the only removal path.

### Step 7 — API: Equivalent calls

```bash
# Create (admin only)
curl -s -X POST http://localhost/api/v1/admin/stock-splits \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","effective_date":"2025-06-02","numerator":4,"denominator":1}' \
  -w "\nHTTP Status: %{http_code}\n"

# Un-apply
curl -s -X DELETE "http://localhost/api/v1/admin/stock-splits/{id}" \
  -H "Authorization: Bearer $TOKEN" -w "HTTP Status: %{http_code}\n"

# Trigger the Finnhub sync manually
curl -s -X POST http://localhost/api/v1/admin/stock-splits/sync \
  -H "Authorization: Bearer $TOKEN" | jq
```

**Expected:** 201 on create, 204 on un-apply. Splits are normally detected automatically by the nightly Finnhub sync plus the one-time backfill; manual entry is the fallback.

---

## TC-CURR-001: Multi-Currency Accounts and Exchange Rates

**Purpose:** Verify a non-USD account is stored with its currency and converted into net worth at the tenant's configured rate.

### Step 1 — UI: Create a non-USD account

1. Navigate to **Accounts** (`/accounts`) and click **New Account**
2. Enter Name `Euro Savings`, Type `Bank`, Institution `N26`, and type `EUR` into the **Currency (e.g. USD, EUR)** field (3 characters, auto-uppercased)
3. Click **Create**

**Expected:** "Account created" toast; the card carries a pink `EUR` badge and its balance is formatted in euros.

### Step 2 — UI: Confirm the empty exchange-rate state

1. Navigate to **Admin** (`/admin`) → **Exchange Rates**

**Expected:** "No exchange rates configured. All accounts use USD."

### Step 3 — UI: Add a rate

1. Click **Add Currency**
2. In the **Add Exchange Rate** card enter **Currency Code** `EUR` and `1.08` in the **1 EUR = ? USD** field
3. Click **Save**

**Expected:** The table shows `1 EUR = 1.08 USD` with Currency / Rate to USD / Last Updated columns and **Edit** / **Delete** row actions.

### Step 4 — API: Verify and cross-check

```bash
curl -s http://localhost/api/v1/exchange-rates -H "Authorization: Bearer $TOKEN" | jq
curl -s -X POST http://localhost/api/v1/exchange-rates \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currency_code":"GBP","rate_to_usd":1.27}' -w "\nHTTP Status: %{http_code}\n"
curl -s -X POST http://localhost/api/v1/exchange-rates \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currency_code":"eur","rate_to_usd":1.08}' -w "\nHTTP Status: %{http_code}\n"
```

**Expected:** The list contains `EUR`. `GBP` is accepted. The lowercase `eur` is rejected with **400** — `currency_code` is validated against `[A-Z]{3}`.

### Step 5 — UI: Dashboard conversion

1. Add a transaction or balance to `Euro Savings`, then go to the **Dashboard** (`/`)

**Expected:** Net worth includes the euro balance converted at 1.08.

### Step 6 — UI: Delete guard

1. In **Admin → Exchange Rates**, click **Delete** on the `EUR` row and confirm

**Expected:** The delete is rejected with a toast noting that accounts may still use this currency. Delete `GBP` (unused) instead and verify it succeeds.

---

## TC-EXPORT-001: Data Export

**Purpose:** Verify the full JSON export and the four CSV exports.

### Step 1 — UI

1. Navigate to **Export** in the sidebar (`/export`)

**Expected:** Two cards — **Full Export (JSON)** with a single **Download JSON** button, and **CSV Export** with four buttons: **Accounts**, **Transactions**, **Holdings**, **Properties**. There is no date-range filter and no XLSX/PDF option.

### Step 2 — UI: Download each

1. Click **Download JSON**, then each of the four CSV buttons

**Expected:** Each button shows "Downloading..." while in flight and produces a "{type} export downloaded" toast. Files download successfully.

### Step 3 — API

```bash
curl -s http://localhost/api/v1/export/json -H "Authorization: Bearer $TOKEN" | jq 'keys'
for t in accounts transactions holdings properties; do
  echo "--- $t"
  curl -s "http://localhost/api/v1/export/csv/$t" -H "Authorization: Bearer $TOKEN" | head -2
done
```

**Expected:** The JSON export contains the tenant's accounts, transactions, holdings, properties and projections. Each CSV starts with a header row.

---

## TC-PROJ-003: Household / Spouse Scenario Inputs

**Purpose:** Verify the spouse fields on the scenario form drive owner-aware projection behaviour.

### Step 1 — UI: Reveal the household fields

1. Navigate to **Projections** (`/projections`) and click **New Scenario** (or **Edit** on an existing one)
2. Scroll to the **Spouse / Household** section
3. Enter a **Spouse Birth Year**

**Expected:** With the field blank the section shows only that one input (single-person household). Once a birth year is entered, these appear: **Primary Death Age**, **Spouse Death Age**, **Survivor Spending Factor (%)** (default 75), **Community Property State** checkbox, **Model Uncertain Lifespans** checkbox.

### Step 2 — UI: Per-account owner

1. Scroll to the accounts section

**Expected:** Each account card now shows an **Owner** select with Primary / Spouse / Joint, where **Joint is disabled unless the account type is Taxable**. This select is hidden entirely when no spouse birth year is set.

### Step 3 — UI: Stochastic mortality toggle

1. Tick **Model Uncertain Lifespans**

**Expected:** **Primary Sex** and **Spouse Sex** selects appear (options: Blended (unset), Male, Female) along with **Longevity Age** (default 95). The help text states this affects the guardrail optimizer's Monte Carlo only, not the deterministic projection.

### Step 4 — UI: Spouse income

1. Go to **Income Sources** (`/income-sources`) and create a `Social Security` source with **Owner:** `Spouse`
2. Tick **Survivor Benefit** and set **Survivor % (%)**

**Expected:** Spouse Social Security is modelled here, not on the scenario form. Link it to the scenario via the **Income Sources** checkboxes on the scenario form.

### Step 5 — Run and inspect

1. Save & Re-run, then open the **Data Table**

**Expected:** After the modelled first death the survivor's spending is scaled by the survivor factor, the filing status flips from married-filing-jointly to single, and per-owner RMD streams are reflected.

---

## TC-PROJ-004: Allocation Editor and Cost Basis

**Purpose:** Verify the per-account asset allocation editor and its 100% validation, plus the cost-basis input.

### Step 1 — UI: Default derived allocation

1. Edit a scenario and look at an account card's **Allocation** field

**Expected:** Text of the form "Derived from holdings (60.0% US / 20.0% Intl / 15.0% Bond / 5.0% Cash)" plus a **Customize allocation** button.

### Step 2 — UI: Customize

1. Click **Customize allocation**

**Expected:** Four inputs — **US Stocks (%)**, **Intl Stocks (%)**, **Bonds (%)**, **Cash (%)** — a running `Total: n%`, and a **Reset to derived** button.

### Step 3 — UI: 100% validation

1. Set values totalling something other than 100 (e.g. 50/20/20/5) and click **Save & Re-run**

**Expected:** The total renders in red with "— must sum to 100%", and the save is blocked with the message "One or more account allocations must sum to 100% before saving."

### Step 4 — UI: Cost basis

1. Look at the **Cost Basis** field on the same account card

**Expected:** For a **Manual Entry** account it is an editable currency input with the help text "Total dollars invested, used for capital-gains tax calculations." For an account **linked** to a real WealthView account it is read-only grey text showing either the derived amount or "Available after first run", with the help text "Derived from the linked account's holdings."

### Step 5 — UI: Cost basis on real holdings

1. Open a holding (`/holdings/:id`)

**Expected:** The **Holding Summary** card exposes an inline-editable **Cost Basis**. The Holdings table on `/accounts/:id` also has an editable Cost Basis column.

### Step 6 — UI: Reclassify unclassified symbols

1. Run a projection whose linked accounts contain an unclassified symbol

**Expected:** An orange notice reads "These holdings were modeled as US Stock because we couldn't classify them:" with a per-symbol select (US Stock / Intl Stock / Bonds / Cash) and an **Apply & re-run** button, disabled until at least one symbol is chosen. This is the only asset-class override in the UI; an already-classified symbol cannot be changed from the web app.

---

## TC-ADMIN-001: Consolidated Admin Area and Legacy Redirects

**Purpose:** Verify the `/admin` area and that the three retired routes redirect into it.

### Step 1 — UI: Redirects

Navigate directly to each of these and observe the resulting URL:

| Old path | Expected |
|---|---|
| `/settings` | replaced by `/admin` |
| `/audit-log` | replaced by `/admin` |
| `/admin/prices` | replaced by `/admin` |

All three use `<Navigate replace>`, so the browser back button skips them.

### Step 2 — UI: Sub-navigation by role

1. Log in as a **tenant admin** and open `/admin`
2. Log in as a **super admin** and open `/admin`

**Expected sections:**

| Section | Visible to |
|---|---|
| Dashboard | super admin only (default section for them) |
| Users | all admins (default section for a tenant admin) |
| Tenants | super admin only |
| Prices | all admins |
| Stock Splits | super admin only |
| Exchange Rates | all admins |
| Invite Codes | all admins |
| System Config | super admin only |
| Audit Log | all admins |

The **Admin** sidebar link is hidden entirely for `member` and `viewer` roles.

### Step 3 — UI: Audit Log

1. Click **Audit Log**

**Expected:** Time / Action / Entity Type / Details columns, a filter dropdown defaulting to **All**, and the empty message "No audit log entries".

### Step 4 — UI: Prices

1. Click **Prices**

**Expected:** Tabs **Finnhub Sync**, **Yahoo Finance**, **CSV Upload**, **Browse**. Finnhub Sync has a **Price Sync Status** table (Symbol / Latest Date / Source / Status) and **Sync All Holdings**. Yahoo Finance has **Fetch Preview**, **Save All**, **Sync All Holdings from Yahoo**. CSV Upload has an **Upload Price CSV** form. Browse has a symbol **Search**.

### Step 5 — UI: Invite Codes

1. Click **Invite Codes**, choose an expiry (1 day / 7 days / 30 days / 90 days) and click **Generate Code**
2. Use **Copy**, register a new user at `/register` with the code, then return

**Expected:** Table columns Code / Created By / Created / Expires / Status / Used By / Actions. After registration the code shows as used. **Revoke** on an unused code flips its status to `Revoked`.

### Step 6 — UI: Users

1. Click **Users**

**Expected:** Email / Role / Tenant / Joined / Status / Actions with an inline role select — Super Admin / Admin / Member / Viewer for a super admin, the same minus Super Admin for a tenant admin. Row actions: **Reset PW** (opens a **Reset Password** modal), **Deactivate** / **Activate**, and **Delete** (super admin) or **Remove** (tenant admin). There is deliberately no "create user" button; users arrive via invite codes.

### Step 7 — UI: Dashboard, Tenants, System Config (super admin)

**Expected:**
- **Dashboard** — stat cards Total Users, Active Users (30d), Tenants, Accounts, Holdings, Transactions, Database Size, Symbols Tracked, Stale Symbols; **Recent Login Activity** table (Email / Time / IP Address / Status)
- **Tenants** — **Create Tenant** form plus a Name / Users / Accounts / Status / Created table
- **System Config** — grouped cards **API Keys**, **Application Settings**, **Price Sync**, **Other**; per-row **Edit** → **Save** / **Cancel**, **Show** / **Hide** for secrets, and a toggle for boolean keys

---

## TC-AUTH-001: MFA and Session Management (API only — no web UI)

**Purpose:** Exercise the MFA and session endpoints. **These have no web UI.** There is no MFA setup screen, no QR code, no recovery-code screen and no active-sessions list anywhere in `frontend/src`, so there is nothing to test through the browser. Verify them at the API level only, and do not write UI steps for them.

### Step 1 — API: MFA status and setup

```bash
curl -s http://localhost/api/v1/auth/mfa/status -H "Authorization: Bearer $TOKEN" | jq
curl -s -X POST http://localhost/api/v1/auth/mfa/setup -H "Authorization: Bearer $TOKEN" | jq
```

**Expected:** `status` reports whether MFA is enabled for the current user. `setup` returns the enrolment payload (shared secret / provisioning data) without persisting an enabled state — `POST /api/v1/auth/mfa/verify-setup` with a valid TOTP code is what enables it. `POST /disable` and `POST /regenerate-recovery-codes` complete the set.

### Step 2 — API: Login with MFA enabled

**Expected:** `POST /api/v1/auth/login` (or `/api/v1/auth/token/login`) returns an MFA-required response instead of tokens; the caller then posts the code to `/api/v1/auth/mfa/challenge` (web) or `/api/v1/auth/token/mfa/challenge` (bearer) to obtain the session.

### Step 3 — API: Sessions

```bash
curl -s http://localhost/api/v1/auth/sessions -H "Authorization: Bearer $TOKEN" | jq
curl -s -X DELETE "http://localhost/api/v1/auth/sessions/{id}" \
  -H "Authorization: Bearer $TOKEN" -w "HTTP Status: %{http_code}\n"
curl -s -X DELETE http://localhost/api/v1/auth/sessions \
  -H "Authorization: Bearer $TOKEN" -w "HTTP Status: %{http_code}\n"
```

**Expected:** The list returns the current user's sessions. `DELETE /{id}` revokes one; the bare `DELETE` revokes all *other* sessions, leaving the caller's own intact. Both return 204.

---

## Running All API Tests

To run the data-independent API invariants in a single pass:

```bash
export TOKEN=$(curl -s http://localhost/api/v1/auth/token/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@wealthview.local","password":"demo123"}' \
  | jq -r '.access_token')

SCENARIO_ID=$(curl -s http://localhost/api/v1/projections \
  -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')

curl -s "http://localhost/api/v1/projections/$SCENARIO_ID/run" \
  -H "Authorization: Bearer $TOKEN" > /tmp/projection_result.json
curl -s http://localhost/api/v1/income-sources \
  -H "Authorization: Bearer $TOKEN" > /tmp/sources.json

python3 -c "
import json, sys

proj = json.load(open('/tmp/projection_result.json'))
srcs = json.load(open('/tmp/sources.json'))

passed = failed = 0

def check(name, ok, detail=''):
    global passed, failed
    print(('PASS  ' if ok else 'FAIL  ') + name + ('  ' + detail if detail else ''))
    if ok: passed += 1
    else:  failed += 1

# TC-PROJ-001.1: income_by_source present on retired years
retired_ibs = [y for y in proj['yearly_data'] if y['retired'] and y.get('income_by_source')]
check('TC-PROJ-001.1  income_by_source present on retired years',
      len(retired_ibs) > 0, f'({len(retired_ibs)} years)')

# TC-PROJ-001.3: sum(income_by_source) == income_streams_total
mismatches = [y['year'] for y in proj['yearly_data']
              if y.get('income_by_source')
              and abs(sum(y['income_by_source'].values()) - y['income_streams_total']) > 0.01]
check('TC-PROJ-001.3  sum(income_by_source) == income_streams_total',
      not mismatches, f'({len(mismatches)} mismatches)')

# TC-PROJ-002: end_age boundary — source absent the year after end_age
for s in srcs:
    if s.get('end_age') is None:
        continue
    sid, ea = s['id'], s['end_age']
    after = [y for y in proj['yearly_data'] if y['age'] == ea + 1]
    if not after:
        continue
    present = sid in (after[0].get('income_by_source') or {})
    check(f'TC-PROJ-002  {s[\"name\"]} inactive at age {ea + 1}', not present)

# TC-PROJ-002: start_age boundary — source absent the year before start_age
for s in srcs:
    sa = s.get('start_age')
    if sa is None:
        continue
    before = [y for y in proj['yearly_data'] if y['age'] == sa - 1 and y['retired']]
    if not before:
        continue
    present = s['id'] in (before[0].get('income_by_source') or {})
    check(f'TC-PROJ-002  {s[\"name\"]} inactive at age {sa - 1}', not present)

print(f'\\n{passed} passed, {failed} failed out of {passed + failed} checks')
sys.exit(1 if failed else 0)
"
```

These checks are deliberately data-independent — they assert invariants (totals reconcile, age
boundaries hold) rather than specific dollar figures, so they keep working as the demo data changes.
</content>
