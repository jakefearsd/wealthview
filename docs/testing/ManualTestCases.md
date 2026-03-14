# Manual Test Cases

## Prerequisites

- App running via `docker compose up --build -d`
- URL: http://localhost:80
- Login: `demo@wealthview.local` / `demo123`

All API tests below use this auth pattern:

```bash
export TOKEN=$(curl -s http://localhost/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@wealthview.local","password":"demo123"}' \
  | jq -r '.access_token')
```

---

## TC-PROJ-001: Projection Income Streams Show NET (Not GROSS) Rental Income

**Purpose:** Verify that the Income Streams chart and all projection data use net rental income (gross minus property expenses) rather than the gross annual amount.

**Background:** Rental income sources are linked to properties that have annual expenses (property tax, insurance, maintenance, mortgage interest). The projection engine must deduct these before reporting income.

**Setup:**
- Demo data has "AirBnB" income source (ID `58c32112`) linked to Beryl Street property
  - Gross annual amount: $90,000
  - Property expenses: tax=$14,000 + insurance=$3,000 + maintenance=$2,500 + mortgage interest
- Demo data has "Switch to Escadera" income source (ID `77d5bb33`) linked to Escadera property
  - Gross annual amount: $38,000
  - Property expenses: tax=$5,200 + insurance=$1,500 + maintenance=$1,600 (no mortgage)

### Step 1 — API: Run projection and verify income_by_source field exists

```bash
SCENARIO_ID="80344744-e518-411b-acaf-421cef3c815d"
curl -s "http://localhost/api/v1/projections/$SCENARIO_ID/run" \
  -H "Authorization: Bearer $TOKEN" > /tmp/projection_result.json

# Check that income_by_source is present on retired years
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

```bash
python3 -c "
import json
with open('/tmp/projection_result.json') as f:
    data = json.load(f)

escadera_id = '77d5bb33-14fd-4eea-9e6d-24fab4d2b5fd'
# Find a full year (not transition) where Escadera is active — age 66
y66 = [y for y in data['yearly_data'] if y['age'] == 66][0]
escadera_net = y66['income_by_source'][escadera_id]

# Escadera: base=38000, inflation=2%, retirement year 2027
# At age 66 (year 2039), yearsInRetirement=13
# nominal = 38000 * 1.02^12 = 48193.1882
# expenses = insurance(1500) + maintenance(1600) + tax(5200) = 8300
# NET = 48193.1882 - 8300 = 39893.1882
expected = 38000 * (1.02 ** 12) - 8300
assert abs(escadera_net - expected) < 0.01, f'FAIL: expected {expected:.4f}, got {escadera_net}'
print(f'Escadera NET at age 66: {escadera_net} (expected {expected:.4f})')
print('PASS')
"
```

**Expected:** Escadera NET at age 66 equals approximately $39,893.19.

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

**Expected:** Every retired year's `income_streams_total` exactly equals the sum of all values in its `income_by_source` map.

### Step 4 — UI: Income Streams chart shows NET amounts

1. Navigate to http://localhost:80 and log in
2. Go to **Projections** page
3. Click **Simple Retirement Projection**
4. Click the **Income Streams** tab
5. Hover over a bar for AirBnB at age 57 (a full year, not transition)

**Expected:** The tooltip value should be approximately $54,293 (NET), NOT $95,509 (GROSS). Compare with the API output from Step 2 to confirm the chart matches.

### Step 5 — UI: Data Table Income column shows NET

1. On the same projection detail page, click the **Data Table** tab
2. Scroll to a retired year where rental income is active (e.g., age 57)
3. Check the "Income" column value

**Expected:** The Income column should match the `income_streams_total` from the API (NET, not GROSS).

---

## TC-PROJ-002: Income Source Age Boundaries in Projections

**Purpose:** Verify that income sources activate/deactivate at the correct ages and apply transition-year halving.

### Step 1 — API: Verify AirBnB stops at end_age=64

```bash
python3 -c "
import json
with open('/tmp/projection_result.json') as f:
    data = json.load(f)

