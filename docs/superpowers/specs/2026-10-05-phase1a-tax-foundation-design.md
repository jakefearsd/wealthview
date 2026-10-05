# Phase 1a — Tax Foundation — Design

**Date:** 2026-10-05
**Status:** Design approved in brainstorming. Pending written-spec review, then an implementation plan.
**Parent:** `2026-10-04-tactical-retirement-planning-roadmap.md`. Phase 1 is split into three sub-specs:
- **1a — this spec:** foundation.
- **1b:** healthcare, ACA and Medicare.
- **1c:** optimizer rework.

Each sub-spec goes through spec → plan → execution and lands green before the next starts.

## Problem statement

Every later phase recommends tax moves: conversions, gain harvesting, withdrawal mix, and staying under a cliff. Before any of that, three things must be true:

1. **The engines charge tax correctly.** Today:
   - A brokerage sale made to *pay* tax realizes a gain that is never taxed (D5).
   - The NIIT threshold is deflated on a different clock from the SS thresholds (D16).
2. **The engines know the dates that gate access.** Today 59½ is a whole-year "age < 60" proxy at nine call sites, so anyone born January–June is penalized one year too long.
3. **There is a reusable, per-year picture of tax space.** This means: how much room is left in each bracket and band, where the cliffs are, and the true marginal rate. 1b, 1c, Phase 3 (C2 explanations) and Phase 4 (action plan) all build on it.

1a also adds the **after-tax legacy value** that 1c's ranked objective needs. It also closes four small defects found in the gap analysis: D4, D7, D13 and D15.

## Decisions (from brainstorming)

