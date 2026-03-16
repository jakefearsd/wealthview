#!/bin/bash
# Manual test script for per-phase target allocation + diagnostics
set -euo pipefail

BASE="http://localhost:80/api/v1"

echo "=== 1. Login ==="
LOGIN_RESP=$(curl -s -H "Content-Type: application/json" \
  -d '{"email":"demo@wealthview.local","password":"demo123"}' \
  "$BASE/auth/login")
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
AUTH="Authorization: Bearer $TOKEN"
echo "Login OK"

echo ""
echo "=== 2. List scenarios ==="
SCENARIOS=$(curl -s -H "$AUTH" "$BASE/projections")
SCENARIO_ID=$(echo "$SCENARIOS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'])")
SCENARIO_NAME=$(echo "$SCENARIOS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['name'])")
echo "Using scenario: $SCENARIO_ID ($SCENARIO_NAME)"

# Get scenario detail to find retirement age
SCENARIO_DETAIL=$(curl -s -H "$AUTH" "$BASE/projections/$SCENARIO_ID")
RET_DATE=$(echo "$SCENARIO_DETAIL" | python3 -c "import json,sys; print(json.load(sys.stdin)['retirement_date'])")
END_AGE=$(echo "$SCENARIO_DETAIL" | python3 -c "import json,sys; print(json.load(sys.stdin)['end_age'])")
BIRTH_YEAR=$(echo "$SCENARIO_DETAIL" | python3 -c "import json,sys; d=json.load(sys.stdin); p=json.loads(d['params_json']); print(p['birth_year'])")
RET_YEAR=$(echo "$RET_DATE" | cut -d- -f1)
RET_AGE=$((RET_YEAR - BIRTH_YEAR))
echo "Retirement age: $RET_AGE, end age: $END_AGE, birth year: $BIRTH_YEAR"

# Helper function
api_optimize() {
  curl -s -H "$AUTH" -H "Content-Type: application/json" -d "$1" "$BASE/projections/$SCENARIO_ID/optimize"
}

MID_AGE=$(( (RET_AGE + END_AGE) / 2 ))
LATE_AGE=$(( MID_AGE + (END_AGE - MID_AGE) / 2 ))

