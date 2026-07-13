# Household / Survivor Modeling (Sub-project A: fixed death ages) — Design

**Date:** 2026-07-12
**Status:** Approved (design), pending implementation plan
**Scope:** Model a two-person household and the first-death transition in both projection engines.
This is sub-project **A** of the household effort: deterministic (fixed, user-set death ages)
everywhere. Sub-project **B** (stochastic mortality in the Monte Carlo: per-trial death years,
survivor-aware success metric) is a separate spec that consumes A's machinery.

## Problem statement

The engine models exactly one person. A married household gets combined-SS taxation (B2) but:
the spouse has no age (age-65 deduction and IRMAA apply ×1; RMDs key off the primary's birth year
even for the spouse's IRA), no income ownership (survivor transitions are unmodeled), and the
first-death tax cliff — single brackets, halved standard deduction, tighter IRMAA/NIIT tiers,
survivor-SS reduction, one RMD table — simply never happens. For married users (the main
audience) late-retirement projections are structurally optimistic on taxes and wrong on income.

## Decisions (from brainstorming)

| Fork | Decision |
|---|---|
| Mortality timing | **Both, decomposed:** A = fixed death ages per spouse (defaults from SSA period-life expectancy, editable); B = optional stochastic-mortality MC mode, separate spec. |
| Spouse data shape | **Owned accounts + owned income.** Accounts: `owner ∈ {primary, spouse, joint}` (joint = taxable only; trad/roth individually owned). Income sources: `owner` + `survivor_percent`. |
| Survivor income | **SS-typed sources: statutory keep-larger rule automatically** (survivor keeps the larger benefit, smaller stops). Non-SS sources: per-source `survivor_percent` (default 1.0; pensions set to elected 0/50/75/100%). |
| Basis step-up | **Included:** joint taxable steps up 50% of embedded gain at first death (common-law default) or 100% (`community_property = true`); deceased-owned taxable steps up fully. Lot mechanics: `basis += (value − basis) × factor`. |
| Survivor spending | **`survivor_spending_factor`** (default 0.75, range 0.5–1.0) scales floors + discretionary + tiers from the transition year, in both engines and the optimizer. |
| Architecture | **Approach 1: `HouseholdContext` threaded through the existing engines** (one continuous run; owner-aware pools; one atomic transition event). Rejected: stitched dual runs (breaks lot/carryforward/seed continuity); full person-entity rewrite (claiming-strategy YAGNI). |

## Non-goals (A)
- Stochastic mortality (sub-project B). SS claiming strategy / PIA-based spousal top-up rules
  (users enter per-person SS amounts). Second-marriage/ex-spouse benefits. Estate/inheritance tax.
  Non-spouse beneficiaries (SECURE 10-year rule). Separate property agreements beyond the single
  `community_property` flag.

---

## 1. Data model & API

**Scenario** (request/response + params plumbing per the established pattern):
- `spouse_birth_year` (nullable Integer — **null ⇒ single-person; everything behaves exactly as
  today**, the global back-compat anchor).
- `primary_death_age`, `spouse_death_age` (Integer; defaults = SSA period-life expectancy for the
  respective birth cohorts, resolved server-side from a small embedded sex-neutral cohort table
  (constants in core, sourced from the SSA 2021 period life table, ~dozen rows by birth decade —
  no external call, no new DB table) and echoed; editable 50–120).
- `survivor_spending_factor` (numeric, default 0.75, 0.5–1.0).
- `community_property` (boolean, default false).
- Filing status: user-set while both alive (unchanged); **auto-flips to `single` from the
  transition year** regardless of the configured value.

**Projection accounts:** `owner ∈ {primary, spouse, joint}` (default `primary`). Validation:
`joint` only for `taxable` type; trad/roth must be `primary` or `spouse`. Migration adds the
column (`text NOT NULL DEFAULT 'primary'` + CHECK) — additive, existing rows untouched.

**Income sources (scenario-linked):** `owner ∈ {primary, spouse}` (default `primary`),
`survivor_percent` (numeric default 1.0, 0–1; ignored for SS-typed sources). A source's
`start_age`/`end_age` now reference its **owner's** age.

**Wire/UI:** all additive snake_case; ScenarioForm gains a collapsible "Spouse / Household"
section (spouse birth year enables the rest); account rows + income sources gain owner selectors;
income sources gain survivor-% where non-SS. Every default reproduces current single-person
behavior byte-for-byte.

## 2. HouseholdContext

Resolved once per run (core, near `ProjectionInputBuilder`):
`persons[PRIMARY, SPOUSE]` (birthYear, deathAge → deathYear), `transitionYear = min(deathYears)`
(only meaningful when spouse present), `survivor` identity, `secondDeathYear`. Rules:
- Single-person (`spouse_birth_year` null): context degenerates; no transition; engines take
  their existing paths untouched.
- If the second death lands inside the horizon, the projection **truncates at that year**; the
  final balance is the bequest. Otherwise the horizon is unchanged.
- Death ages beyond the horizon never transition (documented).

## 3. Owner-aware pools (the heart of the work)

Both engines generalize `{taxable, traditional, roth}` →
`{joint-taxable, trad-P, trad-S, roth-P, roth-S}`:
- **Deterministic** `PoolStrategy.MultiPool`: pools keyed by (type, owner). Withdrawal orders
  operate per *type*; within a type, draws split **proportionally by owner balance** (documented
  convention). Contributions route to the owner configured on the account.
- **MC** `TrialSimulator`: flat `double[5]` (+ parallel `TaxableLots` for the joint pool as
  today). Hot-loop discipline unchanged (no allocation, exact tables).
- **Two RMD streams while both alive:** each owner's traditional pool RMDs at that owner's
  SECURE-2.0 age from that owner's prior-year balance (per-owner `RmdCalculator` inputs). This is
  the age-gap correctness the feature exists for.
- Single-person scenarios collapse to the current 3-pool shape (anchor: existing goldens
  byte-identical).

## 4. The transition event (atomic, ordered, at the transitionYear boundary)

1. **Income:** SS keep-larger — compare the two SS sources' **effective real amounts in the
   transition year** (each source's own growth/inflation treatment applied to that year);
   survivor's SS := the larger, the other ends; non-SS
   sources owned by the deceased scale by `survivor_percent` (0 ⇒ ends); survivor-owned and joint
   sources continue.
2. **Spousal rollover:** deceased's trad → survivor's trad; deceased's roth → survivor's roth
   (treat-as-own assumption). Conservation-pinned (no value created/lost). Survivor's RMD
   age/table governs thereafter.
3. **Step-up:** joint-taxable lots `basis += (value − basis) × (community_property ? 1.0 : 0.5)`;
   deceased-owned taxable (if any) steps up fully, then belongs to the survivor.
4. **Spending:** floors, discretionary, and tier amounts × `survivor_spending_factor` from this
   year forward (engines + optimizer floors + corridor derivation).
5. **Filing:** single from this year. Mechanically cheap: T12's per-year tax tables, IRMAA tiers
   (V075), standard deductions (incl. age-65 adders), NIIT/SS thresholds are all already
   filing-status-parameterized — year tables are built MFJ before, single after.
6. **Per-person thresholds while both alive** (now knowable): age-65 additional deduction ×2 when
   both ≥65; IRMAA surcharge ×2 when both ≥65 (Medicare per person, 2-year lookback per the
   existing machinery).

SS provisional-income convergence (B2) runs with combined benefits + MFJ tiers before, survivor
benefit + single tiers after — through the existing year-context, no new loop.

**Granularity (HP3 Part C, T5-review flagged):** the transition is **whole-year**, not prorated to
the actual date of death. The `transitionYear` (the calendar year of the first death) is treated as
FULLY survivor-mode for every step above — income, spending, and filing status all flip for the
entire year, not just from the death date forward. This is consistent with the engine's existing
whole-year `retired` convention (a scenario retiring mid-year is modeled as retired for the whole
year too) and keeps the transition a single, clean, atomic step rather than a sub-year blend. See
the code comment at the transition site (`HouseholdTransition#resolveYear`) for the same note.

## 5. Optimizer / guardrails
Floors scale by the survivor factor from the transition year (search + reporting + corridor).
Guardrail signature/staleness gains every household field. Seed derivation shape unchanged.
Adaptation rule (T16/T24) operates on the scaled schedule — no special-casing.

## 6. Testing & goldens
- **Unit, per transition component:** rollover conservation; keep-larger (both orderings — larger
  benefit on either side); step-up arithmetic (0.5 / 1.0 / deceased-owned-full); survivor-%
  application incl. 0; spending-factor application; filing-flip table selection; truncation at
  second death.
- **Per-owner RMD oracle:** two-stream test with an age gap (one owner at RMD age, other not);
  both streams vs `RmdCalculator` oracles.
- **Invariant matrix** (`EngineInvariantsTest`): a household case — balance identity across the
  transition boundary (rollover + step-up must conserve; only taxes/spending change flows).
- **Goldens:** existing 5 are single-person ⇒ **byte-unchanged anchors** (verify). New golden #6
  `household-survivor`: age-gap couple (e.g. birth years 1958/1966), death mid-horizon, pension at
  50% survivor, SS pair exercising keep-larger, appreciated joint taxable exercising step-up —
  pins the entire cliff year-by-year.
- Full verify (units + 5 gates + Testcontainers ITs) + frontend suite green before hand-off.

## Module / convention notes
Records/DTO conventions, constructor injection, BigDecimal money, snake_case wire, migrations
additive-only, commit on `main`, never push without instruction. This is a large sub-project —
the plan should decompose into ~8–10 tasks (context+schema; owner plumbing; deterministic pool
generalization; transition event; MC generalization; SS/IRMAA/deduction per-person; optimizer;
frontend; golden #6 + invariants), each with its own tests and golden verification, executed
subagent-driven with full reviews on engine tasks.
