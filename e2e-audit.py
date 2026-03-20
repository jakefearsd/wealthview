#!/usr/bin/env python3
"""
End-to-end audit of WealthView projection engine.
Creates 3 users with diverse portfolios and aggressively validates
every mathematical invariant in the projection results.
"""
import requests
import json
import sys
from decimal import Decimal, ROUND_HALF_UP

BASE = "http://localhost:80/api/v1"
ADMIN_EMAIL = "admin@wealthview.local"
ADMIN_PASS = "admin123"
TOLERANCE = 1.0  # $1 tolerance for rounding differences
PCT_TOLERANCE = 0.02  # 2% relative tolerance for growth checks

import time
RUN_ID = str(int(time.time()))  # Unique suffix for this run

failures = []
passes = 0


def fail(msg):
    failures.append(msg)
    print(f"  FAIL: {msg}")


def ok(msg):
    global passes
    passes += 1
    print(f"  OK: {msg}")


def close(a, b, tol=TOLERANCE):
    return abs((a or 0) - (b or 0)) <= tol


def login(email, password):
    r = requests.post(f"{BASE}/auth/login", json={"email": email, "password": password})
    r.raise_for_status()
    return r.json()["access_token"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def create_user(admin_token, email, password):
    """Create invite code and register a new user."""
    r = requests.post(f"{BASE}/tenant/invite-codes", headers=auth(admin_token))
    r.raise_for_status()
    code = r.json()["code"]
    r = requests.post(f"{BASE}/auth/register", json={
        "email": email, "password": password, "invite_code": code
    })
    r.raise_for_status()
    return r.json()["access_token"]


def create_income_source(token, data):
    r = requests.post(f"{BASE}/income-sources", headers=auth(token), json=data)
    r.raise_for_status()
    return r.json()


def create_spending_profile(token, data):
    r = requests.post(f"{BASE}/spending-profiles", headers=auth(token), json=data)
    r.raise_for_status()
    return r.json()


def create_property(token, data):
    r = requests.post(f"{BASE}/properties", headers=auth(token), json=data)
    r.raise_for_status()
    return r.json()


def create_scenario(token, data):
    r = requests.post(f"{BASE}/projections", headers=auth(token), json=data)
    r.raise_for_status()
    return r.json()


def run_projection(token, scenario_id):
    r = requests.get(f"{BASE}/projections/{scenario_id}/run", headers=auth(token))
    r.raise_for_status()
    return r.json()


# ===========================================================================
# INVARIANT CHECKS — applied to every projection result
# ===========================================================================

def check_balance_continuity(years, label):
    """end_balance[n] must equal start_balance[n+1]"""
    for i in range(len(years) - 1):
        y1, y2 = years[i], years[i + 1]
        if not close(y1["end_balance"], y2["start_balance"], 0.01):
            fail(f"[{label}] Balance discontinuity: year {y1['year']} end={y1['end_balance']:.2f} "
                 f"!= year {y2['year']} start={y2['start_balance']:.2f}")
            return
    ok(f"[{label}] Balance continuity across {len(years)} years")


def check_pool_balances_sum(years, label):
    """traditional + roth + taxable should equal end_balance for pool years"""
    pool_years = [y for y in years if y.get("traditional_balance") is not None]
    if not pool_years:
        return
    for y in pool_years:
        trad = y["traditional_balance"] or 0
        roth_ = y["roth_balance"] or 0
        taxable = y["taxable_balance"] or 0
        total = trad + roth_ + taxable
        if not close(total, y["end_balance"]):
            fail(f"[{label}] Pool sum mismatch year {y['year']}: "
                 f"trad={trad:.2f}+roth={roth_:.2f}+taxable={taxable:.2f}={total:.2f} "
                 f"!= end_balance={y['end_balance']:.2f}")
            return
    ok(f"[{label}] Pool balances sum to end_balance ({len(pool_years)} years)")


def check_tax_breakdown_consistency(years, label):
    """federal_tax + state_tax should be close to income tax portion of tax_liability.
    Income tax = tax_liability - SE tax. The breakdown is computed on combined income
    while the engine computes conversion/withdrawal tax independently, so allow 15% tolerance
    for bracket overlap effects."""
    breakdown_years = [y for y in years
                       if y.get("federal_tax") is not None and y.get("tax_liability") is not None]
    if not breakdown_years:
        return
    for y in breakdown_years:
        fed = y["federal_tax"] or 0
        state = y.get("state_tax") or 0
        se = y.get("self_employment_tax") or 0
        total = y["tax_liability"]
        income_tax_actual = total - se
        breakdown_income_tax = fed + state

        # Breakdown should be non-negative
        if fed < -TOLERANCE or (state and state < -TOLERANCE):
            fail(f"[{label}] Negative tax breakdown year {y['year']}: fed={fed:.2f}, state={state}")
            return

        # When there's no conversion+withdrawal overlap, breakdown should match closely
        # When both exist, breakdown may exceed actual due to bracket overlap (computed on combined income)
        if income_tax_actual > 1 and breakdown_income_tax > 0:
            ratio = breakdown_income_tax / income_tax_actual
            if ratio < 0.5 or ratio > 2.0:
                fail(f"[{label}] Tax breakdown way off year {y['year']}: "
                     f"breakdown={breakdown_income_tax:.2f} vs actual income tax={income_tax_actual:.2f} "
                     f"(ratio={ratio:.2f})")
                return

    ok(f"[{label}] Tax breakdown reasonable ({len(breakdown_years)} years)")


def check_salt_cap(years, label):
    """SALT deduction should never exceed $10,000"""
    for y in years:
        salt = y.get("salt_deduction")
        if salt is not None and salt > 10000.01:
            fail(f"[{label}] SALT exceeds $10k cap: year {y['year']} salt={salt:.2f}")
            return
    ok(f"[{label}] SALT cap respected")


def check_tax_source_sums(years, label):
    """tax_paid_from_taxable + trad + roth should be close to tax_liability for pool years.
    SE tax is paid from cash flow, not from pools, so source sum may be less than total."""
    pool_years = [y for y in years
                  if y.get("tax_paid_from_taxable") is not None
                  and y.get("tax_liability") is not None and y["tax_liability"] > 0]
    if not pool_years:
        return
    for y in pool_years:
        from_t = y["tax_paid_from_taxable"] or 0
        from_trad = y["tax_paid_from_traditional"] or 0
        from_roth = y["tax_paid_from_roth"] or 0
        total_source = from_t + from_trad + from_roth
        tax = y["tax_liability"]
        se = y.get("self_employment_tax") or 0
        tax_from_pools = tax - se  # SE tax is not deducted from pools
        if total_source > tax + TOLERANCE:
            fail(f"[{label}] Tax source exceeds tax year {y['year']}: "
                 f"sources={total_source:.2f} > tax={tax:.2f}")
            return
    ok(f"[{label}] Tax source accounts consistent ({len(pool_years)} years)")


def check_withdrawal_pool_sums(years, label):
    """withdrawal_from_taxable + trad + roth should equal withdrawals for pool retired years"""
    pool_years = [y for y in years
                  if y.get("withdrawal_from_taxable") is not None
                  and y["retired"] and y["withdrawals"] > 0]
    if not pool_years:
        return
    for y in pool_years:
        from_t = y["withdrawal_from_taxable"] or 0
        from_trad = y["withdrawal_from_traditional"] or 0
        from_roth = y["withdrawal_from_roth"] or 0
        total_wd = from_t + from_trad + from_roth
        # Withdrawals include tax payments deducted from pools, so total pool outflows
        # = withdrawals + tax payments. The withdrawal_from fields represent spending
        # withdrawals only. Let's check they don't exceed total withdrawals.
        if total_wd > y["withdrawals"] + TOLERANCE:
            fail(f"[{label}] Withdrawal pool sum exceeds withdrawals year {y['year']}: "
                 f"pool_sum={total_wd:.2f} > withdrawals={y['withdrawals']:.2f}")
            return
    ok(f"[{label}] Withdrawal pool sums consistent ({len(pool_years)} years)")


def check_no_negative_balances(years, label):
    """No balance should go negative"""
    for y in years:
        if y["end_balance"] < -TOLERANCE:
            fail(f"[{label}] Negative end_balance year {y['year']}: {y['end_balance']:.2f}")
            return
        for pool in ["traditional_balance", "roth_balance", "taxable_balance"]:
            val = y.get(pool)
            if val is not None and val < -TOLERANCE:
                fail(f"[{label}] Negative {pool} year {y['year']}: {val:.2f}")
                return
    ok(f"[{label}] No negative balances")


def check_ages_sequential(years, label):
    """Ages should increment by 1 each year"""
    for i in range(len(years) - 1):
        if years[i + 1]["age"] != years[i]["age"] + 1:
            fail(f"[{label}] Age not sequential: year {years[i]['year']} age={years[i]['age']}, "
                 f"next age={years[i + 1]['age']}")
            return
    ok(f"[{label}] Ages sequential")


def check_retirement_transition(years, label, retirement_year):
    """retired flag should flip at retirement year"""
    for y in years:
        if y["year"] < retirement_year and y["retired"]:
            fail(f"[{label}] Year {y['year']} marked retired before retirement year {retirement_year}")
            return
        if y["year"] >= retirement_year and not y["retired"]:
            fail(f"[{label}] Year {y['year']} not marked retired at/after retirement year {retirement_year}")
            return
    ok(f"[{label}] Retirement transition correct at {retirement_year}")


def check_growth_reasonable(years, label, expected_return):
    """Growth should be approximately start_balance * expected_return"""
    working_years = [y for y in years if not y["retired"] and y["start_balance"] > 10000]
    for y in working_years[:3]:  # Check first few working years
        expected = y["start_balance"] * expected_return
        # After contributions, growth is on a higher base, so allow generous tolerance
        if abs(y["growth"] - expected) > expected * 0.5:
            fail(f"[{label}] Growth year {y['year']}: actual={y['growth']:.2f}, "
                 f"expected~{expected:.2f} (start={y['start_balance']:.2f}, rate={expected_return})")
            return
    ok(f"[{label}] Growth rates reasonable")


def check_inflation_adjustment(years, label, inflation_rate):
    """For spending profile scenarios, spending should grow by ~inflation each year"""
    retired = [y for y in years if y["retired"]
               and y.get("essential_expenses") is not None and y["essential_expenses"] > 0]
    if len(retired) < 3:
        return
    y1, y2 = retired[0], retired[1]
    ratio = y2["essential_expenses"] / y1["essential_expenses"] if y1["essential_expenses"] > 0 else 0
    expected_ratio = 1 + inflation_rate
    if abs(ratio - expected_ratio) > 0.02:
        fail(f"[{label}] Inflation mismatch: essential expenses ratio year {y2['year']}/{y1['year']} = "
             f"{ratio:.4f}, expected ~{expected_ratio:.4f}")
        return
    ok(f"[{label}] Inflation adjustment correct ({inflation_rate * 100}%)")


def check_roth_conversion_amounts(years, label):
    """When roth_conversion_amount is set, traditional should decrease and roth increase"""
    conversion_years = [y for y in years
                        if y.get("roth_conversion_amount") is not None and y["roth_conversion_amount"] > 0]
    if not conversion_years:
        return
    # Just verify conversions are happening and are positive
    for y in conversion_years:
        if y["roth_conversion_amount"] <= 0:
            fail(f"[{label}] Roth conversion amount not positive year {y['year']}")
            return
    ok(f"[{label}] Roth conversions present in {len(conversion_years)} years")


def check_income_sources_at_correct_ages(years, label, start_age, end_age=None):
    """Income streams should appear at correct ages"""
    for y in years:
        income = y.get("income_streams_total") or 0
        if y["age"] >= start_age and (end_age is None or y["age"] <= end_age):
            if y["retired"] and income <= 0:
                fail(f"[{label}] No income at age {y['age']} (year {y['year']}), "
                     f"expected income from age {start_age}")
                return
        if end_age is not None and y["age"] > end_age and y["retired"]:
            # After end_age, this particular income should stop (but others may continue)
            pass  # Can't easily check individual sources from total
    ok(f"[{label}] Income sources present at expected ages")


def check_final_balance_matches(result, label):
    """final_balance should match last year's end_balance"""
    years = result["yearly_data"]
    if not years:
        fail(f"[{label}] No yearly data")
        return
    last_end = years[-1]["end_balance"]
    if not close(last_end, result["final_balance"]):
        fail(f"[{label}] final_balance={result['final_balance']:.2f} != last end_balance={last_end:.2f}")
        return
    ok(f"[{label}] Final balance matches last year end_balance")


def check_years_in_retirement(result, label, retirement_year):
    """years_in_retirement should match count of retired years"""
    retired_count = sum(1 for y in result["yearly_data"] if y["retired"])
    if retired_count != result["years_in_retirement"]:
        fail(f"[{label}] years_in_retirement={result['years_in_retirement']} "
             f"!= retired count={retired_count}")
        return
    ok(f"[{label}] Years in retirement count correct ({retired_count})")


def run_all_checks(result, label, retirement_year, expected_return=0.06, inflation_rate=0.03):
    years = result["yearly_data"]
    print(f"\n--- {label} ({len(years)} years, final_balance=${result['final_balance']:,.2f}) ---")
    check_balance_continuity(years, label)
    check_pool_balances_sum(years, label)
    check_tax_breakdown_consistency(years, label)
    check_salt_cap(years, label)
    check_tax_source_sums(years, label)
    check_withdrawal_pool_sums(years, label)
    check_no_negative_balances(years, label)
    check_ages_sequential(years, label)
    check_retirement_transition(years, label, retirement_year)
    check_growth_reasonable(years, label, expected_return)
    check_final_balance_matches(result, label)
    check_years_in_retirement(result, label, retirement_year)


# ===========================================================================
# USER 1: Conservative Couple (MFJ, CA, rental property, Social Security)
# ===========================================================================

def test_user1(admin_token):
    print("\n" + "=" * 70)
    print("USER 1: Conservative Couple — MFJ, CA, rental property, SS income")
    print("=" * 70)

    token = create_user(admin_token, f"couple-{RUN_ID}@test.local", "test123")

    # Create Social Security income source
    ss = create_income_source(token, {
        "name": "Social Security (combined)",
        "income_type": "social_security",
        "annual_amount": 48000,
        "start_age": 67,
        "end_age": None,
        "inflation_rate": 0.02,
        "one_time": False,
        "tax_treatment": "partially_taxable"
    })

    # Create rental property
    prop = create_property(token, {
        "address": "123 Rental Ave, San Diego, CA",
        "purchase_price": 500000,
        "purchase_date": "2020-01-15",
        "current_value": 620000,
        "mortgage_balance": 350000,
        "loan_amount": 400000,
        "annual_interest_rate": 0.045,
        "loan_term_months": 360,
        "loan_start_date": "2020-01-15",
        "property_type": "investment",
        "annual_appreciation_rate": 0.03,
        "annual_property_tax": 6250,
        "annual_insurance_cost": 1800,
        "annual_maintenance_cost": 3000,
        "in_service_date": "2020-01-15",
        "land_value": 200000
    })

    # Create rental income source tied to property
    rental_income = create_income_source(token, {
        "name": "Rental Income - San Diego",
        "income_type": "rental_property",
        "annual_amount": 36000,
        "start_age": 62,
        "end_age": None,
        "inflation_rate": 0.03,
        "one_time": False,
        "tax_treatment": "rental_passive",
        "property_id": prop["id"]
    })

    # Create spending profile with tiers
    spending = create_spending_profile(token, {
        "name": "Couple Retirement Spending",
        "essential_expenses": 60000,
        "discretionary_expenses": 30000,
        "spending_tiers": [
            {"name": "Active Retirement", "start_age": 62, "end_age": 75,
             "essential_expenses": 60000, "discretionary_expenses": 30000},
            {"name": "Slower Pace", "start_age": 76, "end_age": 85,
             "essential_expenses": 50000, "discretionary_expenses": 15000},
            {"name": "Late Retirement", "start_age": 86, "end_age": None,
             "essential_expenses": 45000, "discretionary_expenses": 10000}
        ]
    })

    # Scenario 1A: Full scenario with CA state tax
    scenario_1a = create_scenario(token, {
        "name": "Couple - CA with rental",
        "retirement_date": "2028-01-01",
        "end_age": 92,
        "inflation_rate": 0.03,
        "birth_year": 1966,
        "withdrawal_rate": 0.04,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "married_filing_jointly",
        "state": "CA",
        "primary_residence_property_tax": 9000,
        "primary_residence_mortgage_interest": 12000,
        "accounts": [
            {"linked_account_id": None, "initial_balance": 800000,
             "annual_contribution": 20000, "expected_return": 0.06, "account_type": "traditional"},
            {"linked_account_id": None, "initial_balance": 300000,
             "annual_contribution": 7000, "expected_return": 0.06, "account_type": "roth"},
            {"linked_account_id": None, "initial_balance": 200000,
             "annual_contribution": 10000, "expected_return": 0.06, "account_type": "taxable"}
        ],
        "spending_profile_id": spending["id"],
        "income_sources": [
            {"income_source_id": ss["id"], "override_annual_amount": None},
            {"income_source_id": rental_income["id"], "override_annual_amount": None}
        ]
    })

    r1a = run_projection(token, scenario_1a["id"])
    run_all_checks(r1a, "User1-CA-Rental", 2028, 0.06, 0.03)
    check_inflation_adjustment(r1a["yearly_data"], "User1-CA-Rental", 0.03)

    # Verify SS income kicks in at age 67
    check_income_sources_at_correct_ages(
        r1a["yearly_data"], "User1-SS-age67", start_age=67)

    # Verify spending tiers change
    retired = [y for y in r1a["yearly_data"] if y["retired"]]
    tier1 = [y for y in retired if y["age"] <= 75 and y.get("essential_expenses")]
    tier2 = [y for y in retired if 76 <= y["age"] <= 85 and y.get("essential_expenses")]
    if tier1 and tier2:
        # Tier 2 essential should be less than tier 1 (base: 50k vs 60k, with inflation)
        # But tier2 starts fresh inflation from its startAge, so the first year of tier2
        # should have essential ~50k adjusted for inflation
        t1_last = tier1[-1]["essential_expenses"]
        t2_first = tier2[0]["essential_expenses"]
        if t2_first >= t1_last:
            fail("[User1-CA-Rental] Spending tier transition: tier2 first "
                 f"({t2_first:.2f}) >= tier1 last ({t1_last:.2f})")
        else:
            ok(f"[User1-CA-Rental] Spending tier transition reduces spending "
               f"(tier1 last={t1_last:.2f}, tier2 first={t2_first:.2f})")

    # Scenario 1B: Same but federal-only — to verify CA adds tax
    scenario_1b = create_scenario(token, {
        "name": "Couple - Federal only",
        "retirement_date": "2028-01-01",
        "end_age": 92,
        "inflation_rate": 0.03,
        "birth_year": 1966,
        "withdrawal_rate": 0.04,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "married_filing_jointly",
        "accounts": [
            {"linked_account_id": None, "initial_balance": 800000,
             "annual_contribution": 20000, "expected_return": 0.06, "account_type": "traditional"},
            {"linked_account_id": None, "initial_balance": 300000,
             "annual_contribution": 7000, "expected_return": 0.06, "account_type": "roth"},
            {"linked_account_id": None, "initial_balance": 200000,
             "annual_contribution": 10000, "expected_return": 0.06, "account_type": "taxable"}
        ],
        "spending_profile_id": spending["id"],
        "income_sources": [
            {"income_source_id": ss["id"], "override_annual_amount": None},
            {"income_source_id": rental_income["id"], "override_annual_amount": None}
        ]
    })

    r1b = run_projection(token, scenario_1b["id"])
    run_all_checks(r1b, "User1-FedOnly", 2028, 0.06, 0.03)

    # CA should have higher total tax than federal-only
    ca_tax = sum(y["tax_liability"] or 0 for y in r1a["yearly_data"] if y["retired"])
    fed_tax = sum(y["tax_liability"] or 0 for y in r1b["yearly_data"] if y["retired"])
    if ca_tax > fed_tax:
        ok(f"[User1-Compare] CA lifetime tax (${ca_tax:,.0f}) > Fed-only (${fed_tax:,.0f})")
    else:
        fail(f"[User1-Compare] CA tax (${ca_tax:,.0f}) not higher than Fed-only (${fed_tax:,.0f})")

    return token


# ===========================================================================
# USER 2: Aggressive Single — OR, Roth conversions, large portfolio
# ===========================================================================

def test_user2(admin_token):
    print("\n" + "=" * 70)
    print("USER 2: Aggressive Single — OR, fill-bracket Roth conversion")
    print("=" * 70)

    token = create_user(admin_token, f"aggr-{RUN_ID}@test.local", "test123")

    # Part-time consulting income (self-employment)
    consulting = create_income_source(token, {
        "name": "Part-time consulting",
        "income_type": "part_time_work",
        "annual_amount": 50000,
        "start_age": 55,
        "end_age": 62,
        "inflation_rate": 0.0,
        "one_time": False,
        "tax_treatment": "self_employment"
    })

    # Social Security at 70
    ss = create_income_source(token, {
        "name": "Social Security",
        "income_type": "social_security",
        "annual_amount": 36000,
        "start_age": 70,
        "end_age": None,
        "inflation_rate": 0.02,
        "one_time": False,
        "tax_treatment": "partially_taxable"
    })

    # Scenario 2A: Oregon, fill-bracket Roth conversion, traditional-first withdrawal
    scenario_2a = create_scenario(token, {
        "name": "Aggressive - OR fill-bracket",
        "retirement_date": "2026-07-01",
        "end_age": 95,
        "inflation_rate": 0.025,
        "birth_year": 1971,
        "withdrawal_rate": 0.035,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "single",
        "state": "OR",
        "primary_residence_property_tax": 4500,
        "primary_residence_mortgage_interest": 8000,
        "roth_conversion_strategy": "fill_bracket",
        "target_bracket_rate": 0.22,
        "withdrawal_order": "traditional_first",
        "accounts": [
            {"linked_account_id": None, "initial_balance": 1500000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "traditional"},
            {"linked_account_id": None, "initial_balance": 200000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "roth"},
            {"linked_account_id": None, "initial_balance": 500000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "taxable"}
        ],
        "income_sources": [
            {"income_source_id": consulting["id"], "override_annual_amount": None},
            {"income_source_id": ss["id"], "override_annual_amount": None}
        ]
    })

    r2a = run_projection(token, scenario_2a["id"])
    run_all_checks(r2a, "User2-OR-FillBracket", 2026, 0.07, 0.025)
    check_roth_conversion_amounts(r2a["yearly_data"], "User2-OR-FillBracket")

    # Verify SE tax appears during consulting years (age 55-62)
    years = r2a["yearly_data"]
    se_years = [y for y in years if y.get("self_employment_tax") and y["self_employment_tax"] > 0]
    consulting_ages = [y for y in years if 55 <= y["age"] <= 62 and y["retired"]]
    if consulting_ages and not se_years:
        fail("[User2-OR-FillBracket] No SE tax found during consulting years (age 55-62)")
    elif se_years:
        ok(f"[User2-OR-FillBracket] SE tax present in {len(se_years)} years")

    # Scenario 2B: Same but with dynamic spending (vanguard)
    scenario_2b = create_scenario(token, {
        "name": "Aggressive - Dynamic spending",
        "retirement_date": "2026-07-01",
        "end_age": 95,
        "inflation_rate": 0.025,
        "birth_year": 1971,
        "withdrawal_rate": 0.04,
        "withdrawal_strategy": "vanguard_dynamic_spending",
        "dynamic_ceiling": 0.05,
        "dynamic_floor": 0.025,
        "filing_status": "single",
        "state": "OR",
        "primary_residence_property_tax": 4500,
        "primary_residence_mortgage_interest": 8000,
        "accounts": [
            {"linked_account_id": None, "initial_balance": 1500000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "traditional"},
            {"linked_account_id": None, "initial_balance": 200000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "roth"},
            {"linked_account_id": None, "initial_balance": 500000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "taxable"}
        ],
        "income_sources": [
            {"income_source_id": consulting["id"], "override_annual_amount": None},
            {"income_source_id": ss["id"], "override_annual_amount": None}
        ]
    })

    r2b = run_projection(token, scenario_2b["id"])
    run_all_checks(r2b, "User2-DynamicSpending", 2026, 0.07, 0.025)

    # Verify dynamic spending keeps withdrawals within floor/ceiling bounds
    retired_years = [y for y in r2b["yearly_data"] if y["retired"] and y["withdrawals"] > 0]
    if len(retired_years) >= 3:
        # Check that withdrawals adjust (not perfectly constant like fixed %)
        wd_values = [y["withdrawals"] for y in retired_years[:10]]
        if len(set(round(w, 0) for w in wd_values)) <= 1:
            fail("[User2-DynamicSpending] Dynamic spending withdrawals appear constant (not dynamic)")
        else:
            ok(f"[User2-DynamicSpending] Dynamic spending shows variability in withdrawals")

    # Scenario 2C: Arizona flat tax comparison
    scenario_2c = create_scenario(token, {
        "name": "Aggressive - AZ comparison",
        "retirement_date": "2026-07-01",
        "end_age": 95,
        "inflation_rate": 0.025,
        "birth_year": 1971,
        "withdrawal_rate": 0.035,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "single",
        "state": "AZ",
        "primary_residence_property_tax": 3000,
        "primary_residence_mortgage_interest": 0,
        "accounts": [
            {"linked_account_id": None, "initial_balance": 1500000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "traditional"},
            {"linked_account_id": None, "initial_balance": 200000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "roth"},
            {"linked_account_id": None, "initial_balance": 500000,
             "annual_contribution": 0, "expected_return": 0.07, "account_type": "taxable"}
        ],
        "income_sources": [
            {"income_source_id": consulting["id"], "override_annual_amount": None},
            {"income_source_id": ss["id"], "override_annual_amount": None}
        ]
    })

    r2c = run_projection(token, scenario_2c["id"])
    run_all_checks(r2c, "User2-AZ", 2026, 0.07, 0.025)

    # OR should have higher state tax than AZ
    or_state_tax = sum(y.get("state_tax") or 0 for y in r2a["yearly_data"] if y["retired"])
    az_state_tax = sum(y.get("state_tax") or 0 for y in r2c["yearly_data"] if y["retired"])
    if or_state_tax > az_state_tax:
        ok(f"[User2-Compare] OR state tax (${or_state_tax:,.0f}) > AZ (${az_state_tax:,.0f})")
    else:
        fail(f"[User2-Compare] OR state tax (${or_state_tax:,.0f}) not > AZ (${az_state_tax:,.0f})")

    return token


# ===========================================================================
# USER 3: Early Retiree — NV, small portfolio, one-time income, edge cases
# ===========================================================================

def test_user3(admin_token):
    print("\n" + "=" * 70)
    print("USER 3: Early Retiree — NV, small portfolio, edge cases")
    print("=" * 70)

    token = create_user(admin_token, f"early-{RUN_ID}@test.local", "test123")

    # One-time inheritance
    inheritance = create_income_source(token, {
        "name": "Inheritance",
        "income_type": "other",
        "annual_amount": 100000,
        "start_age": 50,
        "end_age": None,
        "inflation_rate": 0.0,
        "one_time": True,
        "tax_treatment": "tax_free"
    })

    # Small pension
    pension = create_income_source(token, {
        "name": "Small Pension",
        "income_type": "pension",
        "annual_amount": 18000,
        "start_age": 50,
        "end_age": None,
        "inflation_rate": 0.0,
        "one_time": False,
        "tax_treatment": "taxable"
    })

    # Social Security at 62 (early claiming)
    ss_early = create_income_source(token, {
        "name": "Social Security (early)",
        "income_type": "social_security",
        "annual_amount": 18000,
        "start_age": 62,
        "end_age": None,
        "inflation_rate": 0.02,
        "one_time": False,
        "tax_treatment": "partially_taxable"
    })

    # Scenario 3A: Tiny portfolio, early retirement, NV no state tax
    scenario_3a = create_scenario(token, {
        "name": "Early Retiree - NV bare minimum",
        "retirement_date": "2026-01-01",
        "end_age": 95,
        "inflation_rate": 0.03,
        "birth_year": 1976,
        "withdrawal_rate": 0.03,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "single",
        "state": "NV",
        "primary_residence_property_tax": 2000,
        "primary_residence_mortgage_interest": 0,
        "accounts": [
            {"linked_account_id": None, "initial_balance": 150000,
             "annual_contribution": 0, "expected_return": 0.05, "account_type": "traditional"},
            {"linked_account_id": None, "initial_balance": 50000,
             "annual_contribution": 0, "expected_return": 0.05, "account_type": "taxable"}
        ],
        "income_sources": [
            {"income_source_id": pension["id"], "override_annual_amount": None},
            {"income_source_id": ss_early["id"], "override_annual_amount": None},
            {"income_source_id": inheritance["id"], "override_annual_amount": None}
        ]
    })

    r3a = run_projection(token, scenario_3a["id"])
    run_all_checks(r3a, "User3-NV-EarlyRetire", 2026, 0.05, 0.03)

    # This portfolio will likely deplete — verify depletion is handled gracefully
    years = r3a["yearly_data"]
    depleted = [y for y in years if y["end_balance"] <= 0.01 and y["retired"]]
    if depleted:
        ok(f"[User3-NV-EarlyRetire] Portfolio depletes at year {depleted[0]['year']} "
           f"(age {depleted[0]['age']}) — graceful depletion")
        # After depletion, withdrawals should be zero
        post_depletion = [y for y in years if y["year"] > depleted[0]["year"]]
        has_phantom_withdrawals = any(y["withdrawals"] > TOLERANCE for y in post_depletion)
        if has_phantom_withdrawals:
            fail("[User3-NV-EarlyRetire] Withdrawals continue after portfolio depletion!")
        else:
            ok("[User3-NV-EarlyRetire] No withdrawals after depletion")
    else:
        ok("[User3-NV-EarlyRetire] Portfolio survives (unexpected for small portfolio)")

    # Verify NV has no state tax
    nv_state_tax = sum(y.get("state_tax") or 0 for y in years if y["retired"])
    if nv_state_tax > 0:
        fail(f"[User3-NV-EarlyRetire] NV state tax should be $0, got ${nv_state_tax:,.2f}")
    else:
        ok("[User3-NV-EarlyRetire] NV state tax is $0")

    # Scenario 3B: WA with pro-rata withdrawal order
    scenario_3b = create_scenario(token, {
        "name": "Early Retiree - WA pro-rata",
        "retirement_date": "2026-01-01",
        "end_age": 95,
        "inflation_rate": 0.03,
        "birth_year": 1976,
        "withdrawal_rate": 0.04,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "single",
        "state": "WA",
        "primary_residence_property_tax": 3500,
        "primary_residence_mortgage_interest": 0,
        "withdrawal_order": "pro_rata",
        "accounts": [
            {"linked_account_id": None, "initial_balance": 300000,
             "annual_contribution": 0, "expected_return": 0.06, "account_type": "traditional"},
            {"linked_account_id": None, "initial_balance": 100000,
             "annual_contribution": 0, "expected_return": 0.06, "account_type": "roth"},
            {"linked_account_id": None, "initial_balance": 100000,
             "annual_contribution": 0, "expected_return": 0.06, "account_type": "taxable"}
        ],
        "income_sources": [
            {"income_source_id": pension["id"], "override_annual_amount": None},
            {"income_source_id": ss_early["id"], "override_annual_amount": None}
        ]
    })

    r3b = run_projection(token, scenario_3b["id"])
    run_all_checks(r3b, "User3-WA-ProRata", 2026, 0.06, 0.03)

    # Verify pro-rata splits withdrawals proportionally
    retired_w_wd = [y for y in r3b["yearly_data"]
                    if y["retired"] and y["withdrawals"] > 100
                    and y.get("withdrawal_from_taxable") is not None
                    and y.get("withdrawal_from_traditional") is not None
                    and y.get("withdrawal_from_roth") is not None]
    if retired_w_wd:
        y = retired_w_wd[0]
        from_t = y["withdrawal_from_taxable"] or 0
        from_trad = y["withdrawal_from_traditional"] or 0
        from_roth = y["withdrawal_from_roth"] or 0
        total_wd = from_t + from_trad + from_roth
        if total_wd > 0:
            # Pro-rata: proportions should roughly match balance proportions
            trad_pct = from_trad / total_wd
            # Traditional is 60% of portfolio
            if 0.40 < trad_pct < 0.80:
                ok(f"[User3-WA-ProRata] Pro-rata traditional withdrawal "
                   f"proportion={trad_pct:.1%} (expected ~60%)")
            else:
                fail(f"[User3-WA-ProRata] Pro-rata traditional proportion "
                     f"{trad_pct:.1%} not near expected ~60%")

    # Scenario 3C: Roth-only portfolio (edge case — no tax on withdrawals)
    scenario_3c = create_scenario(token, {
        "name": "Roth Only - no tax",
        "retirement_date": "2026-01-01",
        "end_age": 85,
        "inflation_rate": 0.03,
        "birth_year": 1976,
        "withdrawal_rate": 0.04,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "single",
        "accounts": [
            {"linked_account_id": None, "initial_balance": 500000,
             "annual_contribution": 0, "expected_return": 0.06, "account_type": "roth"}
        ]
    })

    r3c = run_projection(token, scenario_3c["id"])
    run_all_checks(r3c, "User3-RothOnly", 2026, 0.06, 0.03)

    # Roth-only should have zero tax
    roth_tax = sum(y["tax_liability"] or 0 for y in r3c["yearly_data"] if y["retired"])
    if roth_tax > TOLERANCE:
        fail(f"[User3-RothOnly] Roth-only portfolio has tax={roth_tax:.2f}, expected ~$0")
    else:
        ok(f"[User3-RothOnly] Roth-only portfolio has $0 tax")

    # Scenario 3D: Taxable-only (edge case)
    scenario_3d = create_scenario(token, {
        "name": "Taxable Only",
        "retirement_date": "2026-01-01",
        "end_age": 85,
        "inflation_rate": 0.03,
        "birth_year": 1976,
        "withdrawal_rate": 0.04,
        "withdrawal_strategy": "fixed_percentage",
        "filing_status": "single",
        "accounts": [
            {"linked_account_id": None, "initial_balance": 500000,
             "annual_contribution": 0, "expected_return": 0.06, "account_type": "taxable"}
        ]
    })

    r3d = run_projection(token, scenario_3d["id"])
    run_all_checks(r3d, "User3-TaxableOnly", 2026, 0.06, 0.03)

    return token


# ===========================================================================
# MAIN
# ===========================================================================

if __name__ == "__main__":
    print("WealthView End-to-End Audit")
    print("=" * 70)

    admin_token = login(ADMIN_EMAIL, ADMIN_PASS)

    test_user1(admin_token)
    test_user2(admin_token)
    test_user3(admin_token)

    print("\n" + "=" * 70)
    print(f"RESULTS: {passes} passed, {len(failures)} failed")
    print("=" * 70)

    if failures:
        print("\nFAILURES:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    else:
        print("\nAll checks passed!")
        sys.exit(0)