echo ""
echo "=== 3. Test A: Cheap phase should NOT be dragged down by expensive phase ==="
echo "   Phases: Early=\$90k (ages $RET_AGE-$((MID_AGE-1))), Mid=\$240k, Late=\$160k, Final=\$300k"
RESULT_A=$(api_optimize "{
  \"scenario_id\": \"$SCENARIO_ID\",
  \"name\": \"Test A\",
  \"essential_floor\": 30000,
  \"terminal_balance_target\": 0,
  \"return_mean\": 0.10,
  \"return_stddev\": 0.15,
  \"trial_count\": 5000,
  \"confidence_level\": 0.80,
  \"phases\": [
    {\"name\":\"Early\",\"start_age\":$RET_AGE,\"end_age\":$((MID_AGE-1)),\"priority_weight\":1,\"target_spending\":90000},
    {\"name\":\"Mid\",\"start_age\":$MID_AGE,\"end_age\":$((LATE_AGE-1)),\"priority_weight\":1,\"target_spending\":240000},
    {\"name\":\"Late\",\"start_age\":$LATE_AGE,\"end_age\":$((END_AGE-5)),\"priority_weight\":1,\"target_spending\":160000},
    {\"name\":\"Final\",\"start_age\":$((END_AGE-4)),\"end_age\":null,\"priority_weight\":1,\"target_spending\":300000}
  ],
  \"portfolio_floor\": 0,
  \"max_annual_adjustment_rate\": 0.05,
  \"phase_blend_years\": 1,
  \"risk_tolerance\": \"moderate\"
}")

echo "$RESULT_A" | python3 -c "
import json, sys
data = json.load(sys.stdin)
print(f'   Failure rate: {data[\"failure_rate\"]*100:.1f}%')
print()
print('   Per-phase avg recommended spending:')
phases = {}
for y in data['yearly_spending']:
    p = y['phase_name']
    phases.setdefault(p, []).append(y['recommended'])
for name, vals in phases.items():
    avg = sum(vals)/len(vals)
    print(f'   {name}: avg=\${avg:,.0f} ({len(vals)} years)')

early_avg = sum(phases.get('Early', [0]))/max(len(phases.get('Early', [0])),1)
print()
print(f'   KEY CHECK: Early phase avg (\${early_avg:,.0f}) should be close to \$90k target')
if early_avg > 70000:
    print('   Result: PASS (>\$70k threshold)')
else:
    print(f'   Result: FAIL (only \${early_avg:,.0f}, expected >\$70k)')
"

echo ""
echo "=== 4. Test B: Cap at target — should not exceed \$60k ==="
RESULT_B=$(api_optimize "{
  \"scenario_id\": \"$SCENARIO_ID\",
  \"name\": \"Test B\",
  \"essential_floor\": 20000,
  \"terminal_balance_target\": 0,
  \"return_mean\": 0.10,
  \"return_stddev\": 0.15,
  \"trial_count\": 5000,
  \"confidence_level\": 0.80,
  \"phases\": [
    {\"name\":\"All\",\"start_age\":$RET_AGE,\"end_age\":null,\"priority_weight\":1,\"target_spending\":60000}
  ],
  \"portfolio_floor\": 0,
  \"max_annual_adjustment_rate\": 0.05,
  \"phase_blend_years\": 0,
  \"risk_tolerance\": \"moderate\"
}")

echo "$RESULT_B" | python3 -c "
import json, sys
data = json.load(sys.stdin)
vals = [y['recommended'] for y in data['yearly_spending']]
avg = sum(vals)/len(vals)
mx = max(vals)
print(f'   Avg recommended: \${avg:,.0f} (target: \$60k)')
print(f'   Max recommended: \${mx:,.0f}')
print(f'   Result: {\"PASS\" if avg < 70000 else \"FAIL\"} (avg should be < \$70k)')
"

echo ""
echo "=== 5. Test C: All phases affordable — each gets its target ==="
RESULT_C=$(api_optimize "{
  \"scenario_id\": \"$SCENARIO_ID\",
  \"name\": \"Test C\",
  \"essential_floor\": 20000,
  \"terminal_balance_target\": 0,
  \"return_mean\": 0.10,
  \"return_stddev\": 0.15,
  \"trial_count\": 5000,
  \"confidence_level\": 0.80,
  \"phases\": [
    {\"name\":\"Early\",\"start_age\":$RET_AGE,\"end_age\":$((MID_AGE-1)),\"priority_weight\":1,\"target_spending\":50000},
    {\"name\":\"Late\",\"start_age\":$MID_AGE,\"end_age\":null,\"priority_weight\":1,\"target_spending\":40000}
  ],
  \"portfolio_floor\": 0,
  \"max_annual_adjustment_rate\": 0.05,
  \"phase_blend_years\": 0,
  \"risk_tolerance\": \"moderate\"
}")

echo "$RESULT_C" | python3 -c "
import json, sys
data = json.load(sys.stdin)
phases = {}
for y in data['yearly_spending']:
    phases.setdefault(y['phase_name'], []).append(y['recommended'])
for name, vals in phases.items():
    avg = sum(vals)/len(vals)
    target = 50000 if name == 'Early' else 40000
    pct = avg/target*100
    ok = 'PASS' if 90 < pct < 110 else 'FAIL'
    print(f'   {name}: avg=\${avg:,.0f} target=\${target:,} ({pct:.0f}%) {ok}')
"

echo ""
echo "=== 6. Test D: Phase order affects allocation ==="
RESULT_D1=$(api_optimize "{
  \"scenario_id\": \"$SCENARIO_ID\",
  \"name\": \"Test D1\",
  \"essential_floor\": 20000,
  \"terminal_balance_target\": 50000,
  \"return_mean\": 0.10,
  \"return_stddev\": 0.15,
  \"trial_count\": 5000,
  \"confidence_level\": 0.80,
  \"phases\": [
    {\"name\":\"A\",\"start_age\":$RET_AGE,\"end_age\":$((MID_AGE-1)),\"priority_weight\":1,\"target_spending\":120000},
    {\"name\":\"B\",\"start_age\":$MID_AGE,\"end_age\":null,\"priority_weight\":1,\"target_spending\":120000}
  ],
  \"portfolio_floor\": 100000,
  \"max_annual_adjustment_rate\": 0,
  \"phase_blend_years\": 0,
  \"risk_tolerance\": \"moderate\"
}")

RESULT_D2=$(api_optimize "{
  \"scenario_id\": \"$SCENARIO_ID\",
  \"name\": \"Test D2\",
  \"essential_floor\": 20000,
  \"terminal_balance_target\": 50000,
  \"return_mean\": 0.10,
  \"return_stddev\": 0.15,
  \"trial_count\": 5000,
  \"confidence_level\": 0.80,
  \"phases\": [
    {\"name\":\"B\",\"start_age\":$MID_AGE,\"end_age\":null,\"priority_weight\":1,\"target_spending\":120000},
    {\"name\":\"A\",\"start_age\":$RET_AGE,\"end_age\":$((MID_AGE-1)),\"priority_weight\":1,\"target_spending\":120000}
  ],
  \"portfolio_floor\": 100000,
  \"max_annual_adjustment_rate\": 0,
  \"phase_blend_years\": 0,
  \"risk_tolerance\": \"moderate\"
}")

python3 -c "
import json, sys
d1 = json.loads('''$(echo "$RESULT_D1")''')
d2 = json.loads('''$(echo "$RESULT_D2")''')
a_first = sum(y['recommended'] for y in d1['yearly_spending'] if y['phase_name']=='A') / max(1,sum(1 for y in d1['yearly_spending'] if y['phase_name']=='A'))
a_second = sum(y['recommended'] for y in d2['yearly_spending'] if y['phase_name']=='A') / max(1,sum(1 for y in d2['yearly_spending'] if y['phase_name']=='A'))
print(f'   Phase A avg when listed first: \${a_first:,.0f}')
print(f'   Phase A avg when listed second: \${a_second:,.0f}')
print(f'   Result: {\"PASS\" if a_first >= a_second - 1 else \"FAIL\"} (first >= second)')
"

echo ""
echo "=== 7. Test E: Legacy priority fallback (no targets) ==="
RESULT_E=$(api_optimize "{
  \"scenario_id\": \"$SCENARIO_ID\",
  \"name\": \"Test E\",
  \"essential_floor\": 20000,
  \"terminal_balance_target\": 100000,
  \"return_mean\": 0.10,
  \"return_stddev\": 0.15,
  \"trial_count\": 5000,
  \"confidence_level\": 0.80,
  \"phases\": [
    {\"name\":\"High\",\"start_age\":$RET_AGE,\"end_age\":$((MID_AGE-1)),\"priority_weight\":3},
    {\"name\":\"Low\",\"start_age\":$MID_AGE,\"end_age\":null,\"priority_weight\":1}
  ],
  \"portfolio_floor\": 0,
  \"max_annual_adjustment_rate\": 0.05,
  \"phase_blend_years\": 0,
  \"risk_tolerance\": \"moderate\"
}")

echo "$RESULT_E" | python3 -c "
import json, sys
data = json.load(sys.stdin)
phases = {}
for y in data['yearly_spending']:
    phases.setdefault(y['phase_name'], []).append(y['discretionary'])
for name in ['High', 'Low']:
    vals = phases.get(name, [0])
    print(f'   {name} priority avg disc: \${sum(vals)/len(vals):,.0f}')
h = sum(phases.get('High',[0]))/len(phases.get('High',[1]))
l = sum(phases.get('Low',[0]))/len(phases.get('Low',[1]))
print(f'   Result: {\"PASS\" if h > l else \"FAIL\"} (high > low)')
"

echo ""
echo "=== 8. Test F: Response structure for frontend diagnostics ==="
echo "$RESULT_A" | python3 -c "
import json, sys
data = json.load(sys.stdin)
y = data['yearly_spending'][0]
required = ['recommended','corridor_low','corridor_high','essential_floor','discretionary',
            'income_offset','portfolio_withdrawal','phase_name','portfolio_balance_median']
missing = [f for f in required if f not in y or y[f] is None]
if missing:
    print(f'   FAIL: Missing fields: {missing}')
else:
    print('   PASS: All yearly_spending fields present')

# Check phases have target_spending
targets = [p.get('target_spending') for p in data['phases']]
nt = sum(1 for t in targets if t is not None)
print(f'   Phases with target_spending: {nt}/{len(data[\"phases\"])}')
print(f'   Result: {\"PASS\" if nt == len(data[\"phases\"]) else \"FAIL\"}')
"

echo ""
echo "=== 9. Test G: YoY smoothing respected ==="
echo "$RESULT_A" | python3 -c "
import json, sys
data = json.load(sys.stdin)
ys = data['yearly_spending']
violations = 0
max_change = 0
for i in range(1, len(ys)):
    prev = ys[i-1]['recommended']
    curr = ys[i]['recommended']
    if prev > 0:
        change = abs(curr - prev) / prev
        max_change = max(max_change, change)
        if change > 0.051:
            violations += 1
            print(f'   Age {ys[i][\"age\"]}: {change*100:.1f}% change')
print(f'   Max YoY change: {max_change*100:.1f}%, violations: {violations}')
print(f'   Result: {\"PASS\" if violations == 0 else \"FAIL\"}')
"

echo ""
echo "=== 10. Test H: Essential floor respected ==="
echo "$RESULT_A" | python3 -c "
import json, sys
data = json.load(sys.stdin)
violations = 0
for y in data['yearly_spending']:
    if y['recommended'] < y['essential_floor'] - 0.01:
        violations += 1
        print(f'   Age {y[\"age\"]}: rec={y[\"recommended\"]} < floor={y[\"essential_floor\"]}')
print(f'   Floor violations: {violations}')
print(f'   Result: {\"PASS\" if violations == 0 else \"FAIL\"}')
"

echo ""
echo "=== 11. Test I: Corridor bounds ordered ==="
echo "$RESULT_A" | python3 -c "
import json, sys
data = json.load(sys.stdin)
violations = 0
for y in data['yearly_spending']:
    if y['corridor_low'] > y['recommended'] + 0.01:
        violations += 1
    if y['corridor_high'] < y['recommended'] - 0.01:
        violations += 1
print(f'   Corridor violations: {violations}')
print(f'   Result: {\"PASS\" if violations == 0 else \"FAIL\"}')
"

echo ""
echo "=== 12. Test J: recommended = floor + discretionary ==="
echo "$RESULT_A" | python3 -c "
import json, sys
data = json.load(sys.stdin)
violations = 0
for y in data['yearly_spending']:
    expected = y['essential_floor'] + y['discretionary']
    if abs(y['recommended'] - expected) > 0.02:
        violations += 1
        print(f'   Age {y[\"age\"]}: rec={y[\"recommended\"]} != floor+disc={expected}')
print(f'   Math violations: {violations}')
print(f'   Result: {\"PASS\" if violations == 0 else \"FAIL\"}')
"

echo ""
echo "============================================"
echo "=== ALL TESTS COMPLETE ==="
echo "============================================"
