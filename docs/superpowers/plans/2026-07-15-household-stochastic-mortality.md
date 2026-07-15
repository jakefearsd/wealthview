# Household / Survivor Modeling (Sub-project B: stochastic mortality) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in stochastic-mortality Monte Carlo mode that samples each spouse's death year per trial from a sex-specific SSA life table, so the projection reports a longevity-aware success probability (lifetime + longevity-conditional) and a death-age distribution — reusing sub-project A's first-death transition machinery unchanged.

**Architecture:** A seeded `MortalitySampler` precomputes per-trial death ages (a separate RNG stream from the return paths). `OptimizationContextBuilder` builds each per-year array in three regimes once — joint (both alive) + two survivor regimes keyed by which spouse survives — and `TrialSimulator` splices joint→survivor at each trial's sampled transition index, looping to that trial's truncate index. A's in-loop first-death event (rollover, per-owner step-up, MFJ→single flip, ×factor spending) fires at the per-trial index. Toggle off ⇒ byte-identical to A. Spec: `docs/superpowers/specs/2026-07-15-household-stochastic-mortality-design.md` (authoritative for all rules).

**Tech Stack:** Java 25 / Spring Boot 4.1 multi-module; PostgreSQL 16 + Flyway; React 18 + Vite + TS; Vitest; Testcontainers; JUnit 5 + Mockito + AssertJ.

## Global Constraints

- **Back-compat anchor (absolute):** `stochastic_mortality != true` ⇒ every code path, number, and golden byte-identical to A today. A's 6 goldens + MC char + adaptive-gate pins are the proof; any movement there ⇒ the task is wrong. Single-person paths are likewise untouched whenever the toggle is off.
- **Separate RNG stream:** mortality draws use `new Random(seed + MORTALITY_SEED_OFFSET)` (a distinct constant, NOT `+1` which `JointConversionSearch` already uses). Constructing/consuming it must never touch the return-path `Random`, so toggle-off return sequences are bit-identical. Null seed ⇒ fresh `Random` (non-reproducible), matching today.
- **Independent mortality** for v1: each spouse's death sampled from its own `qx` independently.
- Reference data (`qx`) is authoritative SSA 2021 period life table data — cite the source in the seed file header; transcribe values, never invent plausible-looking ones.
- Scenario fields are additive `params_json` (snake_case wire): `stochastic_mortality` (Boolean), `primary_sex` / `spouse_sex` (String `male|female`), `longevity_conditional_age` (Integer, default 95). NO column migration for these.
- Snake_case wire; additive Flyway only (never edit committed V-files); records/constructor injection/BigDecimal money in core & api/no wildcard imports; coverage floors hold (core/projection 90% line, 0.83/0.84 branch); commit per task on `main`; **NEVER push**.
- Guardrail signature/staleness (`GuardrailProfileService.scenarioSignature`) gains every new field.

---

## File Structure

- **Persistence (new):** `db/migration/V080__create_mortality_rates.sql`, `db/migration/R__seed_mortality_rates.sql`, `.../persistence/projection/MortalityRateEntity.java`, `MortalityRateRepository.java`.
- **Core (new):** `core/projection/mortality/MortalityTable.java` (value object), `core/projection/mortality/MortalityTableProvider.java` (loads + caches).
- **Core (modified):** `ScenarioParams`, `ScenarioParamsSource`, `ScenarioParamsParser`, `ScenarioRequest`, `ScenarioCrudService` (validation + signature fields), `GuardrailProfileService.scenarioSignature`, `GuardrailOptimizationInput` (+4 fields), `ProjectionInputBuilder` (load table when toggle on).
- **Projection (new):** `projection/MortalitySampler.java`, `projection/StochasticMortalitySummary.java` (aggregation), plus a `MortalityDraws` per-trial record.
- **Projection (modified):** `OptimizationContextBuilder` (three-regime precompute + sampler wiring), `TrialSimulator` (per-trial splice + truncate + summary hooks), `HouseholdMcResolver` (regime factory), the guardrail/projection MC response builder + response DTO.
- **Frontend:** `types/projection.ts`, `ScenarioForm.tsx`, the MC results component, api serializers, co-located `.test.tsx`.
- **Tests:** per task + golden #7 `household-stochastic-mortality`.

---

