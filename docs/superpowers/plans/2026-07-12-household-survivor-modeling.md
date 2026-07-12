# Household / Survivor Modeling (Sub-project A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Model a two-person household with fixed death ages and the full first-death transition (survivor SS, spousal rollover, basis step-up, survivor spending, MFJ→single flip) in both engines, with single-person scenarios byte-identical.

**Architecture:** A `HouseholdContext` resolved once per run threads through both engines; pools generalize from `{taxable, traditional, roth}` to owner-aware `{joint-taxable, trad-P, trad-S, roth-P, roth-S}`; one atomic transition event fires at the first-death year boundary. Spec: `docs/superpowers/specs/2026-07-12-household-survivor-modeling-design.md` (authoritative for all rules).

**Tech Stack:** Java 25 / Spring Boot 4.1 multi-module; PostgreSQL 16 + Flyway; React 18 + Vite + TS; Vitest; Testcontainers.

## Global Constraints

- **Back-compat anchor (absolute):** `spouse_birth_year == null` ⇒ every code path, number, and golden byte-identical to today. Existing 5 goldens + MC char are the proof; any movement there ⇒ the task is wrong.
- Owners: accounts `primary | spouse | joint` (`joint` ⇒ taxable only; trad/roth individually owned); income sources `primary | spouse` + `survivor_percent` (default 1.0; **ignored for SS-typed sources** — statutory keep-larger applies, compared at **transition-year effective real amounts**).
- Transition order (atomic, at transitionYear boundary): income → rollover → step-up (joint: ×0.5, ×1.0 if `community_property`; deceased-owned taxable: full) → spending ×`survivor_spending_factor` → filing single. Truncate projection at second death if within horizon (final balance = bequest).
- Two RMD streams while both alive: each owner's traditional RMDs at that owner's SECURE-2.0 age (`RmdCalculator.rmdStartAge(ownerBirthYear)`) on that owner's prior-year balance. After rollover: survivor's age/table on the merged pool.
- Defaults: `survivor_spending_factor` 0.75 (0.5–1.0); death ages from the embedded SSA cohort table (editable 50–120); `community_property` false.
- Per-person thresholds while both alive: age-65 additional deduction and IRMAA surcharge apply **per person ≥65** (×2 when both).
- All the remediation-era machinery is filing-status-parameterized per year (exact tax tables, IRMAA tiers V075, deductions V074, NIIT/SS thresholds) — the flip = build year-tables MFJ before transition, single after. Never fork tax logic.
- Snake_case wire; additive migrations only (never edit committed V-files); records/constructor injection/BigDecimal money/no wildcard imports; coverage floors hold; commit per task on `main`; NEVER push.
- Withdrawal orders operate per type; within a type, draw **proportionally by owner balance** (document at the seam).
- Guardrail signature/staleness gains every household field.

---

## File Structure (created/modified per task; exact paths in tasks)

- Core new: `projection/household/HouseholdContext.java`, `PersonId.java`, `LifeExpectancy.java`, `dto/HouseholdInput.java` (+ transition logic lives inside the engines at the year seam, not a separate service — it needs pool internals).
- Persistence: `V078__add_owner_to_projection_accounts.sql`, `V079__add_owner_survivor_to_income_sources.sql`, entity fields.
- Core plumbing: `ScenarioRequest`/`ScenarioParamsSource`/`ScenarioParams`/`ScenarioParamsParser`/`ScenarioCrudService`/`GuardrailProfileService` (signature), `CreateProjectionAccountRequest`/`ProjectionAccountResponse` (+owner), income-source request/response (+owner, +survivor_percent), `ProjectionInputBuilder`.
- Engines: `PoolStrategy` (owner-keyed pools + transition), `YearFinanceResolver`, `DeterministicProjectionEngine` (truncation, context threading), `TrialSimulator` + `OptimizationContextBuilder` + `SimulationConfig` (5 pools, transition, per-year filing), `SustainabilitySearch`/`GuardrailResponseBuilder` (floors×factor).
- Frontend: `ScenarioForm` household section + owner selectors, `IncomeSourcesPage` owner/survivor fields, types.
- Tests: per task below + `EngineInvariantsTest` household case + golden #6 `household-survivor`.

---

### Task 1: HouseholdContext + SSA life-expectancy defaults (core, pure)

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/household/PersonId.java`, `HouseholdContext.java`, `LifeExpectancy.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/household/HouseholdContextTest.java`, `LifeExpectancyTest.java`

**Interfaces (Produces — later tasks depend on these exact shapes):**
```java
public enum PersonId { PRIMARY, SPOUSE }

