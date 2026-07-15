# Household / Survivor Modeling (Sub-project B: stochastic mortality) — Design

**Date:** 2026-07-15
**Status:** Approved (design), pending implementation plan
**Scope:** Add an opt-in **stochastic-mortality Monte Carlo mode**: sample each spouse's death year
per trial from a sex-specific SSA life table, so the projection's success probability reflects
longevity risk (living past life expectancy) and mortality-timing risk (when the first-death tax
cliff lands). Sub-project **B** of the household effort; it *consumes* sub-project A's machinery
(`HouseholdContext`, owner-split pools, the in-loop first-death transition, survivor income /
step-up / rollover / ×factor spending / MFJ→single flip) and adds a per-trial mortality sampler in
front of it. A shipped 2026-07-13 (spec `2026-07-12-household-survivor-modeling-design.md`).

## Problem statement

Sub-project A models the first-death transition correctly, but at **fixed** death ages (SSA cohort
life expectancy, editable). Every trial dies on the same two dates. That hides the two largest
unknowns in a married retirement:

- **Longevity risk** — the survivor living well past life expectancy is exactly the tail that
  breaks a plan, and a single fixed death age never samples it.
- **Mortality-timing risk** — *when* the first death happens moves the single-filer tax cliff
  (halved standard deduction, single brackets, tighter IRMAA/NIIT, reduced survivor SS) by years,
  materially changing lifetime tax drag.

The success probability today answers "does the plan survive to *these two dates*," not "does the
plan survive *however long we actually live*."

## Decisions (from brainstorming)

| Fork | Decision |
|---|---|
| What it drives | **Success-probability MC only.** The projection / fan-chart MC samples mortality; the guardrail **optimizer keeps recommending against fixed death ages (A)**. B adds a longevity-aware success number beside the fixed-death result — it does not move the recommendation. Keeps the blast radius inside the trial engine and the recommendation stable. |
| Mortality table | **Sex-specific SSA period life table** (`qx` by single year of age, male/female). Adds a per-person `sex` param. Chosen because *which* spouse survives and for *how long* is the whole survivor model — women outlive men ~2–3 yrs, a systematic signal a sex-neutral table erases. |
| Success metric | **Report both.** Headline = **lifetime success** (floors funded every year while *either* spouse alive; a trial ending via death with money left = success — the actuarially honest `P(never fall short while alive)`). Beside it = **longevity-conditional** success among trials where the survivor reaches `longevity_conditional_age` (default 95). Plus the sampled **death-age distribution** (P10 / median / P90) so a headline higher than the fixed-death run reads as "some trials end early," not a free lunch. |
| Correlation | **Independent** per-spouse sampling for v1. Couples' mortality is mildly positively correlated ("broken-heart"), but a copula adds a weakly-agreed parameter; deferred (see Non-goals). |
| Reproducibility | **Dedicated seeded mortality RNG stream**, `new Random(seed + MORTALITY_SEED_OFFSET)`, per-trial draws in trial order — mirrors `PortfolioPathGenerator`. Null seed ⇒ fresh `Random` (non-reproducible), matching today's return path. |
| Default / anchor | Toggle **off by default.** Toggle off ⇒ **byte-identical to A** (fixed death ages) for every scenario; single-person and every fixed-death household golden / MC char unchanged. B is purely additive and opt-in. |

## Non-goals (B)

- Correlated / copula mortality; health- or smoker-adjusted `qx`; user-supplied mortality overrides.
- Re-driving the **optimizer / guardrail recommendation** under stochastic mortality (the sustainability
  search stays on fixed death ages — decided above).
- Stochastic mortality in the **deterministic** engine (A stays fixed-death; it is the reasoned-about
  single timeline).
- Deferred-annuity / longevity-insurance products, dynamic claiming strategy (already an A non-goal).

---

## 1. Data model & API

**New scenario params (params_json, snake_case wire — additive, no column migration):**

| Field | Type | Default | Validation |
|---|---|---|---|
| `stochastic_mortality` | Boolean | `false` | — |
| `primary_sex` | String | `null` | `male` \| `female` (null ⇒ blended `qx`) |
| `spouse_sex` | String | `null` | `male` \| `female`; requires `spouse_birth_year` |
| `longevity_conditional_age` | Integer | `95` | 80–110; only meaningful when `stochastic_mortality` |