### Task 1: mortality_rates schema, seed, and repository (persistence)

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V080__create_mortality_rates.sql`
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/R__seed_mortality_rates.sql`
- Create: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/projection/MortalityRateEntity.java`, `MortalityRateRepository.java`
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/projection/MortalityRateRepositoryIntegrationTest.java`

**Interfaces (Produces):**
```java
// MortalityRateRepository
List<MortalityRateEntity> findAllBySexOrderByAgeAsc(String sex);
// MortalityRateEntity getters: getSex():String, getAge():int, getQx():BigDecimal
```

- [ ] **Step 1: Write the failing IT.** `@DataJpaTest` + `@Testcontainers` + `@AutoConfigureTestDatabase(replace = NONE)` extending `AbstractIntegrationTest`. Assert `findAllBySexOrderByAgeAsc("female")` returns rows ascending by age, the first age ≤ 40, `qx` of the last row equals `1.0` (forced terminal death), and every `qx` in `[0,1]`. Assert `male` and `female` both return non-empty and differ at age 70 (`male.qx > female.qx`).

- [ ] **Step 2: Run — FAIL** (no table/entity).
`cd backend && mvn -q -pl wealthview-persistence verify -Dtest=MortalityRateRepositoryIntegrationTest -B`
Expected: compile failure / no such table.

- [ ] **Step 3: Write the migration.**
```sql
-- V080: sex-specific SSA period-life mortality rates for the stochastic-mortality Monte Carlo
-- (spec 2026-07-15). qx = P(death within the year | alive at exact age). Seeded by R__seed_mortality_rates.
CREATE TABLE IF NOT EXISTS mortality_rates (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sex        text NOT NULL,
    age        integer NOT NULL,
    qx         numeric(9,8) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_mortality_rates_sex_age UNIQUE (sex, age),
    CONSTRAINT chk_mortality_rates_sex CHECK (sex IN ('male','female')),
    CONSTRAINT chk_mortality_rates_qx CHECK (qx >= 0 AND qx <= 1)
);
```