| Area | Decision |
|---|---|
| Phase 1 structure | Three sub-specs (1a foundation, 1b healthcare, 1c optimizer), each spec → plan → execution. |
| Tax-space home | Pure core calculator, invoked by the deterministic engine each year using that year's realized figures. Returned as a `tax_space` list on the **run response**, not on `ProjectionYearDto`, so the goldens don't move. Shown in a new read-only **Tax Space** tab. 1c builds a fast `double` twin for the optimizer. |
| Golden-safe side channel | New `ProjectionEngine.runDetailed(input)` returns `ProjectionRunDetail(result, taxPictures, terminalValue)`. `run(input)` delegates to it and returns only `result`, so the golden test (which serializes `run()`'s output) is unaffected except by intentional behavior changes. |
| 59½ year | The calendar year in which the person reaches 59½ counts as **penalty-free**, on the tactical assumption that withdrawals are timed after the date. |
| No birth month | Legacy behavior exactly: early access starts at `birthYear + 60`. Existing scenarios and goldens do not change. |
| Early-access owner | Unchanged: the primary's date gates the aggregate check, as today. Per-owner early access moves to Phase 3 (A5, alongside Rule of 55). |
| Heir tax rate | Scenario field `heir_tax_rate`, default **24%**, range 0–50%. |
| Legacy valuation | Traditional × (1 − heir rate) + Roth + taxable at full value (basis steps up at death). Property equity is reported separately and unchanged. |
| IRMAA in the marginal rate | Not blended into the rate. Shown as a separate $ cliff with tier distance. |
| D8 (cash-bucket refill) | Moved to Phase 2 / B6, where the refill rule is designed properly. |

## Non-goals (1a)

- ACA, Medicare premiums, healthcare costs, and IRMAA in the MC (all 1b).
- Conversion optimizer changes and D1–D3, D9–D12, D14 (all 1c). The single exception: the 59½ change is applied mechanically inside `ConversionSimulator`, so the three code paths agree until 1c replaces it.
- A fast `double` tax-space twin for the MC and optimizer (1c).
- Per-owner early access, Rule of 55, Roth basis layers, and D6 (Phase 3).
- Effective-marginal-rate *charts* and "why" narratives (Phase 3 / C2). 1a ships a table only.
- Retirement *month*. `retirementDate.getYear()` stays the retirement boundary.
- Liquidation value (selling everything while alive, which needs taxable basis). Only the legacy value with step-up is in scope.

---

## 1. Tax-space calculator

### 1.1 Engine side: `YearTaxPicture`

During each retired year, `DeterministicProjectionEngine` records a `YearTaxPicture` once the year's taxes are final. This happens at the point where it already assembles `magiThisYear` (`DeterministicProjectionEngine.java` ~:490).

It is a new core record in `core/projection/dto`:

```java
public record YearTaxPicture(
        int year,
        FilingStatus filingStatus,
        int primaryAge, @Nullable Integer spouseAge,        // for the 65+ deduction adder
        BigDecimal ordinaryIncomeExSocialSecurity,          // other income, pension, wages, net rental (taxable), SE net
        BigDecimal socialSecurityBenefit,                   // gross benefits
        BigDecimal socialSecurityTaxable,                   // as the engine charged it
        BigDecimal traditionalDistributions,                // spend draw + RMD + gross-up slice
        BigDecimal rothConversion,
        BigDecimal ordinaryInterest,
        BigDecimal qualifiedDividendsAndLtcg,               // realized FIFO gain + qualified dividends (incl. D5 tax-sale gains)
        BigDecimal magi,
        BigDecimal netRentalIncome,                         // NII component
        BigDecimal chargedOrdinaryAndStateTax,              // what the engine charged, for reconciliation
        BigDecimal chargedLtcgTax,
        int yearsFromBase, BigDecimal inflationRate) { }
```

The run's `TaxCalculationStrategy` already holds state tax, itemizing and the household. It is passed to the calculator directly; it is **not** stored on the picture.

### 1.2 `TaxSpaceCalculator` (core, `core/projection/tax`)

`TaxSpaceCalculator` is a pure `@Component`. It has no state and no repository of its own; it reuses the calculators below.
- **Constructor:** `FederalTaxCalculator`, `CapitalGainsTaxCalculator`, `IrmaaSurchargeCalculator`. `SocialSecurityTaxCalculator` is static/stateless.
- **Method:** `TaxSpaceYear compute(YearTaxPicture p, TaxCalculationStrategy strategy, @Nullable HouseholdContext household)`.

`TaxSpaceYear` (core DTO, snake_case on the wire):

| Field group | Contents |
|---|---|
| Ordinary room | `marginal_ordinary_rate`. `bracket_room`: a list of `{rate, gross_ceiling, room}` for every bracket at or above the current one. `room` = gross ceiling − current gross ordinary income. The ceiling uses the **age-aware** standard deduction or itemized deduction, whichever the strategy picked. |
| Capital-gains room | `ltcg_zero_room` and `ltcg_fifteen_room`. Each is the room left in that band after ordinary taxable income stacks first and the year's realized gains are placed. Never negative. |
| Social Security | `provisional_income`, `ss_base_threshold`, `ss_upper_threshold` (deflated as the engine does), and `ss_inclusion_rate` — the extra taxable benefit per $1 of ordinary income: 0, 0.5 or 0.85. Null when there is no benefit. |
| NIIT | `niit_headroom` = deflated threshold − MAGI. Negative when over the threshold. |
| IRMAA | `irmaa_premium_year` = y + 2, `irmaa_tier` (0 = none), `irmaa_room_to_next_tier`, and `irmaa_next_tier_annual_cost` (the $ jump in surcharge across the household's Medicare-eligible members). All null unless someone is 65+ in y + 2. |
| Effective marginal rates | `effective_marginal_ordinary` = Δ total tax for +$1,000 of ordinary income ÷ 1,000. `effective_marginal_ltcg` = the same for +$1,000 of LTCG. "Total tax" = federal ordinary + state + SS inclusion + LTCG stacking + NIIT. IRMAA is excluded (see the IRMAA group). 1b adds lost ACA credit. |

**Supporting additions (small, TDD'd):**
- `FederalTaxCalculator.computeMaxIncomeForBracket(rate, year, status, age, secondAge)`: an age-aware overload. The existing overloads stay as they are; 1c retires their optimizer uses.
- `CapitalGainsTaxCalculator`: a `ltcgBandRoom(ordinaryTaxable, ltcgIncome, year, status)` helper.
- `SocialSecurityTaxCalculator`: a public `thresholds(status, yearsFromBase, inflation)` accessor. The hard-coded constants move to named fields.
- `IrmaaSurchargeCalculator`: a public `loadTiers(year, status)` accessor. Also fix the stale "×1 per person" Javadoc.
- A single shared deflator utility. It replaces the three copy-pasted `1/(1+i)^n` helpers in SS, NIIT and the LTCG calculator. This is the same change that fixes D16 (§4.4).

**Marginal method.** Re-price the year with ordinary income +$1,000. Social Security inclusion is recomputed from the new provisional income, and LTCG stacking is redone, both through the same calculators. This captures the SS torpedo and the 0%→15% push without any special-case logic.

### 1.3 Reconciliation guard

`TaxSpaceReconciliationTest` runs every golden input through `runDetailed()`. For each year it recomputes total ordinary + state + LTCG tax from the `YearTaxPicture` and asserts it equals `chargedOrdinaryAndStateTax + chargedLtcgTax` within $1.

**Listed exclusions:** the early-withdrawal penalty and SE tax. Both appear in `taxLiability` but are outside the tax-space model.

If this test fails, the calculator and the engine disagree. That is a defect, never a tolerance to widen.

**Known risk:** the engine's SS provisional income passes `PoolStrategy.getMagi()` (which returns the static `other_income` param) into `IncomeSourceProcessor` (:178, :196), alongside the B2 fixed-point portfolio income. If reconciliation shows the engine's provisional-income composition double-counts or omits a component, fix it in the engine in task 7, as its own `fix` commit with a documented golden delta. Do not mirror the quirk in the calculator.

### 1.4 Wire and UI

- **Plumbing:** `ProjectionRunResult` gains `List<TaxSpaceYear> taxSpace` and `TerminalValue terminalValue`, keeping the back-compat constructors. `ProjectionService.runProjection` calls `runDetailed()`, then computes tax space per picture. `ProjectionRunResponse` exposes `tax_space` and `terminal_value`.
- **Unchanged:** `/compare` keeps returning `ProjectionResultResponse`.
- **Frontend:** a `TaxSpaceYear` type in `frontend/src/types/projection.ts`, and a new `TaxSpaceTab.tsx` (template: `IncomeTaxTab`). It gets a new `TabId` in `ProjectionDetailPage` and shows only when `tax_space` is non-empty.
- **Tab columns:** year/age, MAGI, marginal bracket + room to top, 0% LTCG room, SS inclusion zone, NIIT headroom, IRMAA tier / room / premium year, effective marginal ordinary / LTCG.
- **Tab copy:** a one-line explainer per column, plus the existing tax disclaimer.

## 2. Birth month

### 2.1 Inputs

- **Fields:** `ScenarioParams` gains `birth_month` and `spouse_birth_month` (`Integer` 1–12, nullable), stored in `params_json`, so **no migration**.
- **Threading:** `ScenarioParamsSource` and `ScenarioRequest` (both back-compat constructors pad with null), `ScenarioParams.EMPTY`/`from`, and `ScenarioParamsParser`.
- **Validation:** `ScenarioCrudService` validates 1–12. A spouse month requires a spouse birth year.
- **Frontend:** a month select next to the birth-year input in `ScenarioBasicsSection` and next to the spouse birth year in `ScenarioHouseholdSection`. `handleSpouseBirthYearChange` clears the spouse month when the spouse is removed.

### 2.2 `AgeMilestones` (core, `core/projection/household`)

```java
public final class AgeMilestones {
    /** Calendar year the person reaches 59½; that year is penalty-free. Null month → birthYear + 60 (legacy). */
    public static int earlyAccessYear(int birthYear, @Nullable Integer birthMonth);
    /** 1-based month of the 65th birthday in calendar year birthYear+65; null month → 1 (legacy: whole year). Used by 1b. */
    public static int medicareStartMonth(int birthYear, @Nullable Integer birthMonth);
}
```

**Rule:** 59½ falls in `birthYear + 59` when `birthMonth ≤ 6`, else in `birthYear + 60`.

**Examples:**

| Birth | Reaches 59½ | `earlyAccessYear` |
|---|---|---|
| March 1970 | September 2029 | 2029 |
| September 1970 | March 2030 | 2030 |

`HouseholdContext.Person` gains `@Nullable Integer birthMonth`. Its factories keep their existing signatures, which pass null.

### 2.3 Call sites

Every `age < RetirementAges.EARLY_WITHDRAWAL_AGE` check becomes `year < earlyAccessYear`. The value is resolved once per run and passed in through each class's existing config or context.

| Site | Engine |
|---|---|
| `PoolStrategy` :1211 (10% penalty) | Deterministic |
| `WithdrawalOrderStrategy` :78 (DS pre-59½ taxable-only) | Deterministic |
| `TrialSimulator` :571, :766, :1045 | MC (via `SimulationConfig` / `SimulationParameters`) |
| `ConversionSimulator` :184, :374, :410 | Optimizer (via `RothConversionConfig`) |

- `RetirementAges.EARLY_WITHDRAWAL_AGE` is deleted. Its Javadoc moves to `AgeMilestones`.
- **Unchanged:**
  - RMD start (`RmdCalculator.rmdStartAge`) and the 65+ deduction depend on the calendar year only.
  - Income-source start/end ages and spending-tier ages stay whole-year.
- `GuardrailProfileService.scenarioSignature` includes both months (see D15).

## 3. After-tax legacy value

- **Input:** `ScenarioParams.heir_tax_rate` (`BigDecimal`, nullable). Resolved default 0.24 in `ScenarioParamsParser`. Validated 0–0.50. Threaded like birth month. UI: a percent input in `ScenarioTaxSection`.
- **Output:** `TerminalValue` (core DTO):

  ```java
  public record TerminalValue(int year, BigDecimal traditional, BigDecimal roth, BigDecimal taxable,
                              BigDecimal heirTaxRate, BigDecimal afterTaxLegacy, boolean atSecondDeath) { }
  ```

  - `afterTaxLegacy = traditional × (1 − heirTaxRate) + roth + taxable`.
  - Taken from the last projected year's `PoolBalances`. When the household's second death truncates the run (`DeterministicProjectionEngine` :336-346), that year is used and `atSecondDeath = true`.
- **UI:** an "After-tax Legacy" result card beside Final Balance on `ProjectionDetailPage`. Its tooltip states the valuation rule and the heir rate used.
- **Downstream:** 1c's legacy objective consumes the same rule. 1c adds an MC equivalent; per-pool terminal balances are not exported from trials today.

## 4. Defect fixes

### 4.1 D5 — tax-funding sales realize taxable gains (both engines)

**Today:**
- Deterministic: `PoolStrategy.deductFromPools` (:1701-1721) sells FIFO lots to pay tax and discards the gain.
- MC: `TrialSimulator` discards the gain on tax-payment sales (:589-593) and on conversion-tax sales (:1045-1050).

**Fix:**
- The realized gain from any tax-funding sale is added to the year's LTCG income and taxed through the normal stacking.
- The additional tax is itself funded the same way. Solve it like the C2 gross-up (`PoolStrategy` :1313-1405):
  - Warm start: `sale = tax / (1 − t·g)`, where `t` is the year's LTCG marginal rate and `g` is the gain fraction of the lots at the FIFO head.
  - Then at most 3 polish passes to a $1 tolerance.
  - The MC uses the same closed form in `double`, with one polish pass (the hot-loop budget).
- **Cash-reserve sales** (MC seed and refill) are also taxed. This is the same category of discarded gain.
- **Intentional deltas:**
  - All six goldens and `MonteCarloSpendingOptimizerCharacterizationTest` re-pin in the D5 commit.
  - The commit body lists before/after for the headline figures (final balance, lifetime tax, failure rate).
  - Expected direction: slightly lower balances and success.

### 4.2 D13 — reoptimize uses persisted conversion settings

- **Migration `V081__guardrail_profile_conversion_settings.sql`:**
  - `optimize_conversions boolean NOT NULL DEFAULT false`
  - `dynamic_sequencing_bracket_rate numeric(5,4)` (nullable)
  - Backfill: `UPDATE ... SET optimize_conversions = (conversion_schedule IS NOT NULL)`
- **Entity and service:** columns added to `GuardrailSpendingProfileEntity`. `populateGuardrailEntity` writes them. `reoptimize` (`GuardrailProfileService` :279) reads them, replacing the `traditionalExhaustionBuffer != null` inference.
- **Tests:**
  - Reoptimizing a profile created without conversions keeps them off.
  - Reoptimize preserves the user's saved phases. This guards the owner rule "never overwrite optimizer phases".

### 4.3 D15 — stale-plan signature completeness

- **Change:** `GuardrailProfileService.scenarioSignature` (:385-430) appends `filing_status`, `state`, `other_income`, `withdrawal_order`, `birth_month` and `spouse_birth_month`.
- **Consequence:** the signature is also the MC seed source (`deriveSeed`), so every existing profile reads as **stale once** after deploy. That is a flag only; no data is modified.
- **Release note:** to be added.
- **Test:** changing each newly included field flips staleness.

### 4.4 D16 — NIIT threshold deflation clock

- **Today:**
  - `LtcgTaxTable` (:231) deflates the NIIT threshold by the retirement-anchored `y`.
  - SS thresholds use the calendar-anchored `retirementYearOffsetFromBase + y` (`OptimizationContextBuilder` :282).
- **Fix:** both use calendar years from base, through the shared deflator from §1.2.
- **Testing:** the deterministic engine's NIIT path is checked for the same clock. A unit test pins the agreement when `baseYear ≠ retirementYear`.

### 4.5 D4 — conversion table Bracket column

`GuardrailResponseBuilder.buildConvScheduleResponse` (:380) fills `bracketUsed`:
- It reads the marginal rate at the year's taxable income + conversion from the year's `OrdinaryTaxTable` (`rateAt`).
- The value is formatted as a percent string, e.g. "12%".

The column is superseded in 1c, but it is cheap and visible now.

### 4.6 D7 — cash return default

`frontend/src/utils/optimizerConfig.ts` `defaultOptimizerConfig.cashReturnRate`: 4 → **1.5** (real), matching `GuardrailProfileService.DEFAULT_CASH_RETURN_RATE`. Profiles that already store a value keep it.

## 5. Testing

TDD throughout, per CLAUDE.md.

| Area | Tests |
|---|---|
| `TaxSpaceCalculator` | `@ParameterizedTest` fixtures, hand-worked against IRS 2025 tables. Ordinary bracket edges (single/MFJ, with and without 65+ adders, itemizing). 0% LTCG stacking: ordinary below, straddling and above the band. SS inclusion at 0 / 0.5 / 0.85. NIIT under and over the threshold. IRMAA tier boundaries and the premium-year label. Marginal rate across the torpedo zone and the 0%→15% LTCG push. |
| Reconciliation | `TaxSpaceReconciliationTest` over all goldens (§1.3). |
| Supporting calculators | Age-aware `computeMaxIncomeForBracket`. `ltcgBandRoom`. SS `thresholds`. IRMAA `loadTiers`. Shared deflator. |
| `AgeMilestones` | June vs July boundary, December, null month = legacy, `medicareStartMonth`. |
| Engines | Born March: penalty-free in the 59½ year, in deterministic, MC and conversion simulator. Born September and null month: unchanged from today. |
| Legacy value | End of horizon, second death inside the horizon, heir rate 0 and 0.5, default 0.24. |
| D5 | Deterministic and MC: tax-funding sale with gain is taxed, the fixed point converges, and a zero-gain lot gives no change. |
| D13 | V081 repository IT (Testcontainers), backfill, and the reoptimize tests (§4.2). |
| D15 / D16 / D4 | As stated in §4. |
| API | `ProjectionController` MockMvc: `tax_space` and `terminal_value` present and snake_case. Validation 400s for month and heir rate. |
| Frontend | `TaxSpaceTab` render and empty state. Birth-month and spouse-month fields, including the clear-on-spouse-removal behavior. Heir-rate field. Legacy card. Optimizer default cash rate. |
| Coverage | Module floors hold (core 90 / projection 90 / api 80). Raise a floor if coverage rises. |

Golden policy: goldens change **only** in the D5 commit (§4.1). Birth month defaults to null and tax space sits on the run response, so neither moves them.

## 6. Delivery order (≈14 tasks, size M–L)

1. `AgeMilestones` and birth-month inputs (backend: params, request, validation, parser, signature).
2. Engine call sites → `earlyAccessYear` (deterministic, MC, conversion simulator). Delete `EARLY_WITHDRAWAL_AGE`.
3. Frontend birth-month fields.
4. `heir_tax_rate` input, `TerminalValue`, and `ProjectionEngine.runDetailed()` / `ProjectionRunDetail`.
5. Supporting calculator additions and the shared deflator (§1.2).
6. `TaxSpaceCalculator` with its fixture suite.
7. Engine emits `YearTaxPicture`, and `TaxSpaceReconciliationTest`.
8. `ProjectionRunResult` / `ProjectionRunResponse` / `ProjectionService` plumbing and controller tests.
9. Frontend Tax Space tab, legacy card and heir-rate field.
10. D5 (both engines), re-pinning the goldens and characterization tests with documented deltas.
11. D13: V081, entity, reoptimize.
12. D15 signature.
13. D16 deflation clock.
14. D4 Bracket column and D7 cash default. Docs: `docs/reference/api-reference.md`, `docs/user-guide` retirement page, `backend/src/site/markdown/projection-engine.md`.

## 7. Module and convention notes

- **Module placement:** `YearTaxPicture`, `TaxSpaceYear`, `TerminalValue` and `ProjectionRunDetail` live in `wealthview-core` (`core/projection/dto`). `TaxSpaceCalculator` and `AgeMilestones` also live in core. The projection module depends on core, so the engine can build pictures and call nothing new across module boundaries.
- **Dependency rule:** `wealthview-api` touches only core types, consistent with the module rule.
- **Seeded tables:** none are new in 1a. The ACA, age-curve and Medicare tables arrive in 1b.
- **Migration:** V081 is the only migration.
- **Wire format:** snake_case JSON via the global naming strategy. Records for every new DTO. No floating point for money in core or the deterministic engine; the MC keeps its existing `double` hot loop.
- **Performance:** tax space adds about 3 tax evaluations per retired year to a deterministic run (base, +$1k ordinary, +$1k LTCG). That is negligible against the run itself. The MC is unaffected by tax space. D5 adds a closed-form step per tax-funding sale.