/** Resolved once per run. Single-person: spouse==null, transitionYear/secondDeathYear empty. */
public record HouseholdContext(
        Person primary,
        @Nullable Person spouse,
        Optional<Integer> transitionYear,      // calendar year of FIRST death (empty if single or beyond horizon)
        Optional<Integer> secondDeathYear,     // calendar year of SECOND death (empty if single or beyond horizon)
        @Nullable PersonId survivor) {         // null when single-person
    public record Person(PersonId id, int birthYear, int deathAge) {
        public int deathYear() { return birthYear + deathAge; }
        public int ageIn(int calendarYear) { return calendarYear - birthYear; }
    }
    public boolean isHousehold() { return spouse != null; }
    public boolean bothAliveIn(int year) { ... }          // household && year < transitionYear
    public boolean isAliveIn(PersonId p, int year) { ... }
    public static HouseholdContext single(int birthYear) { ... }  // degenerate, used by every existing path
    public static HouseholdContext of(int primaryBirthYear, int primaryDeathAge,
                                      int spouseBirthYear, int spouseDeathAge,
                                      int horizonEndYear) { ... } // clamps transition/second-death to horizon
}

public final class LifeExpectancy {
    /** SSA 2021 period-life sex-neutral planning default by birth-decade cohort; clamped 50..120. */
    public static int defaultDeathAge(int birthYear) { ... }
}
```
`LifeExpectancy` table (constants, cite SSA 2021 in Javadoc): birth ≤1940→84, 1941–1950→85, 1951–1960→86, 1961–1970→87, 1971–1980→88, 1981–1990→89, ≥1991→90.

- [ ] **Step 1: failing tests** — `HouseholdContextTest`: `of(1958, 85, 1966, 90, 2065)` ⇒ transitionYear 2043 (primary dies first: 1958+85), survivor SPOUSE, secondDeathYear 2056; `bothAliveIn(2042)` true / `(2043)` false; death beyond horizon ⇒ empty optionals (`of(1958, 120, 1966, 120, 2050)`); `single(1970)` ⇒ !isHousehold, empty optionals. `LifeExpectancyTest`: 1955→86, 1990→89, clamp check.
- [ ] **Step 2: run — FAIL** (`mvn -q -pl wealthview-core test -Dtest=HouseholdContextTest,LifeExpectancyTest`).
- [ ] **Step 3: implement** the records/constants exactly as above (tie-break: if both die the same year, survivor = the YOUNGER person — document; transition still fires once).
- [ ] **Step 4: run — PASS.**
- [ ] **Step 5: gate + commit** `mvn -q -pl wealthview-core -am verify -DskipITs -B`; `feat(core): add HouseholdContext and SSA life-expectancy defaults`.

### Task 2: Schema — account owner + income-source owner/survivor_percent

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V078__add_owner_to_projection_accounts.sql`, `V079__add_owner_survivor_to_income_sources.sql`
- Modify: `ProjectionAccountEntity` (+`owner` String get/set), `IncomeSourceEntity` (+`owner`, +`survivorPercent`)
- Test: extend the persistence repositories IT with round-trips.

**SQL (exact):**
```sql
-- V078: per-owner projection accounts for household modeling (spec 2026-07-12).
ALTER TABLE projection_accounts
    ADD COLUMN IF NOT EXISTS owner text NOT NULL DEFAULT 'primary';
ALTER TABLE projection_accounts
    ADD CONSTRAINT chk_projection_accounts_owner CHECK (owner IN ('primary','spouse','joint'));
```
```sql
-- V079: income-source ownership + survivor continuation for household modeling.
ALTER TABLE income_sources
    ADD COLUMN IF NOT EXISTS owner text NOT NULL DEFAULT 'primary';
ALTER TABLE income_sources
    ADD CONSTRAINT chk_income_sources_owner CHECK (owner IN ('primary','spouse'));
ALTER TABLE income_sources
    ADD COLUMN IF NOT EXISTS survivor_percent numeric(5,4) NOT NULL DEFAULT 1.0;
ALTER TABLE income_sources
    ADD CONSTRAINT chk_income_sources_survivor_percent CHECK (survivor_percent >= 0 AND survivor_percent <= 1);
```
- [ ] Steps: failing IT (save/load owner+survivorPercent; default 'primary'/1.0 on legacy insert) → V078/V079 + entity fields → IT green → `mvn -q -pl wealthview-persistence verify -B` (Testcontainers proves both apply) → commit `db(persistence): add account owner and income-source survivor fields`.