- [ ] **Step 4: Write the repeatable seed.** Transcribe the SSA 2021 Period Life Table (Actuarial Study; https://www.ssa.gov/oact/STATS/table4c6.html) male & female `qx`, ages 40–119, plus a forced terminal row `age = 120, qx = 1.0` for each sex. Header cites the source and year (as `R__seed_standard_deductions.sql` does). Use `INSERT ... ON CONFLICT (sex, age) DO UPDATE SET qx = EXCLUDED.qx, updated_at = now();` so re-seeding is idempotent. Example shape (values illustrative — replace with transcribed SSA figures):
```sql
-- R__seed_mortality_rates: SSA 2021 Period Life Table qx by sex and age. Repeatable: re-runs on checksum change.
INSERT INTO mortality_rates (sex, age, qx) VALUES
  ('male', 40, 0.00214), ('female', 40, 0.00128),
  -- ... ages 41..118 transcribed from SSA table 4.C6 ...
  ('male', 119, 0.95000), ('female', 119, 0.95000),
  ('male', 120, 1.00000), ('female', 120, 1.00000)
ON CONFLICT (sex, age) DO UPDATE SET qx = EXCLUDED.qx, updated_at = now();
```

- [ ] **Step 5: Write the entity + repository.**
```java
@Entity
@Table(name = "mortality_rates")
public class MortalityRateEntity {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false) private String sex;
    @Column(nullable = false) private int age;
    @Column(nullable = false) private BigDecimal qx;
    // protected no-arg ctor; getters only (reference data is read-only in the app)
}

public interface MortalityRateRepository extends JpaRepository<MortalityRateEntity, UUID> {
    List<MortalityRateEntity> findAllBySexOrderByAgeAsc(String sex);
}
```

- [ ] **Step 6: Run — PASS.** `mvn -q -pl wealthview-persistence verify -Dtest=MortalityRateRepositoryIntegrationTest -B` (Testcontainers proves V080 + seed apply). Expected: PASS.

- [ ] **Step 7: Commit.** `db(persistence): add mortality_rates table and SSA life-table seed`

---

### Task 2: MortalityTable value object + cached provider (core)

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/mortality/MortalityTable.java`, `MortalityTableProvider.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/mortality/MortalityTableTest.java`, `MortalityTableProviderTest.java`

**Interfaces (Produces):**
```java
public record MortalityTable(Map<Integer, Double> maleQx, Map<Integer, Double> femaleQx) {
    /** qx for the given sex at exact age. sex null/unknown ⇒ blended (mean of male & female). Ages
     *  above the table's max return 1.0 (forced death); below the min return the min-age qx. */
    public double qx(@Nullable String sex, int age);
    public int maxAge();  // highest tabulated age (terminal); used to bound the sampling walk
}

@Service
public class MortalityTableProvider {
    /** Loads both sexes from the repository once and caches (Caffeine, like the tax-bracket cache). */
    public MortalityTable load();
}
```

- [ ] **Step 1: Write `MortalityTableTest` (failing).**
```java
@Test
void qx_femaleAt70_returnsSeededValue() {
    var table = new MortalityTable(Map.of(70, 0.02, 120, 1.0), Map.of(70, 0.012, 120, 1.0));
    assertThat(table.qx("female", 70)).isEqualTo(0.012);
}

@Test
void qx_nullSex_returnsBlendedMean() {
    var table = new MortalityTable(Map.of(70, 0.02, 120, 1.0), Map.of(70, 0.012, 120, 1.0));
    assertThat(table.qx(null, 70)).isEqualTo(0.016);   // (0.02 + 0.012) / 2
}

@Test
void qx_ageAboveMax_forcesDeath() {
    var table = new MortalityTable(Map.of(120, 1.0), Map.of(120, 1.0));
    assertThat(table.qx("male", 130)).isEqualTo(1.0);
}
```

- [ ] **Step 2: Run — FAIL.** `mvn -q -pl wealthview-core test -Dtest=MortalityTableTest -B`. Expected: does not compile.

- [ ] **Step 3: Implement `MortalityTable`.**
```java
public double qx(@Nullable String sex, int age) {
    if (age > maxAge()) {
        return 1.0;
    }
    if ("male".equals(sex)) {
        return lookup(maleQx, age);
    }
    if ("female".equals(sex)) {
        return lookup(femaleQx, age);
    }
    return (lookup(maleQx, age) + lookup(femaleQx, age)) / 2.0;   // blended fallback
}
private static double lookup(Map<Integer, Double> qx, int age) {
    Double v = qx.get(age);
    if (v != null) {
        return v;
    }
    int min = qx.keySet().stream().min(Integer::compareTo).orElseThrow();
    return age < min ? qx.get(min) : 1.0;
}
public int maxAge() {
    return Math.max(maleQx.keySet().stream().max(Integer::compareTo).orElse(0),
                    femaleQx.keySet().stream().max(Integer::compareTo).orElse(0));
}
```

- [ ] **Step 4: Run — PASS.** Expected: PASS.

- [ ] **Step 5: Write `MortalityTableProviderTest` (failing).** Mock `MortalityRateRepository`; `findAllBySexOrderByAgeAsc("male")` → two entities (age 70 qx 0.02, age 120 qx 1.0), female likewise. Assert `load().qx("male", 70) == 0.02` and that a second `load()` call does NOT hit the repository again (`verify(repo, times(1))` per sex — cache proof).

- [ ] **Step 6: Run — FAIL,** then **implement** `MortalityTableProvider`: constructor-inject `MortalityRateRepository`; `load()` builds the two maps and memoizes (Caffeine `@Cacheable` on a named cache, or a lazily-populated `volatile MortalityTable` field — match the existing tax-bracket caching idiom in this module). **Step 7: Run — PASS.**

- [ ] **Step 8: Commit.** `feat(core): add MortalityTable value object and cached provider`

---

### Task 3: Scenario params + GuardrailOptimizationInput plumbing (core, api)

**Files:**
- Modify: `ScenarioParams`, `ScenarioParamsSource`, `ScenarioParamsParser`, `ScenarioRequest`, `ScenarioCrudService` (validation + persist), `GuardrailProfileService` (`scenarioSignature`), `GuardrailOptimizationInput` (+`stochasticMortality`, `primarySex`, `spouseSex`, `longevityConditionalAge`, `@Nullable MortalityTable mortalityTable`), `ProjectionInputBuilder` (call `MortalityTableProvider.load()` only when `stochasticMortality` true; else pass null).
- Test: extend `ScenarioParamsTest`, `ScenarioParamsParserTest`, `ScenarioCrudServiceTest`, `GuardrailProfileServiceTest`.

**Interfaces (Produces):** `GuardrailOptimizationInput` gains, in canonical-constructor order after `communityProperty()`:
```java
Boolean stochasticMortality,      // null ⇒ false
String primarySex, String spouseSex,   // "male" | "female" | null (blended)
Integer longevityConditionalAge,  // null ⇒ 95
@Nullable com.wealthview.core.projection.mortality.MortalityTable mortalityTable
```

- [ ] **Step 1: Failing params tests** (mirror the `feeRate`/`spouseBirthYear` pattern exactly): `ScenarioParamsTest` — a params_json blob with `stochastic_mortality`, `primary_sex`, `spouse_sex`, `longevity_conditional_age` round-trips through `from`/`parseOrEmpty`; absent ⇒ nulls (which downstream defaults to false / blended / 95). `ScenarioParamsParserTest` — snake_case keys map to the record fields.
- [ ] **Step 2: Run — FAIL.** `mvn -q -pl wealthview-core test -Dtest=ScenarioParamsTest,ScenarioParamsParserTest -B`.
- [ ] **Step 3: Implement** — add the four fields to `ScenarioParams` (extend `EMPTY` all-null list + `from`), `ScenarioParamsSource` (accessor methods + Javadoc "only meaningful when `stochasticMortality`"), `ScenarioRequest`, and `ScenarioParamsParser`. **Step 4: Run — PASS.**
- [ ] **Step 5: Failing validation + signature tests.** `ScenarioCrudServiceTest`: `primary_sex`/`spouse_sex` not in `{male,female}` ⇒ validation exception with a clear message; `spouse_sex` without `spouse_birth_year` ⇒ rejected; `longevity_conditional_age` outside 80–110 ⇒ rejected; `spouse_sex` present with valid `spouse_birth_year` ⇒ accepted. `GuardrailProfileServiceTest`: toggling `stochastic_mortality`, changing `primary_sex`, or changing `longevity_conditional_age` each changes `scenarioSignature` (staleness).
- [ ] **Step 6: Run — FAIL,** then **implement** validation beside the A household validators (`validateSurvivorSpendingFactor` neighbourhood) and append the four fields to `scenarioSignature`'s hashed tuple. Wire `ProjectionInputBuilder`: `MortalityTable table = Boolean.TRUE.equals(params.stochasticMortality()) ? mortalityTableProvider.load() : null;` and pass it + the three params into `GuardrailOptimizationInput`. **Step 7: Run — PASS.**
- [ ] **Step 8: Back-compat check.** Full core+api suite green with only mechanical constructor-arity edits (null-filled new fields) at existing `GuardrailOptimizationInput`/`ScenarioParams` call sites. `mvn -q -pl wealthview-core,wealthview-api -am verify -DskipITs -B`.
- [ ] **Step 9: Commit.** `feat(core,api): plumb stochastic-mortality scenario params and mortality table`

---

### Task 4: MortalitySampler (projection, pure, seeded)

**Files:**
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/MortalitySampler.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/MortalitySamplerTest.java`

**Interfaces (Consumes:** `MortalityTable.qx(sex, age)`, `MortalityTable.maxAge()`. **Produces:)**
```java
final class MortalitySampler {
    /** Death age sampled given the person is alive at exact age currentAge: walk ages upward, dying
     *  at the first age where rng.nextDouble() < qx; forced at table.maxAge(). */
    static int sampleDeathAge(MortalityTable table, @Nullable String sex, int currentAge, Random rng);
}
```

- [ ] **Step 1: Write failing tests.**
```java
@Test
void sampleDeathAge_qxOneAtCurrentAge_diesThisYear() {
    var table = new MortalityTable(Map.of(65, 1.0, 120, 1.0), Map.of(65, 1.0, 120, 1.0));
    assertThat(MortalitySampler.sampleDeathAge(table, "male", 65, new Random(1))).isEqualTo(65);
}

@Test
void sampleDeathAge_zeroUntilTerminal_forcesDeathAtMaxAge() {
    // qx 0 for all tabulated ages except the terminal 1.0 ⇒ always survives to maxAge
    var table = new MortalityTable(Map.of(60, 0.0, 90, 1.0), Map.of(60, 0.0, 90, 1.0));
    assertThat(MortalitySampler.sampleDeathAge(table, "female", 60, new Random(42))).isEqualTo(90);
}

@Test
void sampleDeathAge_sameSeed_isReproducible() {
    var table = new MortalityTable(Map.of(60, 0.05, 120, 1.0), Map.of(60, 0.05, 120, 1.0));
    int a = MortalitySampler.sampleDeathAge(table, "male", 60, new Random(7));
    int b = MortalitySampler.sampleDeathAge(table, "male", 60, new Random(7));
    assertThat(a).isEqualTo(b);
}
```

- [ ] **Step 2: Run — FAIL.** `mvn -q -pl wealthview-projection test -Dtest=MortalitySamplerTest -B`. Expected: class not found.
- [ ] **Step 3: Implement.**
```java
private MortalitySampler() {}
static int sampleDeathAge(MortalityTable table, @Nullable String sex, int currentAge, Random rng) {
    int maxAge = table.maxAge();
    for (int age = currentAge; age < maxAge; age++) {
        if (rng.nextDouble() < table.qx(sex, age)) {
            return age;
        }
    }
    return maxAge;   // forced terminal death
}
```
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Statistical pin (seeded, deterministic).** Add a test that samples 10,000 death ages at `new Random(12345)` from a small hand-built table whose closed-form conditional life expectancy you compute in the test; assert the empirical mean is within 0.5 years. Deterministic because the seed is fixed. **Run — PASS.**
- [ ] **Step 6: Commit.** `feat(projection): add seeded sex-specific mortality sampler`

---

### Task 5: Per-trial death-age precompute wired into the builder (projection)

**Files:**
- Modify: `OptimizationContextBuilder` (near the `new Random(input.seed())` at line 80 and the `HouseholdMcResolver.resolve(...)` at line 110), `HouseholdMcResolver` (or a new sibling) to expose per-trial index derivation.
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/MortalityDraws.java`
- Test: `MortalityDrawsTest`, extend `OptimizationContextBuilderTest` (or `HouseholdMcResolverTest`).

**Interfaces (Produces):**
```java
/** Per-trial sampled mortality, in trial order. Empty (single arrays of the fixed A indices) when
 *  the toggle is off — the byte-identical anchor. */
record MortalityDraws(int[] transitionIdx, int[] truncateIdx, boolean[] survivorIsPrimary,
                      int[] firstDeathAge, int[] secondDeathAge) {}

// In the builder (Consumes MortalitySampler + the A index math already in HouseholdMcResolver.resolve):
MortalityDraws draws = MortalityDrawGenerator.generate(input, retirementYear, years,
        new Random(seedOrDefault + MORTALITY_SEED_OFFSET), trialCount);
```

**Contract (binding — implementer reads the live `HouseholdMcResolver.resolve` for the exact index formulas to reuse):**
- Only runs when `input.stochasticMortality()` is true AND `input.mortalityTable() != null` AND `input.spouseBirthYear() != null` (household). Otherwise the builder takes today's single-`Resolved` path unchanged (anchor).
- For each trial `t` in `0..trialCount`, in order: `primaryDeathAge[t] = sampleDeathAge(table, input.primarySex(), primaryAgeAtRetirement, mortRng)`; likewise spouse. Derive per-trial `firstDeathYear/secondDeathYear/survivor` with the SAME logic `HouseholdContext.of` uses (min/max death year; younger survives on tie), then map to `transitionIdx`/`truncateIdx` with the SAME formulas already in `resolve` (`firstDeathIdx = firstDeathYear − retirementYear`, clamp `<0 → 0`, `≥ years ⇒ no in-window transition sentinel = years`; `truncateIdx = max(0, min(years, secondDeathYear − retirementYear + 1))`).
- The mortality `Random` is constructed from `input.seed() + MORTALITY_SEED_OFFSET` (define `MORTALITY_SEED_OFFSET` as a documented constant ≠ 1); null seed ⇒ `new Random()`. It is a SEPARATE object from the return-path rng at line 80 — assert in a test that building the return paths with and without the mortality draws yields identical return sequences.

- [ ] **Step 1: Failing `MortalityDrawsTest`** — feed a builder/​generator a stub table where primary always dies at 80 and spouse at 90 (qx step tables), retirement year known; assert every trial's `transitionIdx` maps to primary's death, `truncateIdx` to spouse's death + 1 (clamped), `survivorIsPrimary == false`, and `secondDeathAge == 90`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement** `MortalityDraws` + the generator; wire it in the builder behind the toggle guard. Keep the fixed-death `HouseholdMcResolver.resolve` path for toggle-off.
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Separate-stream test** — with a fixed seed, capture the `PortfolioPathGenerator` output with the toggle off and again with the toggle on; assert the return matrices are identical (mortality draws do not perturb the return rng). **Run — PASS.**
- [ ] **Step 6: Commit.** `feat(projection): precompute per-trial mortality draws on a separate rng stream`

---

### Task 6: Three-regime precompute + per-trial splice in TrialSimulator (opus-tier)

**Files:**
- Modify: `OptimizationContextBuilder` (emit joint + two survivor regimes: income arrays, per-year `OrdinaryTaxTable[]`/`LtcgTaxTable[]` built MFJ for joint and SINGLE for each survivor identity, using `HouseholdContext.filerAgeIn`/`age65QualifyingCount` for the correct per-person deductions), `HouseholdMcResolver` (generalize its single-survivor regime builder to emit both survivor identities), `TrialSimulator.simulateTrial` + `SimulationConfig`.
- Test: `TrialSimulatorStochasticTest` (new), extend `TrialSimulatorHouseholdTest`.

**Contract (binding — the implementer reads the live `simulateTrial`; these are the requirements):**
- `SimulationConfig` gains regime arrays for both survivor identities and a per-trial selector input. Concretely: keep the existing `ordinaryTaxTableByYear` etc. as the JOINT regime; add `ordinaryTaxTableByYearSurvivor[identity]`, `ltcgTaxTableByYearSurvivor[identity]`, `incomeByYearSurvivor[identity]` (identity ∈ {PRIMARY_SURVIVES, SPOUSE_SURVIVES}). Floors×`survivorFactor` stays the in-loop multiply from `transitionIdx` (reuse `HouseholdMcResolver.scaleFromTransition`) — no third floor copy.
- `simulateTrial` takes the trial's `(transitionIdx, truncateIdx, survivorIsPrimary)` from `MortalityDraws[t]` (toggle-off passes the fixed A values, so the single-index path is preserved). Each year `y`: select `income/ordinaryTable/ltcgTable` = `y < transitionIdx ? joint : survivorRegime[survivorIsPrimary ? PRIMARY_SURVIVES : SPOUSE_SURVIVES]`. `loopYears = min(years, truncateIdx)`. The existing first-death event at `y == transitionYearIndex` (rollover, per-owner step-up via `taxableSeed`, `survivorIsPrimary` direction) fires at the per-trial index — logic unchanged, index now per-trial.
- **Anchor:** toggle off ⇒ `transitionIdx`/`truncateIdx`/`survivorIsPrimary` equal today's fixed `HouseholdSim` values and the survivor regime equals today's single pre-spliced arrays, so every existing MC number is byte-identical. Single-person ⇒ `household == null`, no regimes, no splice.
- **Pinned tests:** (a) a stochastic household fixture with a table forcing primary-dies-80 / spouse-dies-90 at a fixed seed — assert the trial uses MFJ income+tables before the 80 index and single-survivor(spouse) income+tables after, filing flips, spending ×factor after; (b) the mirror fixture forcing spouse-dies-first — asserts the PRIMARY_SURVIVES regime is selected; (c) truncation: a fixture where the second death lands mid-horizon ends the loop at `truncateIdx` (final balance = bequest carry-forward); (d) telescoping/composition test extended so total is order-invariant across the two RMD streams then the transition.
- [ ] **Step 1–4: TDD** the four pinned tests → implement the regime precompute + splice → green.
- [ ] **Step 5: Byte-identical gate** — run A's 6 goldens + MC char + adaptive-gate pins with the toggle off; assert unchanged. `mvn -q -pl wealthview-projection,wealthview-core -am verify -DskipITs -B` + explicit `ProjectionGoldenFileTest`.
- [ ] **Step 6: Commit.** `feat(projection): per-trial mortality regime splice in the Monte Carlo`

---

### Task 7: Stochastic-mortality success metrics + summary (projection)

**Files:**
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/StochasticMortalitySummary.java`
- Modify: the trial-loop aggregation site (where `TrialResult.success()` is tallied into the success rate) to also collect per-trial `secondDeathAge`/`firstDeathAge` and compute the summary.
- Test: `StochasticMortalitySummaryTest`.

**Interfaces (Produces):**
```java
record AgeDistribution(int p10, int median, int p90) {}
record LongevityConditional(int age, double probability, double trialFraction) {}
record StochasticMortalitySummary(
        double lifetimeSuccessProbability,
        LongevityConditional longevityConditional,
        AgeDistribution firstDeathAge, AgeDistribution secondDeathAge) {
    static StochasticMortalitySummary from(boolean[] success, int[] firstDeathAge,
                                           int[] secondDeathAge, int longevityAge);
}
```

- [ ] **Step 1: Failing test.**
```java
@Test
void from_countsEarlyDeathAsLifetimeSuccessAndFiltersLongevity() {
    boolean[] success   = { true,  true,  false, true };
    int[] firstDeath    = { 78,    82,    80,    85   };
    int[] secondDeath   = { 88,    96,    92,    97   };   // survivor ages
    var s = StochasticMortalitySummary.from(success, firstDeath, secondDeath, 95);
    assertThat(s.lifetimeSuccessProbability()).isEqualTo(0.75);          // 3 of 4
    // trials reaching survivor age ≥ 95: indices 1 (96) and 3 (97); both success ⇒ 1.0 over 0.5 of trials
    assertThat(s.longevityConditional().age()).isEqualTo(95);
    assertThat(s.longevityConditional().probability()).isEqualTo(1.0);
    assertThat(s.longevityConditional().trialFraction()).isEqualTo(0.5);
    assertThat(s.secondDeathAge().median()).isEqualTo(94);              // median of {88,92,96,97}
}
```

- [ ] **Step 2: Run — FAIL.** `mvn -q -pl wealthview-projection test -Dtest=StochasticMortalitySummaryTest -B`.
- [ ] **Step 3: Implement** `from`: `lifetimeSuccessProbability` = mean of `success`; longevity-conditional filters trials with `secondDeathAge[t] >= longevityAge`, `probability` = success rate within that subset (0 when the subset is empty), `trialFraction` = subset size / total; percentiles via sorted-array nearest-rank (`p10` at index `ceil(0.10·n)-1`, etc.). Document the percentile convention.
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Edge test** — empty longevity subset ⇒ `probability == 0`, `trialFraction == 0` (no divide-by-zero). **Run — PASS.**
- [ ] **Step 6: Commit.** `feat(projection): stochastic-mortality success metrics and death-age distribution`

---

### Task 8: Response DTO block + service wiring (projection, core, api)

**Files:**
- Modify: the guardrail/projection MC response record (add an optional `StochasticMortalityResponse` nested record; `null` when the toggle is off), its `from(...)` factory, the MC service that returns it, and the api response mapping. Follow the A pattern where `GuardrailProfileResponse` nests its disclosure record.
- Test: extend the MC service test + a `@WebMvcTest` slice asserting the JSON shape.

**Interfaces (Produces — snake_case wire):**
```java
record StochasticMortalityResponse(
        BigDecimal lifetimeSuccessProbability,
        LongevityConditionalResponse longevityConditional,
        AgeDistributionResponse firstDeathAge,
        AgeDistributionResponse secondDeathAge) {
    static StochasticMortalityResponse from(StochasticMortalitySummary s);   // null-safe: null ⇒ null
}
```
Serialized keys: `stochastic_mortality: { lifetime_success_probability, longevity_conditional: { age, probability, trial_fraction }, first_death_age: { p10, median, p90 }, second_death_age: {...} }`.

- [ ] **Step 1: Failing service test** — a stochastic household input yields a response whose `stochasticMortality()` is non-null with the summary's values; a toggle-off input yields `null` there (anchor). **Step 2: FAIL.**
- [ ] **Step 3: Implement** the nested records + `from` + wiring. **Step 4: PASS.**
- [ ] **Step 5: Failing `@WebMvcTest`** asserting `$.stochastic_mortality.longevity_conditional.trial_fraction` exists and snake_case keys serialize; toggle-off ⇒ field absent/null. **Implement mapping → PASS.**
- [ ] **Step 6:** Full `mvn -q -pl wealthview-core,wealthview-api,wealthview-projection -am verify -DskipITs -B`. **Commit.** `feat(projection,api): expose stochastic-mortality results on the projection response`

---

### Task 9: Frontend household stochastic-mortality surface

**Files:**
- Modify: `frontend/src/types/projection.ts` (+ `stochastic_mortality` toggle, `primary_sex`/`spouse_sex`, `longevity_conditional_age`, and the response block types), `frontend/src/.../ScenarioForm.tsx` (toggle in the Spouse/Household section; per-spouse Sex selects shown when on; advanced longevity-age input default 95), the MC results component (render the two probabilities + a second-death-age P10/median/P90 readout with copy distinguishing lifetime vs longevity-conditional), api serializers.
- Test: co-located `.test.tsx` (Vitest + RTL).

- [ ] **Step 1: Failing component tests** — toggling "Model uncertain lifespans" reveals the Sex selects and longevity-age input; Sex select round-trips into the serialized request; a response with a `stochastic_mortality` block renders both probability labels and the median second-death age; a response without it renders neither (no crash). **Step 2: `npm run test` — FAIL.**
- [ ] **Step 3: Implement** the form fields, serializers (null ⇒ omit), and results rendering. **Step 4: `npm run test` — PASS.**
- [ ] **Step 5:** `npm run typecheck && npm run lint`. **Commit.** `feat(frontend): stochastic-mortality toggle, sex inputs, and longevity results`

---

### Task 10: Golden #7, back-compat sweep, full verify

**Files:**
- Create: `backend/wealthview-projection/src/test/resources/golden/household-stochastic-mortality-input.json` + generated `household-stochastic-mortality.json`; extend `ProjectionGoldenFileTest` `@ValueSource`.
- Modify: none beyond the golden registration.

**Fixture:** the A `household-survivor` couple (births 1958/1966, retirement 2030, horizon 2066) with `stochastic_mortality = true`, `primary_sex = male`, `spouse_sex = female`, a fixed `seed`, `trial_count` sufficient for stable percentiles (e.g. 2000), `longevity_conditional_age = 95`.

- [ ] **Step 1:** Generate via `-Dupdate.golden=true`. **Sanity-review:** hand-verify (from the seeded draws) that `lifetime_success_probability` ≥ the A fixed-death success rate (early-death trials help), the second-death-age median is near female cohort life expectancy, and the longevity-conditional probability ≤ lifetime. Document 6–8 verified values in the report.
- [ ] **Step 2: Back-compat sweep** — A's 6 goldens byte-identical (toggle off); MC char + adaptive-gate pins unchanged. `mvn -q -pl wealthview-projection test -Dtest=ProjectionGoldenFileTest -B`.
- [ ] **Step 3: Full verify.** `mvn -q clean install -DskipITs -B` + `mvn -q verify -pl wealthview-app -B` (Testcontainers ITs incl. V080) + `npm run test && npm run typecheck` in `frontend`. All green.
- [ ] **Step 4: Commit.** `test(projection): stochastic-mortality golden and full back-compat sweep`

---

## Self-Review

- **Spec coverage:** §1 data model → T1 (table/seed/repo), T2 (MortalityTable), T3 (params + input); §2 sampling → T4 (sampler), T5 (per-trial draws, separate stream); §3 architecture (two-regime splice) → T6; §4 success metrics + output DTO → T7, T8; §5 frontend → T9; §6 testing → every task's TDD + T10 golden/back-compat; independent-mortality + reproducibility constraints → T5 contract. No gaps.
- **Placeholder scan:** the only externally-sourced content is the SSA `qx` seed (T1 step 4) — flagged as *transcribe from the cited SSA table*, a concrete action, not a vague "fill in." All test bodies and signatures are concrete.
- **Type consistency:** `MortalityTable.qx(String,int)`/`maxAge()`, `MortalitySampler.sampleDeathAge(MortalityTable,String,int,Random)`, `MortalityDraws` fields, `StochasticMortalitySummary.from(boolean[],int[],int[],int)`, and the `StochasticMortalityResponse` snake_case keys are used identically across T2/T4/T5/T7/T8. `MORTALITY_SEED_OFFSET` is defined once (T5) and referenced only there. `survivorIsPrimary` / `transitionIdx` / `truncateIdx` names match A's live `HouseholdSim`.