airbnb_id = '58c32112-42fc-466c-aba8-883214c791bf'
for y in data['yearly_data']:
    if y['age'] in [63, 64, 65] and y['retired']:
        has = y.get('income_by_source', {}) and airbnb_id in (y.get('income_by_source') or {})
        val = (y.get('income_by_source') or {}).get(airbnb_id, 0)
        print(f'Age {y[\"age\"]}: AirBnB present={has}, value={val:.2f}')

# Age 63: full year, present
# Age 64: transition (end) year, present but halved
# Age 65: not present
"
```

**Expected:**
- Age 63: present, full amount
- Age 64: present, halved (transition year)
- Age 65: not present (0 or absent)

### Step 2 — API: Verify Social Security starts at start_age=70

```bash
python3 -c "
import json
with open('/tmp/projection_result.json') as f:
    data = json.load(f)

ss_id = 'f14e270a-61b0-4348-b2ed-1da52f936e9f'
for y in data['yearly_data']:
    if y['age'] in [69, 70, 71] and y['retired']:
        has = y.get('income_by_source', {}) and ss_id in (y.get('income_by_source') or {})
        val = (y.get('income_by_source') or {}).get(ss_id, 0)
        print(f'Age {y[\"age\"]}: SS present={has}, value={val:.2f}')
"
```

**Expected:**
- Age 69: not present
- Age 70: present, halved (transition year)
- Age 71: present, full amount

---

## TC-PROP-001: Property Expense Listing and Deletion

**Purpose:** Verify that recorded one-time expenses on a property can be listed and deleted via the API and UI.

### Step 1 — API: List existing expenses

```bash
PROP_ID="25dabac4-d291-4afa-bebf-83dd35dad713"
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
# Get the ID first
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
2. Click **Escadera Drive** property
3. Scroll to **Recorded Expenses** section

**Expected:** Table shows existing expenses with Date, Category, Amount, Frequency, Description columns and a red Delete button on each row.

---

## TC-PROP-002: Property Edit from Detail Page

**Purpose:** Verify that a property can be edited from its detail page.

### Step 1 — UI: Open edit form

1. Navigate to **Properties** > **Escadera Drive**
2. Click the **Edit** button in the property card header

**Expected:** The property card is replaced by an edit form pre-populated with current values.

### Step 2 — UI: Modify and save

1. Change the `Annual Maintenance Cost` from $1,600 to $2,000
2. Click **Update**

**Expected:** Form closes, property card reappears showing the updated maintenance cost.

### Step 3 — API: Verify change persisted

```bash
PROP_ID="25dabac4-d291-4afa-bebf-83dd35dad713"
curl -s "http://localhost/api/v1/properties/$PROP_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import json, sys
p = json.load(sys.stdin)
print(f'annual_maintenance_cost: {p[\"annual_maintenance_cost\"]}')
assert p['annual_maintenance_cost'] == 2000.0, f'FAIL: expected 2000, got {p[\"annual_maintenance_cost\"]}'
print('PASS')
"
```

**Expected:** `annual_maintenance_cost` is 2000.

### Step 4 — Cleanup: Revert the change

```bash
# Re-read the full property, change maintenance back to 1600, PUT it
curl -s "http://localhost/api/v1/properties/$PROP_ID" \
  -H "Authorization: Bearer $TOKEN" > /tmp/prop.json

python3 -c "
import json
with open('/tmp/prop.json') as f:
    p = json.load(f)
# Build update request
req = {
    'address': p['address'],
    'purchase_price': p['purchase_price'],
    'purchase_date': p['purchase_date'],
    'current_value': p['current_value'],
    'mortgage_balance': p['mortgage_balance'],
    'property_type': p['property_type'],
    'annual_appreciation_rate': p['annual_appreciation_rate'],
    'annual_property_tax': p['annual_property_tax'],
    'annual_insurance_cost': p['annual_insurance_cost'],
    'annual_maintenance_cost': 1600,
    'depreciation_method': p['depreciation_method'],
}
print(json.dumps(req))
" | curl -s -X PUT "http://localhost/api/v1/properties/$PROP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @- | python3 -c "
import json, sys
p = json.load(sys.stdin)
print(f'Reverted: annual_maintenance_cost={p[\"annual_maintenance_cost\"]}')
"
```