### Task 3: Request/params plumbing + validation + staleness

**Files:**
- Modify: `ScenarioRequest`, `ScenarioParamsSource`, `ScenarioParams` (+`EMPTY`/`from`), `ScenarioParamsParser`, `ScenarioCrudService` (validation + persist owner), `CreateProjectionAccountRequest`/`ProjectionAccountResponse` (+`owner`), income-source create/update/response records (+`owner`,+`survivor_percent`), `GuardrailProfileService.scenarioSignature` (+all household fields), `ProjectionInputBuilder` (thread `HouseholdContext` + account owner into inputs — engines consume in later tasks; until then context is built and passed but pools ignore SPOUSE ownership).
- Test: extend `ScenarioParamsTest`, `ScenarioParamsParserTest`, `ScenarioCrudServiceTest`, `GuardrailProfileServiceTest` signature tests.

**Fields (scenario params, snake_case wire):** `spouse_birth_year` (Integer), `primary_death_age`/`spouse_death_age` (Integer, null ⇒ `LifeExpectancy.defaultDeathAge`), `survivor_spending_factor` (BigDecimal, null ⇒ 0.75; validate 0.5–1.0), `community_property` (Boolean, null ⇒ false). Validation in `ScenarioCrudService` beside `validateFeeRate`: death ages 50–120; `spouse_*` fields require `spouse_birth_year`; account owner enum + joint-taxable-only rule; survivor_percent 0–1.

- [ ] Steps (TDD per field-group, mirror the fee_rate pattern exactly): failing params tests (present passes through; absent defaults; invalid throws with message) → implement → failing signature tests (each new field changes the hash; account-owner change changes it) → wire → failing CRUD tests (owner persisted; joint+traditional rejected 400-path; income survivor_percent round-trip) → implement → gate `mvn -q -pl wealthview-core,wealthview-api -am verify -DskipITs -B` (fix ALL `ScenarioRequest`/record call sites — expect ~30, mechanical) → commit `feat(core): household scenario fields, account owner, income survivor plumbing`.
- [ ] **Back-compat check:** run the full core+api suite; every pre-existing test green with zero edits except constructor arity (null-filled).

### Task 4: Deterministic owner-aware pools + two RMD streams (opus-tier)

**Files:**
- Modify: `backend/wealthview-projection/.../PoolStrategy.java` (MultiPool internals), `ProjectionInputBuilder` (account owner → pool assembly), `YearFinanceResolver`/`DeterministicProjectionEngine` (pass `HouseholdContext`; per-owner RMD inputs)
- Test: `MultiPoolOwnerTest` (new), extend `DeterministicProjectionEngineWithdrawalTest`.

**Contract (the implementer reads the live file; these are the binding requirements):**
- Internal pool state becomes owner-keyed: joint `TaxableLotsBd` (unchanged), `traditional[PRIMARY|SPOUSE]`, `roth[PRIMARY|SPOUSE]` (`EnumMap<PersonId, BigDecimal>`). ALL existing public accessors (`getTraditional()` etc.) return the SUM — every existing caller compiles and behaves identically.
- Single-person: SPOUSE entries absent/zero; every arithmetic path reduces to today's — the 5 existing goldens + full suite are the proof (byte-identical, no re-pins permitted in this task).
- Contributions route by the account's owner. Within-type draws split proportionally by owner balance (document at the seam); tax cascade unchanged in order (taxable→traditional(sum, split pro-rata)→roth(same)).
- RMD: `computeYearRmd(owner)` per owner — owner's `rmdStartAge(ownerBirthYear)`, owner's prior-year-end balance (snapshot per owner), each stream force-out/tax/reinvest exactly like today's single stream; `rmd_amount` DTO field = sum of streams.
- Tests (pin exact): age-gap fixture birth 1958/1966, both alive, year where primary (67? no — 1958+73=2031) is 73 but spouse is 65 ⇒ ONLY primary's stream fires, amount = primary's prior trad balance / 26.5 (oracle `RmdCalculator`); later year both ≥ RMD age ⇒ both streams, DTO shows the sum; contributions to spouse-owned account grow spouse's pool only; single-person: existing pinned tests untouched.
- [ ] TDD steps as usual; gate `mvn -q -pl wealthview-projection,wealthview-core -am verify -DskipITs -B` + explicit `ProjectionGoldenFileTest` 5/5 byte-check; commit `feat(projection): owner-aware deterministic pools with per-owner RMD streams`.