Threaded through the established seam: `ScenarioRequest` → `ScenarioParamsSource` → `ScenarioParams`
→ `ScenarioParamsParser` → `ScenarioCrudService` (validation beside the A household fields) →
`GuardrailProfileService.scenarioSignature` (every new field joins the staleness hash) →
`GuardrailOptimizationInput` (the MC's input record). Mirrors A task 3 exactly.

**Mortality table (seed table, follows the `irmaa_tiers` / `ltcg_brackets` pattern):**

```sql
-- V080: sex-specific SSA period-life mortality rates for stochastic-mortality Monte Carlo.
CREATE TABLE IF NOT EXISTS mortality_rates (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sex        text NOT NULL,               -- 'male' | 'female'
    age        integer NOT NULL,            -- exact age in years
    qx         numeric(9,8) NOT NULL,       -- P(death within the year | alive at exact age)
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_mortality_rates_sex_age UNIQUE (sex, age),
    CONSTRAINT chk_mortality_rates_sex CHECK (sex IN ('male','female')),
    CONSTRAINT chk_mortality_rates_qx CHECK (qx >= 0 AND qx <= 1)
);
```

- `R__seed_mortality_rates.sql` seeds SSA period life table `qx`, both sexes, ages ~40–119, with a
  forced `qx = 1.0` at the terminal age so every sampled life is finite. Cite the SSA table year in
  the file header (as `R__seed_standard_deductions` cites its source).
- Persistence: `MortalityRateRepository` (Spring Data). Core: a `MortalityTable` value object loaded
  once and Caffeine-cached like the tax brackets (`findAllBySex` → `int age → double qx`). A blended
  ("unspecified" sex) column is derived in code as the mean of the two sexes' `qx` — a documented
  approximation so an absent `sex` degrades gracefully rather than erroring.

## 2. Mortality sampling

`MortalitySampler` (projection module, pure, seeded):

- Input: each person's `birthYear`, `sex` (or blended), the MC `baseYear`, the `MortalityTable`, and
  a seeded `Random`.
- **Draw one death age** given alive at the person's base-year age `a`: walk `k = a, a+1, …`; the
  death age is the first `k` where `rng.nextDouble() < qx[sex][k]`; forced at the terminal age. This
  samples the conditional survival distribution (standard life-table inverse-CDF).
- **Precompute per trial, in trial order**, exactly like `PortfolioPathGenerator`: `int[trials]`
  `primaryDeathAge` and `spouseDeathAge`, from the one mortality stream. Return-path draws and
  mortality draws use **separate** streams (distinct seed offsets) so neither perturbs the other's
  reproducibility.
- Single-person scenario with the toggle on: only the primary is sampled (degenerate — see §4).

## 3. Architecture — two-regime precompute + per-trial splice (Approach A)

The blocker A leaves behind: A **pre-splices** survivor income, ×factor floors, the MFJ→single
tables, and the transition/truncate indices **once**, valid only because death is fixed. B makes all
of those **per-trial**. Rather than recompute transforms in the hot loop (Approach C, drifts from
A's survivor-array code) or bucket every index (Approach B, O(years²)), B **precomputes each per-year
array in a small fixed set of regimes once, then splices per trial:**

- **Joint regime** (both alive): MFJ per-year tax tables, joint income, unscaled floors — today's
  "before transition" arrays.
- **Two survivor regimes**, keyed by *which* spouse survives (`survivor = primary` / `= spouse`):
  SINGLE per-year tables, that survivor's keep-larger-SS + survivor-`%` income, survivor's own
  age-based deductions. **Independent mortality means either spouse can be the survivor**, so both
  are needed; keep-larger identity is invariant to the transition year (common inflation factor
  preserves the SS ordering), so each survivor regime is computed once, not per year-of-death.
  A already builds *one* survivor regime — B generalizes that builder to emit the joint regime plus
  both survivor regimes.
- **Floors ×`survivor_factor`** is the same regardless of survivor identity, so it stays a cheap
  in-loop multiply from the transition index forward (A's `scaleFromTransition` primitive), not a
  third precomputed copy.

**Per trial**, from the sampled death ages: derive `transitionIdx` (first-death MC index, clamped to
0 if pre-window per A's HP3-Part-C rule), `truncateIdx` (`min(years, secondDeath+1)`), and
`survivorIsPrimary`. Then each year selects the regime by `y < transitionIdx ? joint : survivorRegime`,
and the loop ends at `truncateIdx`. The in-loop first-death **event** (rollover, per-owner step-up,
filing flip) already fires at `y == transitionYearIndex` in the A engine — B simply feeds it a
per-trial index instead of a fixed one.

**Component changes:**

- `HouseholdMcResolver` → produces the three regimes + a per-trial resolver (or a `MortalitySampler`
  the builder consumes) instead of one fixed `Resolved`. Its current single-`HouseholdSim` output
  becomes the toggle-off path (byte-identical anchor).
- `TrialSimulator.simulateTrial` gains per-trial `(transitionIdx, truncateIdx, survivorIsPrimary,
  survivorRegimeSelector)`; the existing `loopYears = min(years, truncateYearIndex)` and the
  `y == transitionYearIndex` event become per-trial values. Pools/step-up/rollover logic is unchanged.
- `OptimizationContextBuilder` builds the three regimes once and hands `TrialSimulator` the sampled
  per-trial death indices.

## 4. Success metrics & output

Per trial the engine already computes `essentialFloorMet` over the trial's own (now per-trial
truncated) loop — that **is** "floors funded while someone is alive." B aggregates three views:

- **Lifetime success** = fraction of trials with `essentialFloorMet == true`. Early-death trials
  count as success (you never fell short while alive).
- **Longevity-conditional success** = the same fraction, restricted to trials whose **survivor death
  age ≥ `longevity_conditional_age`** (default 95). Report the conditioning age *and* the qualifying
  trial fraction, so the base rate is visible.
- **Death-age distribution** — P10 / median / P90 of the sampled **second-death age** (and first-death
  age), from the per-trial arrays.

**Single-person + toggle on** (degenerate, supported for free): no transition, no regime splice; the
sampled primary death age sets `truncateIdx`, success = floors funded to that age. It is the
one-survivor case of the same sampler — modeling pure longevity risk without a spouse.

**Output DTO** — a `stochastic_mortality` block on the guardrail/projection MC response, present only
when the toggle is on:

```
stochastic_mortality: {
  lifetime_success_probability: number,
  longevity_conditional: { age: number, probability: number, trial_fraction: number },
  first_death_age:  { p10, median, p90 },
  second_death_age: { p10, median, p90 }
}
```

## 5. Frontend

- `ScenarioForm` household section: a "Model uncertain lifespans" toggle; per-spouse **Sex** selects
  (shown when the toggle is on; blended if left unset with a note); an advanced **longevity age**
  input (default 95).
- Results surface: the two probabilities side by side with copy distinguishing lifetime vs
  longevity-conditional, and a compact second-death-age P10/median/P90 readout. Reuses the existing
  MC success-rate component; the fixed-death success number remains for comparison.

## 6. Testing

- `MortalitySampler`: degenerate `qx` (`1.0` at a fixed age) ⇒ deterministic death age; same seed ⇒
  identical draws; sex column selection; blended fallback when sex absent; forced terminal death.
- **Statistical pin (seeded):** over N trials at a fixed seed, empirical mean sampled death age ≈ the
  life-table conditional expectancy within tolerance — a deterministic assertion, not a flaky one.
- **Regime splice:** a trial with a known transition/truncate/survivor triple selects the right
  regime arrays each year (joint before, correct survivor regime after, loop ends at truncate).
- **Success metrics:** early-death trial ⇒ lifetime success; longevity-conditional filters by
  survivor age and reports the right trial fraction; distribution percentiles.
- **Back-compat anchor (absolute):** toggle **off** ⇒ A's 6 goldens + MC char + adaptive-gate pins
  **byte-identical**; single-person paths untouched. This is the proof the sampler is truly bypassed.
- **New seed-pinned golden #7** (`household-stochastic-mortality`): a two-sex household with the
  toggle on and a fixed seed; pin `lifetime_success_probability`, the longevity-conditional triple,
  and the death-age percentiles (hand-verify against the seeded draws in the report).
- Persistence IT: `mortality_rates` seeds and round-trips both sexes.

## 7. Deferred / open

- Correlated (copula) mortality; health / smoker adjustments; user `qx` overrides.
- Optimizer re-driven under stochastic mortality (a possible sub-project C).
- Exposing `longevity_conditional_age` beyond the advanced control (fine as a default for v1).