---

## Running All API Tests

To run all API-verifiable tests in a single pass:

```bash
export TOKEN=$(curl -s http://localhost/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@wealthview.local","password":"demo123"}' \
  | jq -r '.access_token')

SCENARIO_ID="80344744-e518-411b-acaf-421cef3c815d"
curl -s "http://localhost/api/v1/projections/$SCENARIO_ID/run" \
  -H "Authorization: Bearer $TOKEN" > /tmp/projection_result.json

python3 -c "
import json, sys

with open('/tmp/projection_result.json') as f:
    data = json.load(f)

passed = 0
failed = 0

# TC-PROJ-001 Step 1: income_by_source exists
retired_ibs = [y for y in data['yearly_data'] if y['retired'] and y.get('income_by_source')]
if len(retired_ibs) > 0:
    print('PASS  TC-PROJ-001.1  income_by_source present on retired years')
    passed += 1
else:
    print('FAIL  TC-PROJ-001.1  no retired years have income_by_source')
    failed += 1

# TC-PROJ-001 Step 2: Escadera NET = GROSS - expenses
escadera_id = '77d5bb33-14fd-4eea-9e6d-24fab4d2b5fd'
y66 = [y for y in data['yearly_data'] if y['age'] == 66][0]
expected = 38000 * (1.02 ** 12) - 8300
actual = y66['income_by_source'][escadera_id]
if abs(actual - expected) < 0.01:
    print(f'PASS  TC-PROJ-001.2  Escadera NET={actual:.2f} matches expected {expected:.2f}')
    passed += 1
else:
    print(f'FAIL  TC-PROJ-001.2  Escadera NET={actual:.2f}, expected {expected:.2f}')
    failed += 1

# TC-PROJ-001 Step 3: sum(income_by_source) == income_streams_total
mismatches = 0
for y in data['yearly_data']:
    if y.get('income_by_source'):
        s = sum(y['income_by_source'].values())
        if abs(s - y['income_streams_total']) > 0.01:
            mismatches += 1
if mismatches == 0:
    print('PASS  TC-PROJ-001.3  sum(income_by_source) == income_streams_total for all years')
    passed += 1
else:
    print(f'FAIL  TC-PROJ-001.3  {mismatches} mismatches')
    failed += 1

# TC-PROJ-002 Step 1: AirBnB stops at end_age=64
airbnb_id = '58c32112-42fc-466c-aba8-883214c791bf'
y63 = [y for y in data['yearly_data'] if y['age'] == 63][0]
y65 = [y for y in data['yearly_data'] if y['age'] == 65][0]
a63 = airbnb_id in (y63.get('income_by_source') or {})
a65 = airbnb_id in (y65.get('income_by_source') or {})
if a63 and not a65:
    print('PASS  TC-PROJ-002.1  AirBnB active at 63, inactive at 65')
    passed += 1
else:
    print(f'FAIL  TC-PROJ-002.1  at63={a63}, at65={a65}')
    failed += 1

# TC-PROJ-002 Step 2: SS starts at start_age=70
ss_id = 'f14e270a-61b0-4348-b2ed-1da52f936e9f'
y69 = [y for y in data['yearly_data'] if y['age'] == 69][0]
y70 = [y for y in data['yearly_data'] if y['age'] == 70][0]
s69 = ss_id in (y69.get('income_by_source') or {})
s70 = ss_id in (y70.get('income_by_source') or {})
if not s69 and s70:
    print('PASS  TC-PROJ-002.2  SS inactive at 69, active at 70')
    passed += 1
else:
    print(f'FAIL  TC-PROJ-002.2  at69={s69}, at70={s70}')
    failed += 1

print(f'\\n{passed} passed, {failed} failed out of {passed+failed} checks')
sys.exit(1 if failed else 0)
"
```