### Task 5: Deterministic transition event + truncation (opus-tier)

**Files:**
- Modify: `PoolStrategy` (transition application on pools/lots), `YearFinanceResolver`/`IncomeSourceProcessor` seam (survivor income rules), `DeterministicProjectionEngine` (fire at boundary; truncation; filing flip via year-context), spending application (`RetirementWithdrawalProcessor`/plan resolution ×factor).
- Test: `HouseholdTransitionTest` (new, engine-level, pinned dates).

**Contract — the six steps in spec order, one boundary, idempotent-once:**
1. Income: SS keep-larger at transition-year effective real amounts (both orderings tested); non-SS deceased-owned × `survivor_percent` (0 ⇒ ends; source end_age still honored vs the SURVIVOR-relevant owner ages — a deceased owner's source that survives becomes survivor-attached for age windows; document).
2. Rollover: `traditional[SURVIVOR] += traditional[DECEASED]; traditional[DECEASED]=0` (same roth). Conservation pin: total before == after.
3. Step-up: joint lots `basis += (value−basis)×factor` (0.5 / 1.0 community) — use a `TaxableLotsBd.stepUp(BigDecimal factor)` method (add it, unit-tested: per-lot arithmetic, full=basis:=value).
4. Spending ×factor from this year (floors, discretionary, tier amounts — at the plan-resolution seam so ALL strategies inherit).
5. Filing single from this year: the year tax context (brackets/deduction/LTCG/NIIT/SS tiers/IRMAA) built with SINGLE for `year >= transitionYear` — thread through the existing per-year construction, never special-case inside calculators.
6. Per-person→single thresholds: age-65 adder and IRMAA revert to ×1 (survivor only) — falls out of Task 7's per-person logic + the flip.
- Truncation: `secondDeathYear` within horizon ⇒ last projected year = that year; `ProjectionResult` years list ends there (final balance = bequest); test pins the length + final balance.
- Engine-level pinned test (the mini-golden): couple 1958/1966, primary dies 2043; assert year-2043 row: SS switches to max, pension×0.5, taxLiability computed on SINGLE brackets (oracle: independent FederalTaxCalculator single-status call), basis stepped (verify via later-year capitalGainsTax drop vs no-step-up run), spending×0.75.
- [ ] TDD; goldens 5/5 byte-check again; gate; commit `feat(projection): first-death transition event and second-death truncation`.

### Task 6: MC generalization (opus-tier)

**Files:**
- Modify: `TrialSimulator` (pools `double[3]`→`double[5]` layout `{JOINT_TAXABLE=0, TRAD_P=1, TRAD_S=2, ROTH_P=3, ROTH_S=4}` — constants, not magic numbers), `OptimizationContextBuilder`/`SimulationConfig` (owner balances/basis, household years, per-year filing status for table construction, survivor factors), `GuardrailResponseBuilder` (floors×factor — Task 8 finishes reporting).
- Test: extend `TrialSimulatorReturnTest` + new `TrialSimulatorHouseholdTest`.

**Contract:** identical economics to Tasks 4–5 in double-land: per-owner RMD streams (per-owner prior-year snapshots); transition at the trial-year boundary (income arrays pre-transformed at PRECOMPUTE time — income[y] already reflects keep-larger/survivor% since death year is fixed: do it in `IncomeProjector`, NOT in the hot loop); rollover = `pools[TRAD_S]+=pools[TRAD_P]... per survivor` (one-time, year check); step-up on the `TaxableLots` mirror (add `TaxableLots.stepUp(double factor)`, unit-tested); spending arrays pre-scaled ×factor from transition (floors/discretionary at build time — hot loop untouched); per-year exact tax tables built MFJ→single at the flip (T12 tables are per-year — just build with the right status). Trials truncate at min(horizon, secondDeathYear). Single-person: pools[2]=pools[4]=0 and every branch reduces — MC char + all existing pins byte-identical (no re-pins).
- Composition/telescoping test updated for two RMD streams (order: base+interest → RMD-P → RMD-S → conversion → draw; total order-invariant).
- [ ] TDD; char 6/6 + adaptive-gate pins unchanged; gate; commit `feat(projection): household-aware Monte Carlo trials`.

### Task 7: Per-person thresholds while both alive

**Files:**
- Modify: the age-65 deduction seam (`FederalTaxCalculator` age-aware overload call sites — pass BOTH ages when household+MFJ: adder × count(person ≥65 alive)), IRMAA application (`DeterministicProjectionEngine` surcharge site: × count(person ≥65 alive that year); MC precompute likewise), SS convergence context (already combined pre-death via B2; verify survivor-only + single tiers post-death — Task 5 threads status, this task pins it).
- Test: extend the age-65/IRMAA test classes with household cases.

Pinned cases: both 66 MFJ 2025 ⇒ deduction 31,500 + 2×1,600; one 66/one 63 ⇒ +1×1,600; IRMAA both ≥65 crossing tier 1 ⇒ 2×(74.00+13.70)×12; post-transition ⇒ ×1 on single tiers. Single-person anchors untouched.
- [ ] TDD; gate; commit `feat(core,projection): per-person age-65 deduction and IRMAA while both spouses alive`.

### Task 8: Optimizer/guardrails household integration

**Files:**
- Modify: `SustainabilitySearch` (floors already arrive scaled from Task 6's precompute — verify search/gate/adaptation coherence over the scaled schedule; corridor derivation from scaled plan), `GuardrailResponseBuilder` (yearly rows show post-transition floors/spending), staleness/signature (Task 3 added fields — add an integration test proving an edit to spouse_death_age stales a persisted profile).
- Test: optimizer household integration test — stressed household fixture: recommended spending reflects the factor drop at transition; `success_probability`/`with_rules` both computed; reproducibility.
- [ ] TDD; gate; commit `feat(projection): household-aware guardrail optimization`.

### Task 9: Frontend household surface

**Files:**
- Modify: `frontend/src/types/projection.ts` (+ all household fields), `ScenarioForm.tsx` (collapsible "Spouse / Household" section: spouse birth year enables death ages [placeholder = server SSA default, shown after first save/echo], survivor spending factor %, community property checkbox; account rows gain Owner select gated on household; joint only for taxable), `IncomeSourcesPage.tsx` (+ Owner select + Survivor % [hidden for social_security type with a "statutory survivor rule applies" note]), api serializers.
- Test: Vitest — household section toggles on spouse birth year; owner select round-trips; joint hidden for non-taxable; survivor% hidden for SS; serialize/hydrate all fields (incl. null ⇒ absent).
- [ ] TDD; `npm run test && npm run typecheck && npm run lint`; commit `feat(frontend): household and survivor inputs`.

### Task 10: Golden #6 + invariant matrix + full verify

**Files:**
- Create: `backend/wealthview-projection/src/test/resources/golden/household-survivor-input.json` + generated `household-survivor.json`; extend `ProjectionGoldenFileTest` `@ValueSource`.
- Modify: `EngineInvariantsTest` — household `ScenarioCase` (balance identity across the transition boundary: rollover+step-up conserve; only tax/spending flows change; two-stream RMD oracle inside the matrix).

Golden fixture: births 1958/1966, retirement 2030, horizon to 2066, primary death 2043 (85), spouse 2056 (90) [truncates 2056], SS pair 38k/22k (keep-larger exercised), pension 30k owner=primary survivor 50%, joint taxable $800k basis $500k (step-up visible in post-2043 capitalGainsTax), trad P $900k / S $400k (two streams then merge), spending profile with tiers, moderate confidence.
- [ ] Generate via `-Dupdate.golden=true`; SANITY-REVIEW per spec (transition-year row shows every cliff component; document 6-8 verified values in the report); existing 5 goldens byte-identical; invariant matrix green.
- [ ] **Full verify:** `mvn -q clean install -DskipITs -B` + `mvn -q verify -pl wealthview-app -B` + frontend suite — all green.
- [ ] Commit `test(projection): household-survivor golden and invariants`.

---

## Self-Review

- **Spec coverage:** every spec section maps: §1→T2/T3/T9; §2→T1; §3→T4/T6; §4→T5/T6; per-person→T7; §5→T8; §6→T10. SSA table→T1; truncation→T5/T6; signature→T3/T8. No gaps found.
- **Placeholders:** none — generalization tasks carry binding contracts + pinned tests instead of full-file listings (house pattern for large-seam refactors; implementers read live files).
- **Type consistency:** `PersonId`/`HouseholdContext.Person`/`LifeExpectancy.defaultDeathAge`/`stepUp(factor)` names used identically across T1/T4/T5/T6; pool index constants named in T6 only (MC-local).
