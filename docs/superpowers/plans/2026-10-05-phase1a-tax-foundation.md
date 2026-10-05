# Phase 1a — Tax Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the engines charge tax correctly and know exact early-access dates, and expose a per-year tax-space picture and an after-tax legacy value. Later phases build their recommendations on these.

**Architecture:**
- The deterministic engine gains `runDetailed()`, which returns the unchanged `ProjectionResultResponse` plus per-year `YearTaxPicture`s, per-year `TaxSpaceYear`s (from a pure core `TaxSpaceCalculator`) and a `TerminalValue`.
- These reach the API only through `ProjectionRunResponse`, so the golden files move only where behavior intentionally changes.
- Birth month resolves to an `earlyAccessAge` (59 or 60) through one core helper, `AgeMilestones`. It is threaded into the deterministic engine, the Monte Carlo, and the conversion simulator.
- Defect fixes D5, D13, D15, D16, D4 and D7 land as their own commits. So do two engine defects found during planning:
  - Social Security provisional income counted gross rent (Task 8).
  - The LTCG stacking floor used the age-unaware standard deduction (Task 9, steps 9–13).

**Tech Stack:** Java 25 / Spring Boot 4.1 / Jackson 3 / Hibernate 7, Maven multi-module, JUnit 5 + Mockito + AssertJ, Testcontainers PostgreSQL 16, Flyway; React 19 / TypeScript / Vitest / React Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-05-phase1a-tax-foundation-design.md` (parent roadmap: `docs/superpowers/specs/2026-10-04-tactical-retirement-planning-roadmap.md`).

## Global Constraints

**Engine behavior**
- Legacy behavior is preserved exactly when `birth_month` / `spouse_birth_month` are absent: early access at `birthYear + 60`.
- `heir_tax_rate`: default `0.24`, valid range `0`–`0.50` inclusive.
- Legacy valuation = traditional × (1 − heir rate) + Roth + taxable (basis steps up at death). Property equity is excluded.
- IRMAA is never blended into the effective marginal rate. It is reported as tier distance plus a $ cliff.

**Golden files**
- Goldens change ONLY in intentional-behavior commits: Task 8 (if any golden input has rentals + SS), Task 9 step 13 (only if a 65+ LTCG year exists), and Task 12 (D5).
- Each such commit body lists before/after headline figures.
- Never regenerate goldens to "make a test pass" anywhere else.

**Code and wire conventions**
- New DTOs are records in `wealthview-core` `com.wealthview.core.projection.dto`.
- JSON is snake_case on the wire. Money is `BigDecimal` in core and the deterministic engine; the MC hot loop keeps `double`.
- Migrations: V081 is the only one (`V081__guardrail_profile_conversion_settings.sql`). No existing migration is edited.
- `@Observed(name = "wealthview.projection.run")` stays on both `run` and `runDetailed`. Never add `@Timed` alongside it.
- No wildcard imports. Constructor injection only. AssertJ only. Test names follow `method_state_expected`. Use `@MockitoBean` (not `@MockBean`). Jackson 3 lives under `tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation`.

**Build gates**
- Quality gates (PMD, CPD, SpotBugs, Checkstyle, JaCoCo) must pass per module: `cd backend && mvn -q -T1 -pl <module> -am verify -DskipITs`.
- Coverage floors are never lowered.
- Full-reactor `mvn verify` OOMs in this sandbox, so verify per module, always with `-am`.
- Never use `-DskipTests` to run ITs. Use `-Dsurefire.skip=true`.

**Commits**
- Commit directly on `main`. Never push.
- Conventional Commits, with a body for feat/fix/refactor/db, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Birth month without birth year.** A scenario that sets `birth_month` but no `birth_year` is rejected with 400 rather than silently applied to the engine's `currentYear − 35` fallback. Pinned in Task 2.
2. **Existing saved scenarios.** Their `params_json` predates every new key. They must still run, with early access at 60 and heir rate 0.24, and show the Tax Space tab and Legacy card. Pinned in Task 2 (legacy params → 60) and Task 5 (legacy params → 0.24).
3. **Projections with no retired years or no years at all.** `tax_space` is empty, the tab is hidden, and `terminal_value` is null-safe. Nothing throws. Pinned in Task 5 (empty yearlyData) and Task 11 (tab and card hidden when data is absent).
4. **Selling a loss lot to pay tax (D5).** The realized loss offsets the year's gains, LTCG income is floored at 0, and tax never goes negative. Pinned in Task 12.
5. **A year with no income and no Social Security.** SS fields are null, the marginal rate is 0, and ordinary room equals the full deduction space. The exact SS threshold boundary returns inclusion 0. Pinned in Task 7.

---

### Task 1: `AgeMilestones` and `HouseholdContext.Person.birthMonth`

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/household/AgeMilestones.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/household/HouseholdContext.java` (`Person` record ~:43-53; add `withBirthMonths` after `age65QualifyingCount` ~:123)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/household/AgeMilestonesTest.java` (create)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/household/HouseholdContextTest.java` (extend)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `AgeMilestones.LEGACY_EARLY_ACCESS_AGE` (`int` = 60)
  - `static int AgeMilestones.earlyAccessAge(@Nullable Integer birthMonth)` → 59 for months 1–6, 60 for 7–12, 60 for null; throws `IllegalArgumentException` for a month outside 1..12
  - `static int AgeMilestones.earlyAccessYear(int birthYear, @Nullable Integer birthMonth)` = `birthYear + earlyAccessAge(birthMonth)`
  - `static int AgeMilestones.medicareStartMonth(@Nullable Integer birthMonth)` → `birthMonth`, or 1 when null (consumed by 1b; the contract drops the spec's unused `birthYear` parameter)
  - `HouseholdContext.Person(PersonId id, int birthYear, int deathAge, @Nullable Integer birthMonth)` plus a convenience constructor `Person(PersonId, int, int)` that sets the month to null
  - `HouseholdContext withBirthMonths(@Nullable Integer primaryMonth, @Nullable Integer spouseMonth)`

- [ ] **Step 1: Write the failing `AgeMilestones` test**

Create `backend/wealthview-core/src/test/java/com/wealthview/core/projection/household/AgeMilestonesTest.java`:

```java
package com.wealthview.core.projection.household;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgeMilestonesTest {

    static Stream<Arguments> earlyAccessAgeCases() {
        return Stream.of(
                Arguments.of(1, 59),
                Arguments.of(3, 59),
                Arguments.of(6, 59),
                Arguments.of(7, 60),
                Arguments.of(9, 60),
                Arguments.of(12, 60));
    }

    @ParameterizedTest
    @MethodSource("earlyAccessAgeCases")
    void earlyAccessAge_knownBirthMonth_returnsAgeOfThe59AndAHalfCalendarYear(int birthMonth, int expectedAge) {
        int age = AgeMilestones.earlyAccessAge(birthMonth);

        assertThat(age).isEqualTo(expectedAge);
    }

    @Test
    void earlyAccessAge_nullBirthMonth_returnsLegacy60() {
        int age = AgeMilestones.earlyAccessAge(null);

        assertThat(age).isEqualTo(AgeMilestones.LEGACY_EARLY_ACCESS_AGE).isEqualTo(60);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13, -1})
    void earlyAccessAge_monthOutsideOneToTwelve_throws(int badMonth) {
        assertThatThrownBy(() -> AgeMilestones.earlyAccessAge(badMonth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 12");
    }

    @Test
    void earlyAccessYear_bornMarch1970_is2029() {
        // Born March 1970 -> reaches 59.5 in September 2029 -> 2029 is the penalty-free year.
        int year = AgeMilestones.earlyAccessYear(1970, 3);

        assertThat(year).isEqualTo(2029);
    }

    @Test
    void earlyAccessYear_bornSeptember1970_is2030() {
        // Born September 1970 -> reaches 59.5 in March 2030.
        int year = AgeMilestones.earlyAccessYear(1970, 9);

        assertThat(year).isEqualTo(2030);
    }

    @Test
    void earlyAccessYear_nullMonth_isLegacyBirthYearPlus60() {
        int year = AgeMilestones.earlyAccessYear(1970, null);

        assertThat(year).isEqualTo(2030);
    }

    @Test
    void medicareStartMonth_knownMonth_returnsBirthMonth() {
        int month = AgeMilestones.medicareStartMonth(8);

        assertThat(month).isEqualTo(8);
    }

    @Test
    void medicareStartMonth_nullMonth_returnsJanuaryForWholeYearLegacy() {
        int month = AgeMilestones.medicareStartMonth(null);

        assertThat(month).isEqualTo(1);
    }

    @Test
    void medicareStartMonth_invalidMonth_throws() {
        assertThatThrownBy(() -> AgeMilestones.medicareStartMonth(13))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=AgeMilestonesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol ... class AgeMilestones`.

- [ ] **Step 3: Implement `AgeMilestones`**

Create `backend/wealthview-core/src/main/java/com/wealthview/core/projection/household/AgeMilestones.java`:

```java
package com.wealthview.core.projection.household;

import org.springframework.lang.Nullable;

/**
 * Date-of-birth milestones that gate account access and Medicare, resolved from a birth year and an
 * optional birth month (Phase 1a). A {@code null} month reproduces the legacy whole-year behavior
 * exactly, so every scenario saved before birth month existed is unchanged.
 *
 * <p><b>Early access (IRC 72(t), age 59½).</b> The calendar year in which the person reaches 59½
 * counts as penalty-free. This is the tactical assumption that withdrawals are timed after that
 * date. 59½ falls in {@code birthYear + 59} when the person was born January-June, and in
 * {@code birthYear + 60} otherwise. With no month known, the legacy proxy {@code birthYear + 60}
 * applies.
 *
 * <p>RMD start age and the age-65 standard-deduction adder depend on the calendar year only, so
 * they do not live here.
 */
public final class AgeMilestones {

    /** Legacy whole-year 59½ proxy: the first penalty-free age when the birth month is unknown. */
    public static final int LEGACY_EARLY_ACCESS_AGE = 60;

    private static final int FIRST_HALF_EARLY_ACCESS_AGE = 59;
    private static final int LAST_FIRST_HALF_MONTH = 6;
    private static final int FIRST_MONTH = 1;
    private static final int LAST_MONTH = 12;

    private AgeMilestones() {
    }

    /** The age (calendar year minus birth year) of the first penalty-free year. */
    public static int earlyAccessAge(@Nullable Integer birthMonth) {
        if (birthMonth == null) {
            return LEGACY_EARLY_ACCESS_AGE;
        }
        requireValidMonth(birthMonth);
        return birthMonth <= LAST_FIRST_HALF_MONTH ? FIRST_HALF_EARLY_ACCESS_AGE : LEGACY_EARLY_ACCESS_AGE;
    }

    /** Calendar year the person reaches 59½; that year counts as penalty-free. */
    public static int earlyAccessYear(int birthYear, @Nullable Integer birthMonth) {
        return birthYear + earlyAccessAge(birthMonth);
    }

    /**
     * Month (1-based) of the 65th birthday, when Medicare starts. A {@code null} month returns 1:
     * the legacy whole-year treatment, where the person counts as on Medicare for the full year
     * they turn 65. Phase 1b consumes this for the pre-65/65+ premium split.
     */
    public static int medicareStartMonth(@Nullable Integer birthMonth) {
        if (birthMonth == null) {
            return FIRST_MONTH;
        }
        requireValidMonth(birthMonth);
        return birthMonth;
    }

    private static void requireValidMonth(int birthMonth) {
        if (birthMonth < FIRST_MONTH || birthMonth > LAST_MONTH) {
            throw new IllegalArgumentException("birth month must be between 1 and 12, was " + birthMonth);
        }
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=AgeMilestonesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all 17 invocations green).

- [ ] **Step 5: Write the failing `HouseholdContext` tests**

Append these tests inside `HouseholdContextTest` (it already imports `assertThat` and `PersonId`):

```java
    @Test
    void person_threeArgConstructor_leavesBirthMonthNull() {
        var person = new HouseholdContext.Person(PersonId.PRIMARY, 1970, 87);

        assertThat(person.birthMonth()).isNull();
    }

    @Test
    void withBirthMonths_twoPersonHousehold_setsBothMonthsAndKeepsEverythingElse() {
        var base = HouseholdContext.of(1968, 85, 1970, 90, 2060);

        var withMonths = base.withBirthMonths(3, 11);

        assertThat(withMonths.primary().birthMonth()).isEqualTo(3);
        assertThat(withMonths.spouse().birthMonth()).isEqualTo(11);
        assertThat(withMonths.primary().birthYear()).isEqualTo(1968);
        assertThat(withMonths.spouse().deathAge()).isEqualTo(90);
        assertThat(withMonths.transitionYear()).isEqualTo(base.transitionYear());
        assertThat(withMonths.secondDeathYear()).isEqualTo(base.secondDeathYear());
        assertThat(withMonths.survivor()).isEqualTo(base.survivor());
    }

    @Test
    void withBirthMonths_singlePerson_setsPrimaryMonthAndIgnoresSpouseMonth() {
        var single = HouseholdContext.single(1970);

        var withMonths = single.withBirthMonths(5, 8);

        assertThat(withMonths.primary().birthMonth()).isEqualTo(5);
        assertThat(withMonths.spouse()).isNull();
        assertThat(withMonths.isHousehold()).isFalse();
    }

    @Test
    void withBirthMonths_nullMonths_equalsTheOriginalContext() {
        var base = HouseholdContext.of(1968, 85, 1970, 90, 2060);

        assertThat(base.withBirthMonths(null, null)).isEqualTo(base);
    }
```

- [ ] **Step 6: Run them and confirm they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=HouseholdContextTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol ... method birthMonth()` / `withBirthMonths`.

- [ ] **Step 7: Implement the `Person.birthMonth` component and `withBirthMonths`**

In `HouseholdContext.java`, replace the `Person` record (currently `public record Person(PersonId id, int birthYear, int deathAge) { ... }`) with:

```java
    /**
     * One household member's fixed-death-age mortality assumption.
     *
     * @param deathAge the age at which this person is assumed to die (SSA default or user override)
     * @param birthMonth 1-12, or {@code null} when unknown (legacy whole-year ages; see
     *         {@link AgeMilestones})
     */
    public record Person(PersonId id, int birthYear, int deathAge, @Nullable Integer birthMonth) {

        /** Pre-Phase-1a shape: birth month unknown. */
        public Person(PersonId id, int birthYear, int deathAge) {
            this(id, birthYear, deathAge, null);
        }

        public int deathYear() {
            return birthYear + deathAge;
        }

        public int ageIn(int calendarYear) {
            return calendarYear - birthYear;
        }

        Person withBirthMonth(@Nullable Integer month) {
            return new Person(id, birthYear, deathAge, month);
        }
    }
```

Then add this method immediately after `age65QualifyingCount(int year)`:

```java
    /**
     * Phase 1a: a copy of this context with the given birth months. The spouse month is ignored for
     * a single-person context. Mortality shape (transition / second-death years, survivor) is
     * unchanged: those are whole-year quantities.
     */
    public HouseholdContext withBirthMonths(@Nullable Integer primaryMonth, @Nullable Integer spouseMonth) {
        return new HouseholdContext(
                primary.withBirthMonth(primaryMonth),
                spouse != null ? spouse.withBirthMonth(spouseMonth) : null,
                transitionYear, secondDeathYear, survivor);
    }
```

- [ ] **Step 8: Run both test classes and the module's household tests, and confirm they pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest='AgeMilestonesTest,HouseholdContextTest,ProjectionInputBuilderTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. The existing `new HouseholdContext.Person(PersonId.PRIMARY, 1958, 85)` call in `HouseholdContextTest` still compiles through the 3-arg constructor.

- [ ] **Step 9: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/household/AgeMilestones.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/household/HouseholdContext.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/household/AgeMilestonesTest.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/household/HouseholdContextTest.java
git commit -F - <<'EOF'
feat(core): add AgeMilestones and birth month on household persons

AgeMilestones centralizes the date-of-birth math that gates early
access and Medicare. A person born January-June reaches 59 1/2 in the
calendar year they turn 59, so that year is penalty-free. A null birth
month keeps the legacy whole-year proxy (age 60), so existing scenarios
are unchanged. medicareStartMonth is pre-built for Phase 1b.

HouseholdContext.Person gains an optional birthMonth (the 3-arg
constructor keeps the old shape), and withBirthMonths() returns a copy
carrying both months.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Birth-month scenario inputs (params, request, validation, parser, MC input, household)

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParams.java`: add components, `EMPTY`, `from`, and a 29-arg back-compat constructor
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParamsSource.java`: two accessors
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioRequest.java`: two components after `longevityConditionalAge`; update both back-compat constructors
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ScenarioCrudService.java`: `validateBirthMonths`, called from create (~:111) and update (~:163)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationInput.java`: component `birthMonth` after `mortalityTable`; back-compat constructor; Builder
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java`: `buildOptimizationInput` (~:563) adds `.birthMonth(params.birthMonth())`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionInputBuilder.java`: `resolveHousehold` (~:132-149) applies `withBirthMonths`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/ScenarioParamsParser.java`: `earlyAccessAge(ScenarioParams)`
- Modify (test fixtures): `backend/wealthview-core/src/test/java/com/wealthview/core/testutil/ScenarioRequestBuilder.java`, `backend/wealthview-projection/src/test/java/com/wealthview/projection/testutil/GuardrailOptimizationInputBuilder.java`, `backend/wealthview-projection/src/test/java/com/wealthview/projection/StochasticMortalityGoldenTest.java:153`, `backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerCharacterizationTest.java:68`
- Test: `ScenarioParamsTest`, `ScenarioCrudServiceTest`, `GuardrailProfileServiceTest`, `ProjectionInputBuilderTest` (core); `ScenarioParamsParserTest` (projection)

**Interfaces:**
- Consumes: `AgeMilestones.earlyAccessAge(Integer)`, `HouseholdContext.withBirthMonths(Integer, Integer)` (Task 1).
- Produces:
  - `ScenarioParams` gains `Integer birthMonth()` (`birth_month`) and `Integer spouseBirthMonth()` (`spouse_birth_month`), the LAST two components. Task 5 appends `heirTaxRate` after them and adds one more `null` to the 29-arg back-compat constructor below.
  - `ScenarioParamsSource.birthMonth()` / `spouseBirthMonth()`.
  - `ScenarioRequest` components `birthMonth` and `spouseBirthMonth`, placed immediately after `longevityConditionalAge` and before `accounts`. Task 5 inserts `heirTaxRate` immediately after `spouseBirthMonth`.
  - `ScenarioRequestBuilder.withBirthMonth(Integer)` / `withSpouseBirthMonth(Integer)`.
  - `GuardrailOptimizationInput.birthMonth()` (`@Nullable Integer`, last component) and `Builder.birthMonth(Integer)`; `GuardrailOptimizationInputBuilder.withBirthMonth(Integer)`.
  - `ScenarioParamsParser.earlyAccessAge(ScenarioParams)` (package-private, projection module) → `AgeMilestones.earlyAccessAge(params.birthMonth())`.

- [ ] **Step 1: Write the failing params/parser tests**

Append to `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/ScenarioParamsTest.java`:

```java
    @Test
    void toJson_birthMonthsPresent_writesSnakeCaseKeys() throws Exception {
        var params = ScenarioParams.from(ScenarioRequestBuilder.builder()
                .withBirthYear(1970).withBirthMonth(3)
                .withSpouseBirthYear(1972).withSpouseBirthMonth(11)
                .build());

        var node = mapper.readTree(params.toJson(mapper));

        assertThat(node.get("birth_month").asInt()).isEqualTo(3);
        assertThat(node.get("spouse_birth_month").asInt()).isEqualTo(11);
    }

    @Test
    void parseOrEmpty_legacyJsonWithoutBirthMonths_yieldsNullMonths() {
        var parsed = ScenarioParams.parseOrEmpty(mapper, """
                {"birth_year": 1965, "spouse_birth_year": 1967}
                """);

        assertThat(parsed.birthYear()).isEqualTo(1965);
        assertThat(parsed.birthMonth()).isNull();
        assertThat(parsed.spouseBirthMonth()).isNull();
    }

    @Test
    void legacy29ArgConstructor_padsBirthMonthsWithNull() {
        var params = new ScenarioParams(
                1968, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);

        assertThat(params.birthMonth()).isNull();
        assertThat(params.spouseBirthMonth()).isNull();
    }
```

Append to `backend/wealthview-projection/src/test/java/com/wealthview/projection/ScenarioParamsParserTest.java`:

```java
    // Phase 1a: birth month -> early-access age (59 1/2 year).

    @Test
    void earlyAccessAge_legacyParamsWithoutBirthMonth_returns60() {
        var params = parser.parseParams("""
                {"birth_year": 1965, "withdrawal_rate": 0.04}
                """);

        assertThat(parser.earlyAccessAge(params)).isEqualTo(60);
    }

    @Test
    void earlyAccessAge_marchBirth_returns59() {
        var params = parser.parseParams("""
                {"birth_year": 1970, "birth_month": 3}
                """);

        assertThat(parser.earlyAccessAge(params)).isEqualTo(59);
    }

    @Test
    void earlyAccessAge_septemberBirth_returns60() {
        var params = parser.parseParams("""
                {"birth_year": 1970, "birth_month": 9}
                """);

        assertThat(parser.earlyAccessAge(params)).isEqualTo(60);
    }

    @Test
    void earlyAccessAge_emptyParams_returns60() {
        assertThat(parser.earlyAccessAge(ScenarioParams.EMPTY)).isEqualTo(60);
    }
```

- [ ] **Step 2: Write the failing validation tests**

Append to `backend/wealthview-core/src/test/java/com/wealthview/core/projection/ScenarioCrudServiceTest.java` (`ScenarioRequestBuilder`, `assertThatThrownBy`, `when`, `any`, `captureSavedScenario()` are already in scope):

```java
    // Phase 1a: birth months.

    @Test
    void createScenario_birthMonthWithoutBirthYear_throwsIllegalArgument() {
        var request = ScenarioRequestBuilder.builder().withBirthYear(null).withBirthMonth(3).build();

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birth_month requires birth_year");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 13})
    void createScenario_birthMonthOutOfRange_throwsIllegalArgument(int badMonth) {
        var request = ScenarioRequestBuilder.builder().withBirthYear(1970).withBirthMonth(badMonth).build();

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birth_month must be between 1 and 12");
    }

    @Test
    void createScenario_spouseBirthMonthWithoutSpouseBirthYear_throwsIllegalArgument() {
        var request = ScenarioRequestBuilder.builder().withBirthYear(1970).withSpouseBirthMonth(5).build();

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spouse_birth_month requires spouse_birth_year");
    }

    @Test
    void createScenario_spouseBirthMonthOutOfRange_throwsIllegalArgument() {
        var request = ScenarioRequestBuilder.builder().withBirthYear(1970)
                .withSpouseBirthYear(1972).withSpouseBirthMonth(14).build();

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spouse_birth_month must be between 1 and 12");
    }

    @Test
    void updateScenario_birthMonthWithoutBirthYear_throwsIllegalArgument() {
        var request = ScenarioRequestBuilder.builder().withBirthYear(null).withBirthMonth(3).build();

        assertThatThrownBy(() -> service.updateScenario(tenantId, scenarioId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birth_month requires birth_year");
    }

    @Test
    void createScenario_validBirthMonths_persistsThemInParamsJson() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var request = ScenarioRequestBuilder.builder().withBirthYear(1970).withBirthMonth(3)
                .withSpouseBirthYear(1972).withSpouseBirthMonth(11).build();

        service.createScenario(tenantId, request);

        var saved = captureSavedScenario();
        assertThat(saved.getParamsJson()).contains("\"birth_month\":3");
        assertThat(saved.getParamsJson()).contains("\"spouse_birth_month\":11");
    }
```

(If `ScenarioCrudServiceTest` already imports `ParameterizedTest`/`ValueSource`, use the short names and drop the qualified ones. Do not add duplicate imports.)

These are the backend half of "birth_month without birth_year → 400". `GlobalExceptionHandler.handleIllegalArgument` (`wealthview-api/.../exception/GlobalExceptionHandler.java:158`) already maps `IllegalArgumentException` to a 400 envelope, so no API change is needed.

- [ ] **Step 3: Write the failing MC-input and household tests**

Append to `backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java`, next to the other `optimize_paramsJsonWith...` tests (~:705):

```java
    // Phase 1a: the scenario's birth_month reaches the MC input (early-access age is resolved
    // engine-side via AgeMilestones). Absent => null (legacy whole-year 60).
    @Test
    void optimize_paramsJsonWithBirthMonth_propagatesToInput() {
        var scenarioWithMonth = ScenarioMother.guardrailScenario(tenant, "Month",
                "{\"birth_year\":1968,\"birth_month\":4}");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioWithMonth);
        assertThat(input.birthMonth()).isEqualTo(4);
    }

    @Test
    void optimize_paramsJsonWithoutBirthMonth_propagatesNull() {
        var input = captureOptimizationInput(buildRequest(req -> req), scenario);
        assertThat(input.birthMonth()).isNull();
    }
```

Append to `backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionInputBuilderTest.java`, after `build_withSpouseBirthYear_buildsTwoPersonHousehold` (~:552):

```java
    @Test
    void build_withBirthMonths_threadsThemOntoHouseholdPersons() {
        var scenario = ScenarioMother.scenarioWithParams(tenant,
                "{\"birth_year\":1968,\"birth_month\":3,\"spouse_birth_year\":1970,\"spouse_birth_month\":10}");
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().primary().birthMonth()).isEqualTo(3);
        assertThat(result.household().spouse().birthMonth()).isEqualTo(10);
    }

    @Test
    void build_singleWithBirthMonth_threadsPrimaryMonth() {
        var scenario = ScenarioMother.scenarioWithParams(tenant, "{\"birth_year\":1968,\"birth_month\":6}");
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().primary().birthMonth()).isEqualTo(6);
        assertThat(result.household().isHousehold()).isFalse();
    }

    @Test
    void build_withoutBirthMonth_leavesPersonMonthNull() {
        var scenario = ScenarioMother.scenarioWithParams(tenant, "{\"birth_year\":1968}");
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().primary().birthMonth()).isNull();
    }
```

- [ ] **Step 4: Run and confirm compile failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='ScenarioParamsTest,ScenarioCrudServiceTest,GuardrailProfileServiceTest,ProjectionInputBuilderTest,ScenarioParamsParserTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE: `cannot find symbol ... withBirthMonth`, `birthMonth()`, `earlyAccessAge(ScenarioParams)`.

- [ ] **Step 5: Add the `ScenarioParams` components, `EMPTY`, `from`, and back-compat constructor**

In `ScenarioParams.java`:

1. Replace the last canonical component line `        Integer longevityConditionalAge) {` with:

```java
        Integer longevityConditionalAge,
        Integer birthMonth,
        Integer spouseBirthMonth) {
```

2. Replace `EMPTY` with:

```java
    public static final ScenarioParams EMPTY = new ScenarioParams(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
```

3. Replace the last argument line of `from(...)` (`source.longevityConditionalAge());`) with:

```java
                source.longevityConditionalAge(), source.birthMonth(), source.spouseBirthMonth());
```

4. Add this constructor directly after `EMPTY`:

```java
    /**
     * Back-compat convenience for positional callers that predate birth months (Phase 1a): the
     * 29-component shape. The new components are padded with {@code null}, meaning "not set", so
     * ages stay whole-year exactly as before.
     */
    // ExcessiveParameterList: mirrors the record's pre-Phase-1a canonical constructor so existing
    // positional call sites keep compiling unchanged.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public ScenarioParams(
            Integer birthYear, BigDecimal withdrawalRate, String withdrawalStrategy,
            BigDecimal dynamicCeiling, BigDecimal dynamicFloor, String filingStatus,
            BigDecimal otherIncome, BigDecimal annualRothConversion, String withdrawalOrder,
            BigDecimal dynamicSequencingBracketRate, String rothConversionStrategy,
            BigDecimal targetBracketRate, Integer rothConversionStartYear, String state,
            BigDecimal primaryResidencePropertyTax, BigDecimal primaryResidenceMortgageInterest,
            BigDecimal dividendYield, BigDecimal feeRate, Boolean includeDepressionYears,
            BigDecimal interestYield, Integer spouseBirthYear, Integer primaryDeathAge,
            Integer spouseDeathAge, BigDecimal survivorSpendingFactor, Boolean communityProperty,
            Boolean stochasticMortality, String primarySex, String spouseSex,
            Integer longevityConditionalAge) {
        this(birthYear, withdrawalRate, withdrawalStrategy, dynamicCeiling, dynamicFloor, filingStatus,
                otherIncome, annualRothConversion, withdrawalOrder, dynamicSequencingBracketRate,
                rothConversionStrategy, targetBracketRate, rothConversionStartYear, state,
                primaryResidencePropertyTax, primaryResidenceMortgageInterest, dividendYield, feeRate,
                includeDepressionYears, interestYield, spouseBirthYear, primaryDeathAge, spouseDeathAge,
                survivorSpendingFactor, communityProperty, stochasticMortality, primarySex, spouseSex,
                longevityConditionalAge, null, null);
    }
```

- [ ] **Step 6: Add the `ScenarioParamsSource` accessors**

Append inside `ScenarioParamsSource` after `longevityConditionalAge()`:

```java

    /**
     * Phase 1a: the primary's birth month (1-12). {@code null} means unknown, which keeps the
     * legacy whole-year 59½ proxy (age 60; see {@code AgeMilestones}). Requires
     * {@link #birthYear()}.
     */
    Integer birthMonth();

    /** Phase 1a: the spouse's birth month (1-12), mirroring {@link #birthMonth()}. Requires
     * {@link #spouseBirthYear()}. */
    Integer spouseBirthMonth();
```

- [ ] **Step 7: Add the `ScenarioRequest` components and update the back-compat constructors**

In `ScenarioRequest.java`:

1. Replace `        Integer longevityConditionalAge,\n        List<CreateProjectionAccountRequest> accounts,` in the record header with:

```java
        Integer longevityConditionalAge,
        Integer birthMonth,
        Integer spouseBirthMonth,
        List<CreateProjectionAccountRequest> accounts,
```

2. In the FIRST back-compat constructor (pre-C10 shape), change
   `null, null, null, null, null, null, null, null, null, null, null, accounts, spendingProfileId,`
   to
   `null, null, null, null, null, null, null, null, null, null, null, null, null, accounts, spendingProfileId,`
   (11 → 13 nulls: the two new month components default to null).

3. In the SECOND back-compat constructor (pre-C1 shape), change
   `includeDepressionYears, null, null, null, null, null, null, null, null, null, null, accounts,`
   to
   `includeDepressionYears, null, null, null, null, null, null, null, null, null, null, null, null, accounts,`
   (10 → 12 nulls).

- [ ] **Step 8: Extend `ScenarioRequestBuilder`**

In `backend/wealthview-core/src/test/java/com/wealthview/core/testutil/ScenarioRequestBuilder.java`:
- Add fields after `private Integer longevityConditionalAge;`:

```java
    private Integer birthMonth;
    private Integer spouseBirthMonth;
```

- Add setters before `withAccounts`:

```java
    public ScenarioRequestBuilder withBirthMonth(@Nullable Integer birthMonth) {
        this.birthMonth = birthMonth;
        return this;
    }

    public ScenarioRequestBuilder withSpouseBirthMonth(@Nullable Integer spouseBirthMonth) {
        this.spouseBirthMonth = spouseBirthMonth;
        return this;
    }
```

- In `build()`, change `stochasticMortality, primarySex, spouseSex, longevityConditionalAge,` to `stochasticMortality, primarySex, spouseSex, longevityConditionalAge, birthMonth, spouseBirthMonth,`.

- [ ] **Step 9: Add validation in `ScenarioCrudService`**

Add a constant next to the other bounds (~:69):

```java
    private static final int MIN_BIRTH_MONTH = 1;
    private static final int MAX_BIRTH_MONTH = 12;
```

Add `validateBirthMonths(request);` immediately after `validateStochasticMortalityFields(request);` in BOTH `createScenario` (~:112) and `updateScenario` (~:164).

Add this method after `validateLongevityConditionalAge`:

```java
    /**
     * Phase 1a: birth months (when present) must be 1-12. Each month needs its matching birth
     * year: a month with no year is meaningless, and the engine would pair it with the
     * {@code currentYear - 35} fallback birth year.
     */
    private static void validateBirthMonths(ScenarioParamsSource request) {
        validateBirthMonth(request.birthMonth(), "birth_month");
        validateBirthMonth(request.spouseBirthMonth(), "spouse_birth_month");
        if (request.birthMonth() != null && request.birthYear() == null) {
            throw new IllegalArgumentException("birth_month requires birth_year to be set");
        }
        if (request.spouseBirthMonth() != null && request.spouseBirthYear() == null) {
            throw new IllegalArgumentException("spouse_birth_month requires spouse_birth_year to be set");
        }
    }

    private static void validateBirthMonth(Integer birthMonth, String fieldName) {
        if (birthMonth == null) {
            return;
        }
        if (birthMonth < MIN_BIRTH_MONTH || birthMonth > MAX_BIRTH_MONTH) {
            throw new IllegalArgumentException(fieldName + " must be between " + MIN_BIRTH_MONTH
                    + " and " + MAX_BIRTH_MONTH);
        }
    }
```

- [ ] **Step 10: Add `birthMonth` to `GuardrailOptimizationInput`**

In `GuardrailOptimizationInput.java`:

1. Replace the last header component `        @Nullable MortalityTable mortalityTable\n) {` with:

```java
        @Nullable MortalityTable mortalityTable,
        // Phase 1a: the primary's birth month (1-12, null = unknown). Resolved engine-side to the
        // early-access (59 1/2) age via AgeMilestones; null => legacy whole-year age 60.
        @Nullable Integer birthMonth
) {
```

2. In the 28-arg back-compat constructor body, change the final line `                null, null, null, null, null);` to `                null, null, null, null, null, null);` and add a trailing comment `// Phase 1a: birthMonth null (legacy age 60)`.

3. In `Builder`, add the field `private Integer birthMonth;` after `private MortalityTable mortalityTable;`, and this setter after `mortalityTable(...)`:

```java
        public Builder birthMonth(@Nullable Integer birthMonth) {
            this.birthMonth = birthMonth;
            return this;
        }
```

4. In `Builder.build()`, change the final `                    mortalityTable);` to `                    mortalityTable, birthMonth);`.

5. Update the Javadoc `@param` list above the record with `@param birthMonth the primary's birth month (1-12) or null; see AgeMilestones`.

Fix the two test call sites of the canonical constructor:
- `StochasticMortalityGoldenTest.java` ~:153: change `stochastic ? "female" : null, 95, table);` to `stochastic ? "female" : null, 95, table, null);`.
- `MonteCarloSpendingOptimizerCharacterizationTest.java` ~:68: change `null, null, null, null, false, null, null, null, null, null);   // household task 6: single-person` to `null, null, null, null, false, null, null, null, null, null, null);   // household task 6: single-person; Phase 1a birthMonth null`.

Add to `backend/wealthview-projection/src/test/java/com/wealthview/projection/testutil/GuardrailOptimizationInputBuilder.java` (next to `withBirthYear`):

```java
    public GuardrailOptimizationInputBuilder withBirthMonth(@Nullable Integer birthMonth) {
        delegate.birthMonth(birthMonth);
        return this;
    }
```

- [ ] **Step 11: Wire birth month into `GuardrailProfileService` and `ProjectionInputBuilder`**

In `GuardrailProfileService.buildOptimizationInput`, add after `.mortalityTable(projectionInputBuilder.resolveMortalityTable(params))`:

```java
                // Phase 1a: raw birth month; OptimizationContextBuilder resolves the early-access age.
                .birthMonth(params.birthMonth())
```

In `ProjectionInputBuilder.resolveHousehold`, replace:

```java
        if (params.spouseBirthYear() == null) {
            return HouseholdContext.single(primaryBirthYear);
        }
```

with:

```java
        if (params.spouseBirthYear() == null) {
            return HouseholdContext.single(primaryBirthYear).withBirthMonths(params.birthMonth(), null);
        }
```

and replace the final `return HouseholdContext.of(...)` statement with:

```java
        return HouseholdContext.of(primaryBirthYear, primaryDeathAge,
                params.spouseBirthYear(), spouseDeathAge, horizonEndYear)
                .withBirthMonths(params.birthMonth(), params.spouseBirthMonth());
```

Update `resolveHousehold`'s Javadoc with one sentence: "Phase 1a: both persons carry their optional birth month (null = unknown)."

- [ ] **Step 12: Add `ScenarioParamsParser.earlyAccessAge`**

In `backend/wealthview-projection/src/main/java/com/wealthview/projection/ScenarioParamsParser.java`, add the import `import com.wealthview.core.projection.household.AgeMilestones;` (alphabetical among the `com.wealthview` imports) and this method after `includeDepressionYears`:

```java
    /**
     * Phase 1a: the age of the calendar year the primary reaches 59½ (penalty-free from that year
     * on). Without a birth month this is the legacy whole-year proxy, 60.
     */
    int earlyAccessAge(ScenarioParams params) {
        return AgeMilestones.earlyAccessAge(params.birthMonth());
    }
```

- [ ] **Step 13: Run the tests and confirm they pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='ScenarioParamsTest,ScenarioCrudServiceTest,GuardrailProfileServiceTest,ProjectionInputBuilderTest,ScenarioParamsParserTest,StochasticMortalityGoldenTest,MonteCarloSpendingOptimizerCharacterizationTest,GuardrailOptimizationInputTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. The two pinned MC tests are unchanged numerically, because birthMonth null keeps age 60.

- [ ] **Step 14: Run the module quality gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs` and then `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS (PMD, CPD, SpotBugs, Checkstyle, JaCoCo all green).

- [ ] **Step 15: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParams.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParamsSource.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioRequest.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationInput.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/ScenarioCrudService.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionInputBuilder.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/ScenarioParamsParser.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/testutil/ScenarioRequestBuilder.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/ScenarioParamsTest.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/ScenarioCrudServiceTest.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionInputBuilderTest.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/ScenarioParamsParserTest.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/testutil/GuardrailOptimizationInputBuilder.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/StochasticMortalityGoldenTest.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerCharacterizationTest.java
git commit -F - <<'EOF'
feat(core): accept birth_month and spouse_birth_month on scenarios

Adds two optional params_json fields (1-12), validated in
ScenarioCrudService: each month requires its matching birth year, so a
month can't be paired with the engine's currentYear-35 fallback. The
months reach the household persons (ProjectionInputBuilder) and the
Monte Carlo input (GuardrailOptimizationInput.birthMonth).
ScenarioParamsParser.earlyAccessAge resolves the 59 1/2 year via
AgeMilestones.

Unset months keep today's whole-year age-60 proxy. No migration: the
fields live in params_json. The engines start consuming the
early-access age in the next commit.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: Engine early-access sites → `earlyAccessAge` (deterministic, MC, conversion simulator)

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`:
  - `PoolConfig` record (~:463-480): new last component `int earlyAccessAge`
  - the two back-compat constructors (~:490, ~:507)
  - `Builder` (~:539-612): field, setter, `build()`
  - `MultiPool` field and constructor (~:937-950)
  - `executeWithdrawals` `WithdrawalContext` (~:1111) and the penalty (~:1211)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/WithdrawalOrderStrategy.java`: `WithdrawalContext` (~:34) gains `int earlyAccessAge` plus a 5-arg back-compat constructor; DS check (~:78)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java`: `buildPoolStrategy` (~:291) adds `.earlyAccessAge(paramsParser.earlyAccessAge(params))`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialSimulator.java`:
  - `SimulationConfig` (~:200-228): new last component `int earlyAccessAge`
  - `Builder`: field, setter, `build()`
  - sites at :571, :602/:765, :541/:1045
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/SimulationParameters.java`: new last component `int earlyAccessAge`; `emptyHorizon`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/OptimizationContextBuilder.java`: canonical `SimulationParameters` call (~:211)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialConfigFactory.java`: field plus `ages(int,int,int)`
- Modify: the `.ages(...)` callers: `SustainabilitySearch.java:478` (plus a `SearchContext.earlyAccessAge()` accessor ~:105), `StochasticMortalityEvaluator.java:67`, `GuardrailResponseBuilder.java:215`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/RothConversionConfig.java` (new last component), `RothConversionOptimizer.java` (ctor and `Builder.earlyAccessAge`), `ConversionSimulator.java` (:184, :374, :410), `JointConversionSearch.java` (~:52 builder chain)
- Delete: `backend/wealthview-projection/src/main/java/com/wealthview/projection/RetirementAges.java`
- Test (update to the new constant / shape): `MultiPoolDeepTest.java:788`, `TrialSimulatorReturnTest.java:699`, `TrialSimulatorConfigBuilderTest.java:24,:67`, `TrialSimulatorStochasticTest.java:36`, `SustainabilitySearchTest.java:112`, `ConversionSimulatorWithdrawalOrderTest.java:69`, `ConversionSimulatorRmdConservationTest.java:43`
- Test (new cases): `MultiPoolDeepTest`, `DeterministicProjectionEngineWithdrawalTest`, `TrialSimulatorReturnTest`, `OptimizationContextBuilderTest`, new `ConversionSimulatorEarlyAccessTest`

**Interfaces:**
- Consumes: `AgeMilestones.LEGACY_EARLY_ACCESS_AGE`, `AgeMilestones.earlyAccessAge(Integer)` (Task 1); `ScenarioParamsParser.earlyAccessAge(ScenarioParams)`, `GuardrailOptimizationInput.birthMonth()` (Task 2).
- Produces:
  - `PoolStrategy.PoolConfig.earlyAccessAge()` and `PoolConfig.Builder.earlyAccessAge(int)` (default 60)
  - `WithdrawalOrderStrategy.WithdrawalContext(BigDecimal, BigDecimal, BigDecimal, int age, int year, int earlyAccessAge)` plus a 5-arg constructor (60)
  - `TrialSimulator.SimulationConfig.earlyAccessAge()` and `SimulationConfig.Builder.earlyAccessAge(int)` (default 60)
  - `SimulationParameters.earlyAccessAge()`
  - `TrialConfigFactory.Builder.ages(int retirementAge, int rmdStartAge, int earlyAccessAge)`
  - `RothConversionConfig.earlyAccessAge()` and `RothConversionOptimizer.Builder.earlyAccessAge(int)` (default 60)
  - Every check is `age < earlyAccessAge`. `RetirementAges` no longer exists.

- [ ] **Step 1: Write the failing deterministic tests**

Append to `MultiPoolDeepTest` in the `=== T18a-4 ...` section (`bd`, `ZERO`, `YEAR`, `grouped`, `FlatTaxStubs`, `WithdrawalOrder`, `FilingStatus` are in scope; add `import com.wealthview.core.projection.household.AgeMilestones;` if absent):

```java
    // Phase 1a: birth month moves the first penalty-free year to the 59 1/2 calendar year.

    private PoolStrategy.MultiPool poolWithEarlyAccessAge(WithdrawalOrder order, int earlyAccessAge,
                                                         BigDecimal dsBracketRate) {
        var config = PoolStrategy.PoolConfig.builder(FilingStatus.SINGLE, ZERO, ZERO, "fixed",
                        null, null, order, FlatTaxStubs.flatTaxStrategy("0.10"), dsBracketRate)
                .earlyAccessAge(earlyAccessAge)
                .build();
        return new PoolStrategy.MultiPool(grouped("50000", "100000", "0", "0", "0", "0"), ZERO, config);
    }

    @Test
    void executeWithdrawals_age59WithEarlyAccessAge59_noPenalty() {
        var pool = poolWithEarlyAccessAge(WithdrawalOrder.TRADITIONAL_FIRST, 59, null);

        var result = pool.executeWithdrawals(bd("10000"), YEAR, ZERO, ZERO, ZERO, 59, ZERO, ZERO, ZERO);

        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("10000"));
        assertThat(result.earlyWithdrawalPenalty()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeWithdrawals_age59WithLegacyEarlyAccessAge_appliesPenalty() {
        var pool = poolWithEarlyAccessAge(WithdrawalOrder.TRADITIONAL_FIRST,
                AgeMilestones.LEGACY_EARLY_ACCESS_AGE, null);

        var result = pool.executeWithdrawals(bd("10000"), YEAR, ZERO, ZERO, ZERO, 59, ZERO, ZERO, ZERO);

        assertThat(result.earlyWithdrawalPenalty()).isEqualByComparingTo(bd("1000"));
    }

    @Test
    void executeWithdrawals_dynamicSequencingAge59WithEarlyAccessAge59_drawsTraditionalInBracket() {
        var pool = poolWithEarlyAccessAge(WithdrawalOrder.DYNAMIC_SEQUENCING, 59, new BigDecimal("0.12"));

        var result = pool.executeWithdrawals(bd("10000"), YEAR, ZERO, ZERO, ZERO, 59, ZERO, ZERO, ZERO);

        // Flat stub bracket ceiling is 100,000 -> the whole 10,000 fits; pre-fix (age-60 proxy)
        // dynamic sequencing would have forced taxable-only at 59.
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("10000"));
        assertThat(result.fromTaxable()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeWithdrawals_dynamicSequencingAge59WithLegacyEarlyAccessAge_drawsTaxableOnly() {
        var pool = poolWithEarlyAccessAge(WithdrawalOrder.DYNAMIC_SEQUENCING,
                AgeMilestones.LEGACY_EARLY_ACCESS_AGE, new BigDecimal("0.12"));

        var result = pool.executeWithdrawals(bd("10000"), YEAR, ZERO, ZERO, ZERO, 59, ZERO, ZERO, ZERO);

        assertThat(result.fromTraditional()).isEqualByComparingTo(ZERO);
        assertThat(result.fromTaxable()).isEqualByComparingTo(bd("10000"));
    }
```

Change the existing call at `MultiPoolDeepTest.java:788` from `RetirementAges.EARLY_WITHDRAWAL_AGE` to `AgeMilestones.LEGACY_EARLY_ACCESS_AGE`.

Append to `DeterministicProjectionEngineWithdrawalTest`, after `run_traditionalFirstRetireeAtAge60_noEarlyWithdrawalPenalty` (~:575):

```java
    // Phase 1a: birth month. Born March -> reaches 59 1/2 in the year they turn 59 -> that
    // year is penalty-free. Born September -> 59 1/2 lands in the age-60 year -> unchanged.

    private ProjectionInput age59TraditionalFirstInput(String birthMonthJson) {
        int birthYear = LocalDate.now().getYear() - 59;
        return createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d,%s "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "traditional_first"}
                """.formatted(birthYear, birthMonthJson),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "taxable")));
    }

    @Test
    void run_age59BornMarch_noEarlyWithdrawalPenalty() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var year1 = engineTax.run(age59TraditionalFirstInput(" \"birth_month\": 3,")).yearlyData().getFirst();

        assertThat(year1.age()).isEqualTo(59);
        assertThat(year1.withdrawalFromTraditional()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.earlyWithdrawalPenalty()).isNull();
    }

    @Test
    void run_age59BornSeptember_stillPaysEarlyWithdrawalPenalty() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var year1 = engineTax.run(age59TraditionalFirstInput(" \"birth_month\": 9,")).yearlyData().getFirst();

        assertThat(year1.earlyWithdrawalPenalty())
                .isEqualByComparingTo(year1.withdrawalFromTraditional().multiply(bd("0.10")));
    }

    @Test
    void run_age59NoBirthMonth_stillPaysEarlyWithdrawalPenalty() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var year1 = engineTax.run(age59TraditionalFirstInput("")).yearlyData().getFirst();

        assertThat(year1.earlyWithdrawalPenalty())
                .isEqualByComparingTo(year1.withdrawalFromTraditional().multiply(bd("0.10")));
    }
```

(If `ProjectionInput` isn't imported in that test, add `import com.wealthview.core.projection.dto.ProjectionInput;`.)

- [ ] **Step 2: Write the failing MC tests**

In `TrialSimulatorReturnTest`, add `import com.wealthview.core.projection.household.AgeMilestones;`. Change `penaltyTestConfig(RetirementAges.EARLY_WITHDRAWAL_AGE)` at ~:699 to `penaltyTestConfig(AgeMilestones.LEGACY_EARLY_ACCESS_AGE)`. Then append:

```java
    // Phase 1a: SimulationConfig.earlyAccessAge (from birth month) replaces the age-60 proxy.

    @Test
    void simulateTrial_age59WithEarlyAccessAge59_noPenalty() {
        var config = TrialSimulator.SimulationConfig.builder(50_000.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{0.0})
                .retirementAge(59)
                .earlyAccessAge(59)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1, config);

        // Only the $1,000 ordinary tax leaves: 139,000 (same as the at-60 case).
        assertThat(result.finalBalance()).isEqualTo(139_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_age59WithDefaultEarlyAccessAge_appliesPenalty() {
        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1,
                penaltyTestConfig(59));

        assertThat(result.finalBalance()).isEqualTo(138_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_conversionScheduleAge59WithEarlyAccessAge59_mayDrawTraditional() {
        // A non-null (zero) conversion schedule switches on the pre-59 1/2 taxable-only rule. With
        // earlyAccessAge 59 the age-59 year is past it, so traditional_first draws traditional.
        var config = TrialSimulator.SimulationConfig.builder(50_000.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{0.0})
                .conversions(new double[]{0.0}, new double[]{0.0})
                .retirementAge(59)
                .earlyAccessAge(59)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1, config);

        // Traditional pays 10,000 + 1,000 tax funded from taxable: 49,000 + 90,000 = 139,000.
        assertThat(result.finalBalance()).isEqualTo(139_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_conversionScheduleAge59WithDefaultEarlyAccessAge_drawsTaxableOnly() {
        var config = TrialSimulator.SimulationConfig.builder(50_000.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{0.0})
                .conversions(new double[]{0.0}, new double[]{0.0})
                .retirementAge(59)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1, config);

        // Taxable-only at basis = value: no gain, no ordinary tax, no penalty -> 140,000.
        assertThat(result.finalBalance()).isEqualTo(140_000.0, within(1e-6));
    }
```

Append to `OptimizationContextBuilderTest`:

```java
    @Test
    void build_birthMonthMarch_resolvesEarlyAccessAge59() {
        var input = GuardrailOptimizationInputBuilder.builder().withBirthMonth(3).build();

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(setup.sim().earlyAccessAge()).isEqualTo(59);
    }

    @Test
    void build_birthMonthAbsent_resolvesLegacyEarlyAccessAge60() {
        var input = GuardrailOptimizationInputBuilder.builder().withBirthMonth(null).build();

        var setup = builder.build(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(setup.sim().earlyAccessAge()).isEqualTo(60);
    }
```

- [ ] **Step 3: Write the failing conversion-simulator test**

Create `backend/wealthview-projection/src/test/java/com/wealthview/projection/ConversionSimulatorEarlyAccessTest.java`:

```java
package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.household.AgeMilestones;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1a: the conversion simulator's two pre-59½ rules (taxable-only spending and the
 * conversion-tax affordability cap) key off {@code RothConversionConfig.earlyAccessAge}. With a
 * March birth (early-access age 59), the age-59 year is no longer "early".
 *
 * <p>Fixture: one year at age 59, flat 20% tax, a mocked bracket ceiling of $100,000, no target
 * balance (0 disables the RMD-target cap), so the conversion is sized purely by bracket space and
 * the affordability rule.
 */
class ConversionSimulatorEarlyAccessTest {

    private static final double RETURN_MEAN = 0.0;
    private static final double INIT_TRADITIONAL = 1_000_000;
    private static final double THIN_TAXABLE = 10_000;
    private static final double AMPLE_TAXABLE = 200_000;
    private static final double ESSENTIAL_FLOOR = 40_000;
    private static final int BIRTH_YEAR = 1960;
    private static final int AGE = 59;

    private FederalTaxCalculator flatTwentyPercent() {
        var calc = mock(FederalTaxCalculator.class);
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    return income.signum() <= 0 ? BigDecimal.ZERO : income.multiply(new BigDecimal("0.20"));
                });
        when(calc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class))).thenReturn(new BigDecimal("100000"));
        return calc;
    }

    private SimResult simulate(String order, double initTaxable, double fraction, int earlyAccessAge) {
        int endAge = AGE + 1;
        var config = new RothConversionConfig(
                INIT_TRADITIONAL, 0.0, initTaxable,
                new double[]{0.0}, new double[]{0.0},
                BIRTH_YEAR, AGE, endAge, 5,
                0.22, 0.12, RETURN_MEAN,
                ESSENTIAL_FLOOR,
                FilingStatus.SINGLE, flatTwentyPercent(),
                order, 0.10, 0.0,
                endAge - AGE, RmdCalculator.rmdStartAge(BIRTH_YEAR),
                new RentalAdjustmentCalculator(List.of(), new RentalLossCalculator(), BIRTH_YEAR, AGE),
                earlyAccessAge);
        return new ConversionSimulator(config, 0.0).simulateForFraction(fraction);
    }

    @Test
    void simulateForFraction_age59WithEarlyAccessAge59_spendingUsesOrderedStrategy() {
        var result = simulate("traditional_first", AMPLE_TAXABLE, 0.0, 59);

        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL - ESSENTIAL_FLOOR, offset(1e-6));
        assertThat(result.taxableBalance()[0]).isEqualTo(AMPLE_TAXABLE, offset(1e-6));
    }

    @Test
    void simulateForFraction_age59WithLegacyEarlyAccessAge_spendingIsTaxableOnly() {
        var result = simulate("traditional_first", AMPLE_TAXABLE, 0.0, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);

        assertThat(result.traditionalBalance()[0]).isEqualTo(INIT_TRADITIONAL, offset(1e-6));
        assertThat(result.taxableBalance()[0]).isEqualTo(AMPLE_TAXABLE - ESSENTIAL_FLOOR, offset(1e-6));
    }

    @Test
    void simulateForFraction_thinTaxableAge59_affordabilityCapOnlyAppliesBeforeEarlyAccess() {
        var capped = simulate("taxable_first", THIN_TAXABLE, 1.0, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);
        var uncapped = simulate("taxable_first", THIN_TAXABLE, 1.0, 59);

        // Legacy: taxable (10k) minus the essential need (40k) is <= 0, so the pre-59 1/2
        // affordability cap zeroes the conversion. Early-access 59: the cap is off, so the full
        // bracket space converts.
        assertThat(capped.conversionByYear()[0]).isEqualTo(0.0, offset(1e-6));
        assertThat(uncapped.conversionByYear()[0]).isGreaterThan(0.0);
    }
}
```

Update the two existing canonical `RothConversionConfig` test constructions to pass the new last argument:
- `ConversionSimulatorWithdrawalOrderTest.java` ~:76: change `                rentalCalc);` to `                rentalCalc, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);`. Add the import `import com.wealthview.core.projection.household.AgeMilestones;`, and change the `RETIREMENT_AGE` comment to `// >= the legacy early-access age (60), < 75`.
- `ConversionSimulatorRmdConservationTest.java` ~:52: same change and import.

Update the other canonical constructions (add the `AgeMilestones` import to each file):
- `TrialSimulatorStochasticTest.java` ~:42: `regimes, survivorFactor);` → `regimes, survivorFactor, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);`
- `TrialSimulatorConfigBuilderTest.java` ~:27: `0.0, null, 0.0, null, null, 0.0, 1.0, null, null, 1.0);` → `0.0, null, 0.0, null, null, 0.0, 1.0, null, null, 1.0, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);`
- `TrialSimulatorConfigBuilderTest.java` all-knobs test: add `.earlyAccessAge(59)` to the builder chain before `.build()`, and change the canonical's last line `80.0, ltcgTables, 0.015, null, rental, 0.04, 0.7, null, regimes, 0.75);` → `80.0, ltcgTables, 0.015, null, rental, 0.04, 0.7, null, regimes, 0.75, 59);`
- `SustainabilitySearchTest.java` ~:114: `0.0, 0.0, 0.0, 0.0, 1.0, 1.0, null, null, null);` → `0.0, 0.0, 0.0, 0.0, 1.0, 1.0, null, null, null, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);`

- [ ] **Step 4: Run them and confirm they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='MultiPoolDeepTest,DeterministicProjectionEngineWithdrawalTest,TrialSimulatorReturnTest,OptimizationContextBuilderTest,ConversionSimulatorEarlyAccessTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE: `cannot find symbol ... earlyAccessAge`.

- [ ] **Step 5: Deterministic engine**

`WithdrawalOrderStrategy.java`:

1. Replace the `WithdrawalContext` record with:

```java
    /**
     * Per-withdrawal context (income, conversion, RMD, age and year) consumed by the
     * dynamic-sequencing order. Collapses what would otherwise be a long parameter list.
     * {@code earlyAccessAge} (Phase 1a) is the first age at which traditional draws are
     * penalty-free; see {@code AgeMilestones}.
     */
    record WithdrawalContext(BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                             BigDecimal rmdAmount, int age, int year, int earlyAccessAge) {

        /** Pre-Phase-1a shape: legacy whole-year early-access age (60). */
        WithdrawalContext(BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                          BigDecimal rmdAmount, int age, int year) {
            this(effectiveOtherIncome, conversionAmount, rmdAmount, age, year,
                    AgeMilestones.LEGACY_EARLY_ACCESS_AGE);
        }
    }
```

2. In `DynamicSequencingOrder.execute`, replace

```java
            if (context.age() < RetirementAges.EARLY_WITHDRAWAL_AGE) {
                // Before 59.5 (using 60 as proxy): taxable only to avoid early withdrawal penalties
```

with

```java
            if (context.age() < context.earlyAccessAge()) {
                // Before 59.5 (the calendar year of 59.5 from AgeMilestones): taxable only to avoid
                // early withdrawal penalties
```

3. Add the import `import com.wealthview.core.projection.household.AgeMilestones;`.

`PoolStrategy.java`:

1. In `PoolConfig`, replace `            FederalTaxCalculator federalTaxCalculator) {` (end of the record header) with:

```java
            FederalTaxCalculator federalTaxCalculator,
            // Phase 1a: first penalty-free age (59 when born Jan-Jun, else 60). See AgeMilestones.
            int earlyAccessAge) {
```

2. In both back-compat `PoolConfig` constructors, change the trailing `..., BigDecimal.ZERO, 0, null);` to `..., BigDecimal.ZERO, 0, null, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);`.

3. In `PoolConfig.Builder`, add the field `private int earlyAccessAge = AgeMilestones.LEGACY_EARLY_ACCESS_AGE;` after `private FederalTaxCalculator federalTaxCalculator;`. Add this setter after `federalTaxCalculator(...)`:

```java
            Builder earlyAccessAge(int earlyAccessAge) {
                this.earlyAccessAge = earlyAccessAge;
                return this;
            }
```

   Change `build()`'s final `dividendYield, interestYield, feeRate, baseYear, federalTaxCalculator);` to `dividendYield, interestYield, feeRate, baseYear, federalTaxCalculator, earlyAccessAge);`. Update the Builder Javadoc's default list to mention "early-access age 60".

4. In `MultiPool`, add `private final int earlyAccessAge;` next to `private final int baseYear;`, and `this.earlyAccessAge = config.earlyAccessAge();` after `this.baseYear = config.baseYear();`.

5. In `executeWithdrawals` (~:1111), change the context construction to:

```java
                var withdrawalContext = new WithdrawalOrderStrategy.WithdrawalContext(
                        effectiveOtherIncome, conversionAmount, rmdAmount, age, year, earlyAccessAge);
```

6. At the penalty (~:1201-1213), change `age < RetirementAges.EARLY_WITHDRAWAL_AGE` to `age < earlyAccessAge`, and change the comment line `(whole-year proxy: age < RetirementAges.EARLY_WITHDRAWAL_AGE, the SAME proxy the` to `(age < earlyAccessAge from AgeMilestones -- the 59 1/2 calendar year, legacy 60 -- the SAME threshold the`.

7. Change the Javadoc reference at ~:429 `{@link RetirementAges#EARLY_WITHDRAWAL_AGE}` to `the pool's early-access age ({@code AgeMilestones})`.

8. Add the import `import com.wealthview.core.projection.household.AgeMilestones;`.

`DeterministicProjectionEngine.buildPoolStrategy`: add `.earlyAccessAge(paramsParser.earlyAccessAge(params))` after `.federalTaxCalculator(federalTaxCalculator)`.

- [ ] **Step 6: MC engine**

`TrialSimulator.java`:

1. In the `SimulationConfig` header, replace `            double survivorFactor\n    ) {` with:

```java
            double survivorFactor,
            // Phase 1a: first penalty-free age (AgeMilestones; legacy 60).
            int earlyAccessAge
    ) {
```

2. In `SimulationConfig.Builder`, add the field `private int earlyAccessAge = AgeMilestones.LEGACY_EARLY_ACCESS_AGE;` after `private double survivorFactor = 1.0;`. Add this setter before `build()`:

```java
            Builder earlyAccessAge(int earlyAccessAge) {
                this.earlyAccessAge = earlyAccessAge;
                return this;
            }
```

   Change `build()`'s last line `household, survivorRegimes, survivorFactor);` to `household, survivorRegimes, survivorFactor, earlyAccessAge);`. Add "early-access age 60" to the Builder Javadoc default list.

3. :571: `boolean preAge595 = hasConversions && age < RetirementAges.EARLY_WITHDRAWAL_AGE;` → `boolean preAge595 = hasConversions && age < config.earlyAccessAge();`

4. :541-542 call: `config.conversionTaxByYear(), y, age, table, baseWithInterest + rmdForced);` → `config.conversionTaxByYear(), y, age, config.earlyAccessAge(), table, baseWithInterest + rmdForced);`

5. :602 call: `applyEarlyWithdrawalPenalty(tp, outcome.traditionalDrawn(), age);` → `applyEarlyWithdrawalPenalty(tp, outcome.traditionalDrawn(), age, config.earlyAccessAge());`

6. `applyEarlyWithdrawalPenalty`: change the signature to `(TrialPools tp, double traditionalDrawn, int age, int earlyAccessAge)` and the condition to `age < earlyAccessAge`. In its Javadoc, replace `(whole-year proxy: {@link RetirementAges#EARLY_WITHDRAWAL_AGE} = 60, already used above by` with `(the 59 1/2 calendar year from {@code AgeMilestones}, legacy 60 -- already used above by`.

7. `applyTrialConversion`: change the signature to `(TrialPools tp, double[] conversionByYear, double[] conversionTaxByYear, int y, int age, int earlyAccessAge, OrdinaryTaxTable table, double base)` and the condition `if (age < RetirementAges.EARLY_WITHDRAWAL_AGE) {` to `if (age < earlyAccessAge) {`.

8. Add the import `import com.wealthview.core.projection.household.AgeMilestones;`.

`SimulationParameters.java`: replace the header's last component `        @Nullable StochasticEvalArrays stochasticEval\n) {` with:

```java
        @Nullable StochasticEvalArrays stochasticEval,
        // Phase 1a: first penalty-free age, resolved once from the birth month (AgeMilestones).
        int earlyAccessAge
) {
```

In `emptyHorizon`, change `null, null, null, null, rmdStartAge, 0, 0, 0, 0, 1, 1.0, null, null, null);` to `null, null, null, null, rmdStartAge, 0, 0, 0, 0, 1, 1.0, null, null, null, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);`, and add the import.

`OptimizationContextBuilder.build`:
- After `int rmdStartAge = RmdCalculator.rmdStartAge(input.birthYear());`, add `int earlyAccessAge = AgeMilestones.earlyAccessAge(input.birthMonth());`.
- In the canonical `new SimulationParameters(...)` (~:211), change `mortalityDraws, stochasticEval),` to `mortalityDraws, stochasticEval, earlyAccessAge),`.
- Add the import.

`TrialConfigFactory.java`:
- Add the field `private final int earlyAccessAge;`, and `this.earlyAccessAge = b.earlyAccessAge;` in the constructor.
- In `configFor`, add `.earlyAccessAge(earlyAccessAge)` after `.rmdStartAge(rmdStartAge)`.
- In `Builder`, add `private int earlyAccessAge = AgeMilestones.LEGACY_EARLY_ACCESS_AGE;` and replace `ages(...)` with:

```java
        /** Combined setter (task 12 / CPD): every one of the three call sites sets these age knobs
         * together, so one call replaces the separate {@code retirementAge}/{@code rmdStartAge}/
         * {@code earlyAccessAge} setters {@link TrialSimulator.SimulationConfig.Builder} carries --
         * this also keeps the two builders' method shapes from lining up token-for-token. */
        Builder ages(int retirementAge, int rmdStartAge, int earlyAccessAge) {
            this.retirementAge = retirementAge;
            this.rmdStartAge = rmdStartAge;
            this.earlyAccessAge = earlyAccessAge;
            return this;
        }
```

- Add the import.

Update the three `.ages(...)` callers:
- `SustainabilitySearch.java:478` → `.ages(ctx.retirementAge(), ctx.rmdStartAge(), ctx.earlyAccessAge())`. Add to `SearchContext`, after `retirementAge()`:

```java
        int earlyAccessAge() {
            return setup.sim().earlyAccessAge();
        }
```

- `StochasticMortalityEvaluator.java:67` → `.ages(sim.retirementAge(), sim.rmdStartAge(), sim.earlyAccessAge())`
- `GuardrailResponseBuilder.java:215` → `.ages(ctx.sim().retirementAge(), ctx.sim().rmdStartAge(), ctx.sim().earlyAccessAge())`

- [ ] **Step 7: Conversion simulator**

`RothConversionConfig.java`: replace `        RentalAdjustmentCalculator rentalAdjustmentCalculator) {` with:

```java
        RentalAdjustmentCalculator rentalAdjustmentCalculator,
        // Phase 1a: first penalty-free age (AgeMilestones; legacy 60).
        int earlyAccessAge) {
```

`RothConversionOptimizer.java`:
- Private constructor: add a trailing parameter `int earlyAccessAge` after `double dynamicSequencingBracketRate`. In `new RothConversionConfig(...)`, change `rentalAdjustmentCalculator);` to `rentalAdjustmentCalculator, earlyAccessAge);`.
- `Builder`: add the field `private int earlyAccessAge = AgeMilestones.LEGACY_EARLY_ACCESS_AGE;` and this setter after `dynamicSequencingBracketRate(double)`:

```java
        Builder earlyAccessAge(int earlyAccessAge) {
            this.earlyAccessAge = earlyAccessAge;
            return this;
        }
```

- `build()`: change `rmdBracketHeadroom, dynamicSequencingBracketRate);` to `rmdBracketHeadroom, dynamicSequencingBracketRate, earlyAccessAge);`.
- Add the import `import com.wealthview.core.projection.household.AgeMilestones;`.

`ConversionSimulator.java`:
- Add the field `private final int earlyAccessAge;` and `this.earlyAccessAge = config.earlyAccessAge();` in the constructor.
- :184 `if (age < RetirementAges.EARLY_WITHDRAWAL_AGE) {` → `if (age < earlyAccessAge) {`
- :374 (`selectWithdrawalStrategy`): same change.
- :410 (`constrainConversionByAffordability`): `if (age >= RetirementAges.EARLY_WITHDRAWAL_AGE) {` → `if (age >= earlyAccessAge) {`
- Javadoc at ~:404: `Only applies before age 59.5 (RetirementAges.EARLY_WITHDRAWAL_AGE), when penalty-free` → `Only applies before the early-access age (the 59 1/2 calendar year, AgeMilestones), when penalty-free`.
- Javadoc on `EarlyWithdrawalStrategy` (~:261): `Before age 59.5` → `Before the early-access age (59 1/2 calendar year)`.

`JointConversionSearch.java`: in the `RothConversionOptimizer.builder()` chain (after `.dynamicSequencingBracketRate(...)`), add:

```java
                .earlyAccessAge(ctx.sim().earlyAccessAge())
```

Delete `backend/wealthview-projection/src/main/java/com/wealthview/projection/RetirementAges.java`.

- [ ] **Step 8: Confirm no references remain**

Run: `cd backend && grep -rn "RetirementAges\|EARLY_WITHDRAWAL_AGE" --include=*.java . | grep -v /target/`
Expected: no output.

- [ ] **Step 9: Run the targeted tests and confirm they pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='MultiPoolDeepTest,DeterministicProjectionEngineWithdrawalTest,TrialSimulatorReturnTest,TrialSimulatorConfigBuilderTest,TrialSimulatorStochasticTest,SustainabilitySearchTest,OptimizationContextBuilderTest,ConversionSimulatorEarlyAccessTest,ConversionSimulatorWithdrawalOrderTest,ConversionSimulatorRmdConservationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 10: Run the whole projection module, goldens included, and confirm byte-identical goldens**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS. `ProjectionGoldenFileTest` passes unchanged: no golden input sets `birth_month`, so every early-access age stays 60. `MonteCarloSpendingOptimizerCharacterizationTest` and `StochasticMortalityGoldenTest` pins hold. If a golden or pin moves, the threading is wrong. Fix it; never regenerate.

- [ ] **Step 11: Commit**

```bash
git add -A backend/wealthview-projection/src
git commit -F - <<'EOF'
feat(projection): gate early-access rules on the 59 1/2 calendar year

The ten "age < 60" checks (deterministic 10% penalty and dynamic
sequencing; MC penalty, pre-59 1/2 taxable-only split and conversion-tax
funding; the conversion simulator's spending strategy, tax funding and
affordability cap) now compare against an earlyAccessAge resolved once
per run from the birth month via AgeMilestones. Someone born
January-June is penalty-free in the calendar year they turn 59.

The value is threaded through PoolConfig/WithdrawalContext,
SimulationConfig/SimulationParameters/TrialConfigFactory, and
RothConversionConfig, defaulting to 60. RetirementAges is deleted.

No birth month means 60, so goldens and MC characterization pins are
unchanged.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: Frontend birth-month fields

**Files:**
- Modify: `frontend/src/types/projection.ts`: `CreateScenarioRequest` (~:198, after `include_depression_years`)
- Modify: `frontend/src/components/scenario/scenarioFormFields.ts`: two fields plus a `MONTH_OPTIONS` constant
- Modify: `frontend/src/components/ScenarioForm.tsx`: `buildInitialFields` (~:72-106), destructure (~:154-163), `handleSpouseBirthYearChange` (~:171-187), `handleSubmit` request (~:249-302)
- Modify: `frontend/src/components/scenario/ScenarioBasicsSection.tsx`: Birth Month select after Birth Year
- Modify: `frontend/src/components/scenario/ScenarioHouseholdSection.tsx`: Spouse Birth Month select after Spouse Birth Year (shown only when there's a spouse)
- Test: `frontend/src/components/ScenarioForm.test.tsx`

**Interfaces:**
- Consumes: the backend wire fields `birth_month` and `spouse_birth_month` (Task 2).
- Produces:
  - `CreateScenarioRequest.birth_month?: number | null` and `spouse_birth_month?: number | null`
  - `ScenarioFormFields.birthMonth: number | null` and `spouseBirthMonth: number | null`
  - `MONTH_OPTIONS: ReadonlyArray<{ value: number; label: string }>` exported from `scenarioFormFields.ts`

- [ ] **Step 1: Write the failing form tests**

Append inside `describe('ScenarioForm', ...)` in `frontend/src/components/ScenarioForm.test.tsx` (`labeledInput`, `setupMocks`, `ScenarioForm`, `render`, `fireEvent`, `screen`, `waitFor`, `vi` are already in scope):

```tsx
    describe('birth month (Phase 1a)', () => {
        it('defaults Birth Month to unset and submits birth_month null', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            expect(labeledInput<HTMLSelectElement>('Birth Month').value).toBe('');
            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.birth_month).toBeNull();
            expect(call.spouse_birth_month).toBeNull();
        });

        it('submits the selected birth month as a number', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(labeledInput<HTMLSelectElement>('Birth Month'), { target: { value: '3' } });
            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            expect(onSubmit.mock.calls[0][0].birth_month).toBe(3);
        });

        it('pre-fills birth months from an existing scenario params_json', () => {
            setupMocks();
            render(
                <ScenarioForm
                    onSubmit={vi.fn()}
                    submitLabel="Save"
                    initialValues={{
                        id: 's1',
                        name: 'Existing',
                        retirement_date: '2035-01-01',
                        end_age: 90,
                        inflation_rate: 0.03,
                        params_json: JSON.stringify({
                            birth_year: 1970, birth_month: 9, spouse_birth_year: 1972, spouse_birth_month: 2,
                        }),
                        accounts: [],
                    } as never}
                />,
            );

            expect(labeledInput<HTMLSelectElement>('Birth Month').value).toBe('9');
            expect(labeledInput<HTMLSelectElement>('Spouse Birth Month').value).toBe('2');
        });

        it('shows Spouse Birth Month only with a spouse and clears it when the spouse is removed', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            expect(screen.queryByText('Spouse Birth Month')).not.toBeInTheDocument();
            fireEvent.change(labeledInput('Spouse Birth Year'), { target: { value: '1972' } });
            fireEvent.change(labeledInput<HTMLSelectElement>('Spouse Birth Month'), { target: { value: '11' } });
            fireEvent.change(labeledInput('Spouse Birth Year'), { target: { value: '' } });

            expect(screen.queryByText('Spouse Birth Month')).not.toBeInTheDocument();
            fireEvent.click(screen.getByRole('button', { name: 'Save' }));
            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            expect(onSubmit.mock.calls[0][0].spouse_birth_month).toBeNull();
        });

        it('submits spouse_birth_month when a spouse is present', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(labeledInput('Spouse Birth Year'), { target: { value: '1972' } });
            fireEvent.change(labeledInput<HTMLSelectElement>('Spouse Birth Month'), { target: { value: '11' } });
            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            expect(onSubmit.mock.calls[0][0].spouse_birth_month).toBe(11);
        });
    });
```

(If the `initialValues` fixture shape differs from the `Scenario` type, copy the shape used by any existing `initialValues` test in this file. The `as never` cast exists only so the test doesn't restate every optional `Scenario` field.)

- [ ] **Step 2: Run them and confirm they fail**

Run: `cd frontend && npx vitest run src/components/ScenarioForm.test.tsx`
Expected: FAIL: `Field for label "Birth Month" not found`.

- [ ] **Step 3: Add types and form-field definitions**

`frontend/src/types/projection.ts`: in `CreateScenarioRequest`, after `include_depression_years?: boolean | null;`, add:

```ts
    /** Phase 1a: primary's birth month (1-12). Null = unknown -> early access at the legacy age-60 year. Requires birth_year. */
    birth_month?: number | null;
    /** Phase 1a: spouse's birth month (1-12). Only meaningful when spouse_birth_year is set. */
    spouse_birth_month?: number | null;
```

`frontend/src/components/scenario/scenarioFormFields.ts`:
- After `DEFAULT_LONGEVITY_CONDITIONAL_AGE`, add:

```ts
/** Phase 1a: month picker options shared by the primary and spouse birth-month selects. */
export const MONTH_OPTIONS: ReadonlyArray<{ value: number; label: string }> = [
    { value: 1, label: 'January' }, { value: 2, label: 'February' }, { value: 3, label: 'March' },
    { value: 4, label: 'April' }, { value: 5, label: 'May' }, { value: 6, label: 'June' },
    { value: 7, label: 'July' }, { value: 8, label: 'August' }, { value: 9, label: 'September' },
    { value: 10, label: 'October' }, { value: 11, label: 'November' }, { value: 12, label: 'December' },
];
```

- In `ScenarioFormFields`, after `birthYear: number;`, add `birthMonth: number | null;`. After `spouseBirthYear: number | null;`, add `spouseBirthMonth: number | null;`.

- [ ] **Step 4: Wire `ScenarioForm`**

In `frontend/src/components/ScenarioForm.tsx`:
- `buildInitialFields`: after `birthYear: parsedParams.birth_year ?? 1990,`, add `birthMonth: parsedParams.birth_month ?? null,`. After `spouseBirthYear: parsedParams.spouse_birth_year ?? null,`, add `spouseBirthMonth: parsedParams.spouse_birth_month ?? null,`.
- Destructure: add `birthMonth,` after `birthYear,` and `spouseBirthMonth,` after `spouseBirthYear,`.
- `handleSpouseBirthYearChange`: inside the `value == null ? { ... }` reset object, add `spouseBirthMonth: null,` as the first entry.
- `handleSubmit` request: after `birth_year: birthYear,`, add `birth_month: birthMonth,`. After `spouse_birth_year: spouseBirthYear,`, add `spouse_birth_month: household ? spouseBirthMonth : null,`.

- [ ] **Step 5: Add the selects**

`ScenarioBasicsSection.tsx`: change the import line to `import { MONTH_OPTIONS, type ScenarioFormFields, type SetScenarioField } from './scenarioFormFields';`, add `birthMonth` to the destructure, and insert directly after the Birth Year `FormField`:

```tsx
            <FormField
                label="Birth Month"
                helpText="Optional. Makes the 59½ penalty-free year exact (born January–June: the year you turn 59). Blank uses the year you turn 60."
            >
                <select
                    style={inputStyle}
                    value={birthMonth ?? ''}
                    onChange={e => setField('birthMonth', e.target.value === '' ? null : Number(e.target.value))}
                >
                    <option value="">Not set</option>
                    {MONTH_OPTIONS.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
                </select>
            </FormField>
```

`ScenarioHouseholdSection.tsx`: add `MONTH_OPTIONS,` to the `./scenarioFormFields` import list and `spouseBirthMonth` to the destructure. Insert as the FIRST child inside the `{household && (<> ... </>)}` fragment (immediately before "Primary Death Age"):

```tsx
                        <FormField
                            label="Spouse Birth Month"
                            helpText="Optional. Makes the spouse's 59½ and Medicare dates exact. Blank uses whole-year ages."
                        >
                            <select
                                style={inputStyle}
                                value={spouseBirthMonth ?? ''}
                                onChange={e => setField('spouseBirthMonth', e.target.value === '' ? null : Number(e.target.value))}
                            >
                                <option value="">Not set</option>
                                {MONTH_OPTIONS.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
                            </select>
                        </FormField>
```

- [ ] **Step 6: Run the tests, typecheck and lint, and confirm they pass**

Run: `cd frontend && npx vitest run src/components/ScenarioForm.test.tsx && npm run typecheck && npm run lint`
Expected: all PASS. The existing household tests still pass: the new select is hidden without a spouse and doesn't change any existing label.

- [ ] **Step 7: Run frontend coverage (ratchet floors)**

Run: `cd frontend && npm run test:coverage`
Expected: PASS with floors held (statements 83, branches 75, functions 74, lines 86).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/types/projection.ts frontend/src/components/scenario/scenarioFormFields.ts \
        frontend/src/components/ScenarioForm.tsx frontend/src/components/scenario/ScenarioBasicsSection.tsx \
        frontend/src/components/scenario/ScenarioHouseholdSection.tsx frontend/src/components/ScenarioForm.test.tsx
git commit -F - <<'EOF'
feat(frontend): add birth month selects to the scenario form

Optional Birth Month (basics) and Spouse Birth Month (household,
visible only with a spouse) selects. The months are sent as
birth_month / spouse_birth_month and pre-filled from params_json.
Removing the spouse clears the spouse month, as it already does for the
other household fields. Unset months send null, so the backend keeps
the legacy whole-year 59 1/2 proxy.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```


### Task 5: `heir_tax_rate` input, `TerminalValue`, and `ProjectionEngine.runDetailed()`

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/TerminalValue.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/YearTaxPicture.java` (pure data carrier, populated in Task 9)
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/TaxSpaceYear.java` (pure data carrier, populated in Task 7)
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionRunDetail.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionEngine.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParams.java` (component after `spouseBirthMonth`, `EMPTY`, `from`)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParamsSource.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioRequest.java` (component after `spouseBirthMonth`, both back-compat ctors)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ScenarioCrudService.java` (~:107-112 create, ~:159-164 update, new validator next to `validateInterestYield` ~:378)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/ScenarioParamsParser.java` (~:44)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java` (`run` ~:166-180, `ProjectionRunContext` ~:190-205, `runInternal` ~:207-232, `runProjection` ~:328-361)
- Modify (test util): `backend/wealthview-core/src/test/java/com/wealthview/core/testutil/ScenarioRequestBuilder.java`
- Modify (test): `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/ScenarioParamsTest.java` (round-trip test for `heir_tax_rate`; existing positional calls use Part A's 29-arg back-compat constructor and need no change)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/TerminalValueTest.java` (new)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/ScenarioCrudServiceTest.java` (append)
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/ScenarioParamsParserTest.java` (append)
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTerminalValueTest.java` (new)

**Interfaces:**
- Consumes: Task 2's `ScenarioParams` / `ScenarioParamsSource` / `ScenarioRequest` components `Integer birthMonth, Integer spouseBirthMonth` (appended after `longevityConditionalAge`; in `ScenarioRequest` they sit immediately before `accounts`), and `ScenarioRequestBuilder.withBirthMonth/withSpouseBirthMonth`.
- Produces:
  - `ScenarioParams.heirTaxRate()` / `ScenarioParamsSource.heirTaxRate()` / `ScenarioRequest.heirTaxRate()` (`BigDecimal`, JSON `heir_tax_rate`).
  - `ScenarioParamsParser.DEFAULT_HEIR_TAX_RATE` (`new BigDecimal("0.24")`) and `BigDecimal heirTaxRate(ScenarioParams)`.
  - `TerminalValue(int year, BigDecimal traditional, BigDecimal roth, BigDecimal taxable, BigDecimal heirTaxRate, BigDecimal afterTaxLegacy, boolean atSecondDeath)` with `static TerminalValue compute(int, BigDecimal, BigDecimal, BigDecimal, BigDecimal, boolean)`.
  - `YearTaxPicture` and `TaxSpaceYear` (+ nested `TaxSpaceYear.BracketRoom`) records exactly as in the contract (data only; logic arrives in Tasks 7 and 9).
  - `ProjectionRunDetail(ProjectionResultResponse result, List<YearTaxPicture> taxPictures, List<TaxSpaceYear> taxSpace, @Nullable TerminalValue terminalValue)`.
  - `ProjectionEngine.runDetailed(ProjectionInput)` (default method). `DeterministicProjectionEngine.runDetailed` returns `taxPictures = List.of()`, `taxSpace = List.of()` until Task 9, and a populated `terminalValue` (null when the projection produced no years).
  - Note for Task 10: Mockito mocks of `ProjectionEngine` do NOT run default methods — `ProjectionService` tests that switch to `runDetailed` must stub it explicitly.

- [ ] **Step 1: Write the failing `TerminalValue` test**

Create `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/TerminalValueTest.java`:

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalValueTest {

    @Test
    void compute_typicalPools_taxesOnlyTraditionalAtHeirRate() {
        // 100,000 x (1 - 0.24) = 76,000 ; + 50,000 Roth + 25,000 taxable (basis steps up) = 151,000
        var value = TerminalValue.compute(2060, new BigDecimal("100000"), new BigDecimal("50000"),
                new BigDecimal("25000"), new BigDecimal("0.24"), false);

        assertThat(value.afterTaxLegacy()).isEqualByComparingTo("151000.0000");
        assertThat(value.afterTaxLegacy().scale()).isEqualTo(4);
        assertThat(value.year()).isEqualTo(2060);
        assertThat(value.heirTaxRate()).isEqualByComparingTo("0.24");
        assertThat(value.atSecondDeath()).isFalse();
    }

    @Test
    void compute_zeroHeirRate_legacyEqualsPoolSum() {
        var value = TerminalValue.compute(2060, new BigDecimal("100000"), new BigDecimal("50000"),
                new BigDecimal("25000"), BigDecimal.ZERO, true);

        assertThat(value.afterTaxLegacy()).isEqualByComparingTo("175000");
        assertThat(value.atSecondDeath()).isTrue();
    }

    @Test
    void compute_fiftyPercentHeirRate_halvesTraditional() {
        // 100,000.01 x 0.5 = 50,000.005 -> rounds HALF_UP at scale 4 = 50,000.0050
        var value = TerminalValue.compute(2060, new BigDecimal("100000.01"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.50"), false);

        assertThat(value.afterTaxLegacy()).isEqualByComparingTo("50000.0050");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=TerminalValueTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error `cannot find symbol: class TerminalValue`.

- [ ] **Step 3: Create the four core DTO records**

`backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/TerminalValue.java`:

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * End-of-projection (or second-death) pool balances valued for heirs: traditional dollars are taxed at
 * the heirs' assumed ordinary rate (they must empty an inherited IRA within ten years); Roth passes
 * tax-free; the taxable account passes at full value because its cost basis steps up at death.
 * Property equity is deliberately excluded -- it is reported separately as part of net worth.
 */
public record TerminalValue(int year, BigDecimal traditional, BigDecimal roth, BigDecimal taxable,
                            BigDecimal heirTaxRate, BigDecimal afterTaxLegacy, boolean atSecondDeath) {

    public static TerminalValue compute(int year, BigDecimal traditional, BigDecimal roth, BigDecimal taxable,
                                        BigDecimal heirTaxRate, boolean atSecondDeath) {
        BigDecimal afterTax = traditional.multiply(BigDecimal.ONE.subtract(heirTaxRate))
                .add(roth).add(taxable)
                .setScale(SCALE, ROUNDING);
        return new TerminalValue(year, traditional, roth, taxable, heirTaxRate, afterTax, atSecondDeath);
    }
}
```

`backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/YearTaxPicture.java`:

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.tax.FilingStatus;

/**
 * One retired year's realized tax inputs, captured by the deterministic engine once the year's taxes
 * are final (Phase 1a, spec §1.1) so {@code TaxSpaceCalculator} can re-price the year with probes.
 *
 * <p>{@code primaryAge} is the FILER's age ({@code HouseholdContext#filerAgeIn}: the primary while
 * alive, the survivor afterwards); {@code spouseAge} is {@code HouseholdContext#secondFilerAgeIn}
 * (non-null only while both spouses are alive). {@code ordinaryIncomeExSocialSecurity} is the
 * engine's effective other income minus the taxable Social Security portion.
 * {@code chargedOrdinaryAndStateTax} = taxLiability - ltcgTax - selfEmploymentTax - earlyWithdrawalPenalty.
 * {@code medicareCountInPremiumYear} is the number of alive household members aged 65+ in
 * {@code year + 2} (the IRMAA premium year this year's MAGI sets).
 */
public record YearTaxPicture(
        int year, FilingStatus filingStatus, int primaryAge, @Nullable Integer spouseAge,
        BigDecimal ordinaryIncomeExSocialSecurity,
        BigDecimal socialSecurityBenefit,
        BigDecimal socialSecurityTaxable,
        BigDecimal traditionalDistributions,
        BigDecimal rothConversion,
        BigDecimal ordinaryInterest,
        BigDecimal qualifiedDividendsAndLtcg,
        BigDecimal magi,
        BigDecimal netRentalIncome,
        BigDecimal chargedOrdinaryAndStateTax,
        BigDecimal chargedLtcgTax,
        int yearsFromBase, BigDecimal inflationRate,
        int medicareCountInPremiumYear) {
}
```

`backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/TaxSpaceYear.java`:

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

/**
 * Per-year "tax space" (Phase 1a, spec §1.2): remaining room in each ordinary bracket and LTCG band,
 * the Social Security inclusion zone, NIIT headroom, IRMAA tier distance for the premium year this
 * year's MAGI sets, and the effective marginal rate of the next $1,000 of ordinary income / LTCG.
 * Social Security fields are null when there is no benefit; IRMAA fields are null unless someone is
 * Medicare-age in {@code year + 2}.
 */
public record TaxSpaceYear(
        int year, int age, BigDecimal magi,
        BigDecimal marginalOrdinaryRate, List<BracketRoom> bracketRoom,
        BigDecimal ltcgZeroRoom, BigDecimal ltcgFifteenRoom,
        @Nullable BigDecimal provisionalIncome, @Nullable BigDecimal ssBaseThreshold,
        @Nullable BigDecimal ssUpperThreshold, @Nullable BigDecimal ssInclusionRate,
        BigDecimal niitHeadroom,
        @Nullable Integer irmaaPremiumYear, @Nullable Integer irmaaTier,
        @Nullable BigDecimal irmaaRoomToNextTier, @Nullable BigDecimal irmaaNextTierAnnualCost,
        BigDecimal effectiveMarginalOrdinary, BigDecimal effectiveMarginalLtcg) {

    /** Gross-income room left before income crosses the top of the bracket taxed at {@code rate}. */
    public record BracketRoom(BigDecimal rate, BigDecimal grossCeiling, BigDecimal room) {
    }
}
```

`backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionRunDetail.java`:

```java
package com.wealthview.core.projection.dto;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * The deterministic engine's full run output (Phase 1a): the byte-pinned {@link ProjectionResultResponse}
 * the golden files serialize, plus side-channel data that must never move those goldens -- per-year tax
 * pictures, the derived tax space, and the after-tax legacy value ({@code null} when the projection
 * produced no years).
 */
public record ProjectionRunDetail(ProjectionResultResponse result, List<YearTaxPicture> taxPictures,
                                  List<TaxSpaceYear> taxSpace, @Nullable TerminalValue terminalValue) {
}
```

- [ ] **Step 4: Run the `TerminalValue` test to verify it passes**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=TerminalValueTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the failing heir-rate validation tests**

Append to `backend/wealthview-core/src/test/java/com/wealthview/core/projection/ScenarioCrudServiceTest.java` (uses the class's existing `service`, `tenantId`, `scenarioId`, `tenantLookup`, `tenant`, `scenarioRepository` fields and the existing `captureSavedScenario()` helper):

```java
    // === Phase 1a: heir_tax_rate (0 .. 0.50 inclusive) ===

    @Test
    void createScenario_heirTaxRateZero_persistsExplicitZero() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var request = ScenarioRequestBuilder.builder().withHeirTaxRate(BigDecimal.ZERO).build();

        service.createScenario(tenantId, request);

        assertThat(captureSavedScenario().getParamsJson()).contains("\"heir_tax_rate\":0");
    }

    @Test
    void createScenario_heirTaxRateAtMax_doesNotThrow() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var request = ScenarioRequestBuilder.builder().withHeirTaxRate(new BigDecimal("0.50")).build();

        var result = service.createScenario(tenantId, request);

        assertThat(result.name()).isEqualTo("Plan");
    }

    @Test
    void createScenario_heirTaxRateAboveMax_throwsIllegalArgument() {
        var request = ScenarioRequestBuilder.builder().withHeirTaxRate(new BigDecimal("0.51")).build();

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heir_tax_rate must be between 0 and 0.50");
    }

    @Test
    void createScenario_heirTaxRateNegative_throwsIllegalArgument() {
        var request = ScenarioRequestBuilder.builder().withHeirTaxRate(new BigDecimal("-0.01")).build();

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heir_tax_rate");
    }

    @Test
    void updateScenario_heirTaxRateAboveMax_throwsIllegalArgument() {
        var request = ScenarioRequestBuilder.builder().withHeirTaxRate(new BigDecimal("0.51")).build();

        assertThatThrownBy(() -> service.updateScenario(tenantId, scenarioId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heir_tax_rate");
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=ScenarioCrudServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error `cannot find symbol: method withHeirTaxRate(java.math.BigDecimal)`.

- [ ] **Step 7: Add the `heirTaxRate` component end to end**

`ScenarioParams.java` — append the component after `Integer spouseBirthMonth` (added by Task 2), so the record's last components read:

```java
        Integer longevityConditionalAge,
        Integer birthMonth,
        Integer spouseBirthMonth,
        BigDecimal heirTaxRate) {
```

If `EMPTY` calls the canonical constructor, add one more `null` as its final argument. In the 29-arg back-compat constructor Part A added (the pre-birth-month shape that nine `ScenarioParamsTest` tests call positionally), the delegating `this(...)` call currently ends with the two `null`s for `birthMonth, spouseBirthMonth`. Append one more `null` for `heirTaxRate`, so it ends `..., longevityConditionalAge, null, null, null);`. The 29-arg signature itself does NOT change, so the existing positional test calls keep compiling untouched. In `from(...)`, append `source.heirTaxRate()` after `source.spouseBirthMonth()`:

```java
                source.longevityConditionalAge(), source.birthMonth(), source.spouseBirthMonth(),
                source.heirTaxRate());
```

`ScenarioParamsSource.java` — append after Task 2's `spouseBirthMonth()` accessor:

```java
    /**
     * Phase 1a: the ordinary tax rate assumed for heirs who inherit traditional (pre-tax) dollars,
     * used to value the after-tax legacy. {@code null} resolves to {@code 0.24}
     * ({@code ScenarioParamsParser.DEFAULT_HEIR_TAX_RATE}); valid range {@code [0, 0.50]}.
     */
    BigDecimal heirTaxRate();
```

`ScenarioRequest.java` — add the component immediately after `Integer spouseBirthMonth,` (i.e. still before `List<CreateProjectionAccountRequest> accounts`):

```java
        Integer spouseBirthMonth,
        BigDecimal heirTaxRate,
        List<CreateProjectionAccountRequest> accounts,
```

In BOTH back-compat constructors, add one more `null` argument immediately after the two `null`s Task 2 added for `birthMonth`/`spouseBirthMonth` (the position directly before `accounts` in the `this(...)` call). The compiler reports an arity error for any constructor you miss.

`ScenarioRequestBuilder.java` (core test util) — add the field, setter, and pass it in `build()`:

```java
    private BigDecimal heirTaxRate;
```

```java
    public ScenarioRequestBuilder withHeirTaxRate(@Nullable BigDecimal heirTaxRate) {
        this.heirTaxRate = heirTaxRate;
        return this;
    }
```

```java
    public ScenarioRequest build() {
        return new ScenarioRequest(
                name, retirementDate, endAge, inflationRate, birthYear, withdrawalRate, withdrawalStrategy,
                dynamicCeiling, dynamicFloor, filingStatus, null, null, withdrawalOrder, null,
                rothConversionStrategy, targetBracketRate, null, null, null, null,
                dividendYield, feeRate, includeDepressionYears, interestYield,
                spouseBirthYear, primaryDeathAge, spouseDeathAge, survivorSpendingFactor, communityProperty,
                stochasticMortality, primarySex, spouseSex, longevityConditionalAge,
                birthMonth, spouseBirthMonth, heirTaxRate,
                accounts, null, null, incomeSources);
    }
```

(`birthMonth`/`spouseBirthMonth` are the builder fields Task 2 added; keep them.)

`ScenarioParamsTest.java` — leave the existing positional calls alone (they use the unchanged 29-arg back-compat constructor). Append a round-trip test that goes through the canonical constructor via `from(...)`:

```java
    @Test
    void heirTaxRate_roundTripsThroughSnakeCaseKey() throws Exception {
        var params = ScenarioParams.from(ScenarioRequestBuilder.builder()
                .withHeirTaxRate(new BigDecimal("0.32")).build());

        var json = params.toJson(mapper);

        assertThat(mapper.readTree(json).get("heir_tax_rate").decimalValue()).isEqualByComparingTo("0.32");
        assertThat(ScenarioParams.parseOrEmpty(mapper, json).heirTaxRate()).isEqualByComparingTo("0.32");
    }
```

`ScenarioCrudService.java` — next to `MAX_INTEREST_YIELD`:

```java
    private static final BigDecimal MAX_HEIR_TAX_RATE = new BigDecimal("0.50");
```

next to `validateInterestYield`:

```java
    private static void validateHeirTaxRate(BigDecimal heirTaxRate) {
        if (heirTaxRate == null) {
            return;
        }
        if (heirTaxRate.signum() < 0 || heirTaxRate.compareTo(MAX_HEIR_TAX_RATE) > 0) {
            throw new IllegalArgumentException("heir_tax_rate must be between 0 and 0.50");
        }
    }
```

and in BOTH `createScenario` and `updateScenario`, directly after `validateInterestYield(request.interestYield());`:

```java
        validateHeirTaxRate(request.heirTaxRate());
```

- [ ] **Step 8: Run the core tests to verify they pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest='ScenarioCrudServiceTest,ScenarioParamsTest,TerminalValueTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 9: Write the failing parser and engine tests**

Append to `backend/wealthview-projection/src/test/java/com/wealthview/projection/ScenarioParamsParserTest.java`:

```java
    @Test
    void heirTaxRate_absentFromParams_defaultsToPoint24() {
        assertThat(parser.heirTaxRate(ScenarioParams.EMPTY)).isEqualByComparingTo("0.24");
    }

    @Test
    void heirTaxRate_presentInParsedParams_returnsParsedValue() {
        var params = parser.parseParams("{\"heir_tax_rate\": 0.32}");

        assertThat(parser.heirTaxRate(params)).isEqualByComparingTo("0.32");
    }

    @Test
    void heirTaxRate_explicitZero_isNotTreatedAsAbsent() {
        var params = parser.parseParams("{\"heir_tax_rate\": 0}");

        assertThat(parser.heirTaxRate(params)).isEqualByComparingTo("0");
    }

    @Test
    void heirTaxRate_legacyParamsJsonWithoutTheKey_resolvesDefault() {
        // A scenario saved before Phase 1a: no heir_tax_rate key at all.
        var params = parser.parseParams("""
                {"birth_year": 1965, "withdrawal_rate": 0.04, "filing_status": "single"}
                """);

        assertThat(params.heirTaxRate()).isNull();
        assertThat(parser.heirTaxRate(params)).isEqualByComparingTo("0.24");
    }
```

(Add `import com.wealthview.core.projection.dto.ScenarioParams;` if the file does not already import it.)

Create `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTerminalValueTest.java`:

```java
package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.household.HouseholdContext;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.retiredAt66BirthYear;
import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProjectionEngineTerminalValueTest extends DeterministicProjectionEngineTestSupport {

    private static ProjectionInput threePoolRetiree(String extraParams) {
        String params = """
                {"birth_year": %d, "withdrawal_rate": 0.04, "fee_rate": 0%s}
                """.formatted(retiredAt66BirthYear(), extraParams);
        List<ProjectionAccountInput> accounts = List.of(
                acct("400000", "0", "0.04", "traditional"),
                acct("100000", "0", "0.04", "roth"),
                acct("100000", "0", "0.04", "taxable"));
        return createRetiredInput(params, accounts);
    }

    @Test
    void runDetailed_explicitHeirRate_valuesLastRowPoolsForHeirs() {
        var detail = engine.runDetailed(threePoolRetiree(", \"heir_tax_rate\": 0.30"));

        var last = detail.result().yearlyData().getLast();
        var tv = detail.terminalValue();
        assertThat(tv).isNotNull();
        assertThat(tv.year()).isEqualTo(last.year());
        assertThat(tv.traditional()).isEqualByComparingTo(last.traditionalBalance());
        assertThat(tv.roth()).isEqualByComparingTo(last.rothBalance());
        assertThat(tv.taxable()).isEqualByComparingTo(last.taxableBalance());
        assertThat(tv.heirTaxRate()).isEqualByComparingTo("0.30");
        BigDecimal expected = last.traditionalBalance().multiply(bd("0.70"))
                .add(last.rothBalance()).add(last.taxableBalance())
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(tv.afterTaxLegacy()).isEqualByComparingTo(expected);
        assertThat(tv.atSecondDeath()).isFalse();
    }

    @Test
    void runDetailed_heirRateAbsent_usesDefaultPoint24() {
        var detail = engine.runDetailed(threePoolRetiree(""));

        assertThat(detail.terminalValue().heirTaxRate()).isEqualByComparingTo("0.24");
    }

    @Test
    void runDetailed_beforeTask9_taxPicturesAndTaxSpaceAreEmptyNotNull() {
        var detail = engine.runDetailed(threePoolRetiree(""));

        assertThat(detail.taxPictures()).isEmpty();
        assertThat(detail.taxSpace()).isEmpty();
    }

    @Test
    void run_returnsTheSameResultAsRunDetailed() {
        var input = threePoolRetiree("");

        var viaRun = engine.run(input);
        var viaDetailed = engine.runDetailed(input).result();

        assertThat(viaRun.yearlyData()).hasSameSizeAs(viaDetailed.yearlyData());
        assertThat(viaRun.finalBalance()).isEqualByComparingTo(viaDetailed.finalBalance());
        assertThat(viaRun.yearsInRetirement()).isEqualTo(viaDetailed.yearsInRetirement());
    }

    @Test
    void runDetailed_noProjectedYears_terminalValueIsNullAndResultIsEmpty() {
        // birth 1930 + end age 90 = end year 2020, before the 2030 reference year: zero loop iterations.
        var input = createInput(LocalDate.of(2000, 1, 1), 90, BigDecimal.ZERO,
                "{\"birth_year\": 1930, \"withdrawal_rate\": 0.04}",
                List.of(acct("100000", "0", "0.04", "taxable")), null, 2030, List.of());

        var detail = engine.runDetailed(input);

        assertThat(detail.result().yearlyData()).isEmpty();
        assertThat(detail.terminalValue()).isNull();
    }

    @Test
    void runDetailed_secondDeathWithinHorizon_flagsAtSecondDeathInThatYear() {
        // Primary (1958) dies 2040 at 82; spouse (1966) dies 2050 at 84; horizon end 2053.
        var household = HouseholdContext.of(1958, 82, 1966, 84, 1958 + 95);
        ProjectionAccountInput traditional = new HypotheticalAccountInput(bd("1500000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.empty(), bd("0"), "traditional", "primary");
        var input = new ProjectionInput(UUID.randomUUID(), "Second death", LocalDate.of(2020, 1, 1), 95,
                BigDecimal.ZERO,
                """
                {"birth_year": 1958, "filing_status": "married_filing_jointly", "withdrawal_rate": 0.04,
                 "withdrawal_order": "taxable_first", "fee_rate": 0}
                """,
                List.of(traditional), new SpendingProfileInput(bd("50000"), bd("10000"), null),
                2035, List.of(), null, List.of(), household);

        var detail = engine.runDetailed(input);

        var tv = detail.terminalValue();
        assertThat(tv.year()).isEqualTo(2050);
        assertThat(tv.atSecondDeath()).isTrue();
    }
}
```

- [ ] **Step 10: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='ScenarioParamsParserTest,DeterministicProjectionEngineTerminalValueTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation errors `cannot find symbol: method heirTaxRate(ScenarioParams)` and `cannot find symbol: method runDetailed(ProjectionInput)`.

- [ ] **Step 11: Implement the parser default, the interface default method, and the engine side channel**

`ScenarioParamsParser.java` — after `DEFAULT_INTEREST_YIELD`:

```java
    /**
     * Phase 1a: ordinary tax rate assumed for heirs inheriting traditional dollars when a scenario
     * doesn't set {@code heir_tax_rate} -- a typical rate for heirs in their peak earning years who
     * must empty an inherited IRA within ten years.
     */
    public static final BigDecimal DEFAULT_HEIR_TAX_RATE = new BigDecimal("0.24");
```

and after `interestYield(...)`:

```java
    /** Resolves the effective heir tax rate, defaulting to {@link #DEFAULT_HEIR_TAX_RATE} when unset. */
    BigDecimal heirTaxRate(ScenarioParams params) {
        return params.heirTaxRate() != null ? params.heirTaxRate() : DEFAULT_HEIR_TAX_RATE;
    }
```

`ProjectionEngine.java` (core) — replace the file body:

```java
package com.wealthview.core.projection;

import java.util.List;

import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionRunDetail;

@FunctionalInterface
public interface ProjectionEngine {

    ProjectionResultResponse run(ProjectionInput input);

    /**
     * Phase 1a: the run result plus golden-safe side-channel data (tax pictures, tax space, after-tax
     * legacy). The default wraps {@link #run} with empty side data; the deterministic engine overrides it.
     */
    default ProjectionRunDetail runDetailed(ProjectionInput input) {
        return new ProjectionRunDetail(run(input), List.of(), List.of(), null);
    }
}
```

`DeterministicProjectionEngine.java`:

1. Imports — add (keeping alphabetical order within the `com.wealthview` group):

```java
import com.wealthview.core.common.Money;
import com.wealthview.core.projection.dto.ProjectionRunDetail;
import com.wealthview.core.projection.dto.TerminalValue;
```

2. Replace the existing `run(...)` method (the one carrying `@Observed`, ~:163-180) with:

```java
    // No @Timed here — see the note in MonteCarloSpendingOptimizer#optimize. This name did not
    // crash the scrape (neither timer asked for buckets, so both rendered as Summary), but the two
    // annotations still recorded every run TWICE into one metric, doubling its count and sum.
    @Observed(name = "wealthview.projection.run",
              contextualName = "deterministic-projection",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public ProjectionResultResponse run(ProjectionInput input) {
        // Self-invocation: runDetailed's own @Observed does not fire again (no proxy hop), and the
        // runs counter increments exactly once, inside runDetailed.
        return runDetailed(input).result();
    }

    @Observed(name = "wealthview.projection.run",
              contextualName = "deterministic-projection",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public ProjectionRunDetail runDetailed(ProjectionInput input) {
        MDC.put("operation", "projection");
        MDC.put("scenarioName", input.scenarioName() != null ? input.scenarioName() : "unnamed");
        try {
            var detail = runInternal(input);
            if (meterRegistry != null) {
                meterRegistry.counter("wealthview.projection.runs", "type", "deterministic").increment();
            }
            return detail;
        } finally {
            MDC.remove("operation");
            MDC.remove("scenarioName");
        }
    }
```

3. `ProjectionRunContext` — append a component after `boolean communityProperty`:

```java
            boolean communityProperty,
            BigDecimal heirTaxRate) {
```

4. `runInternal` — change its return type to `ProjectionRunDetail` and pass the heir rate when constructing the context:

```java
    private ProjectionRunDetail runInternal(ProjectionInput input) {
```

```java
        var ctx = new ProjectionRunContext(input, pool, resolved.strategy(),
                resolved.currentYear(), resolved.birthYear(), resolved.retirementYear(), resolved.endYear(),
                resolved.inflationRate(), resolved.spendingPlan(), resolved.incomeSources(),
                resolved.properties(), taxStrategy, survivorSpendingFactor, communityProperty,
                paramsParser.heirTaxRate(params));
        return runProjection(ctx);
```

5. `runProjection` — change its return type to `ProjectionRunDetail` and replace its final `return new ProjectionResultResponse(...)` statement with:

```java
        var result = new ProjectionResultResponse(ctx.input().scenarioId(), yearlyData, finalBalance,
                acc.yearsInRetirement(), feasibility, finalNetWorth);
        return new ProjectionRunDetail(result, List.of(), List.of(), terminalValue(ctx, yearlyData));
    }

    /**
     * Phase 1a after-tax legacy: values the LAST projected row's pools -- the end of the horizon, or the
     * second death's year when the household truncation above ended the loop early. {@code null} when
     * the projection produced no years (e.g. the end age is already behind the reference year).
     */
    @Nullable
    private static TerminalValue terminalValue(ProjectionRunContext ctx, List<ProjectionYearDto> yearlyData) {
        if (yearlyData.isEmpty()) {
            return null;
        }
        var last = yearlyData.getLast();
        var household = ctx.input().household();
        boolean atSecondDeath = household != null && household.secondDeathYear().isPresent()
                && household.secondDeathYear().get() == last.year();
        return TerminalValue.compute(last.year(), Money.sum(last.traditionalBalance()),
                Money.sum(last.rothBalance()), Money.sum(last.taxableBalance()), ctx.heirTaxRate(),
                atSecondDeath);
    }
```

(`Money.sum` treats a null pool balance as zero.)

- [ ] **Step 12: Run the projection tests to verify they pass, then the golden suite**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='ScenarioParamsParserTest,DeterministicProjectionEngineTerminalValueTest,ProjectionGoldenFileTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `ProjectionGoldenFileTest` must pass WITHOUT `-Dupdate.golden=true` — `run()` output is unchanged.

- [ ] **Step 13: Run the quality gates for both modules**

Run: `cd backend && mvn -q -T1 -pl wealthview-core,wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS (PMD, CPD, SpotBugs, Checkstyle, JaCoCo all green).

- [ ] **Step 14: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/TerminalValue.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/YearTaxPicture.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/TaxSpaceYear.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionRunDetail.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionEngine.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParams.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioParamsSource.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ScenarioRequest.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/ScenarioCrudService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/testutil/ScenarioRequestBuilder.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/ScenarioParamsTest.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/TerminalValueTest.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/ScenarioCrudServiceTest.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/ScenarioParamsParser.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/ScenarioParamsParserTest.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTerminalValueTest.java
git commit -F - <<'EOF'
feat(projection): add heir tax rate and after-tax legacy value via runDetailed

Adds the heir_tax_rate scenario field (default 0.24, validated 0-0.50) and a
TerminalValue computed from the last projected row (or the second-death year
when the household truncates the run): traditional x (1 - heir rate) + Roth +
taxable at full value (basis steps up at death).

ProjectionEngine gains runDetailed(), returning ProjectionRunDetail: the
unchanged ProjectionResultResponse plus side-channel data that never touches
the golden files. run() now delegates to runDetailed().result(). The
YearTaxPicture and TaxSpaceYear carriers are introduced here (empty lists
until Tasks 7 and 9 populate them).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6: Supporting calculator additions and `RealTermsDeflator`

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/RealTermsDeflator.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/LtcgBandRoom.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/SsThresholds.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/IrmaaTier.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/SocialSecurityTaxCalculator.java` (constants → named fields, `thresholds`, deflator)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/CapitalGainsTaxCalculator.java` (`ltcgBandRoom`, deflator)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/IrmaaSurchargeCalculator.java` (`loadTiers`, Javadoc)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/FederalTaxCalculator.java` (age-aware `computeMaxIncomeForBracket` overload)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/TaxCalculationStrategy.java` (`computeGrossCeilingForRate` default)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/CombinedTaxCalculator.java` (override)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/FederalOnlyTaxStrategy.java` (override)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/tax/RealTermsDeflatorTest.java` (new)
- Test (append): `SocialSecurityTaxCalculatorTest.java`, `CapitalGainsTaxCalculatorTest.java`, `IrmaaSurchargeCalculatorTest.java`, `FederalTaxCalculatorTest.java`, `CombinedTaxCalculatorTest.java`, `FederalOnlyTaxStrategyTest.java` (all in `backend/wealthview-core/src/test/java/com/wealthview/core/projection/tax/`)

**Interfaces:**
- Consumes: nothing new.
- Produces (all in `com.wealthview.core.projection.tax`):
  - `RealTermsDeflator.factor(int yearsFromBase, @Nullable BigDecimal inflationRate)` → `BigDecimal` (1 when `yearsFromBase <= 0` or rate null/zero; else `1/(1+i)^n` at scale `SCALE + 6`, `HALF_UP`) and `RealTermsDeflator.factor(int, double)` → `double`. Task 15 uses the double overload.
  - `record LtcgBandRoom(BigDecimal zeroRoom, BigDecimal fifteenRoom)`; `CapitalGainsTaxCalculator.ltcgBandRoom(BigDecimal ordinaryTaxableIncome, BigDecimal ltcgIncome, int year, FilingStatus status)`.
  - `record SsThresholds(BigDecimal base, BigDecimal upper)`; `SocialSecurityTaxCalculator.thresholds(FilingStatus status, int yearsFromBase, BigDecimal inflationRate)`.
  - `record IrmaaTier(BigDecimal magiFloor, @Nullable BigDecimal magiCeiling, BigDecimal annualSurchargePerPerson)`; `IrmaaSurchargeCalculator.loadTiers(int year, FilingStatus status)` → `List<IrmaaTier>` (with the same year fallback as `computeAnnualSurcharge`).
  - `FederalTaxCalculator.computeMaxIncomeForBracket(BigDecimal rate, int year, FilingStatus status, int age, @Nullable Integer secondQualifyingAge)`.
  - `TaxCalculationStrategy.computeGrossCeilingForRate(BigDecimal targetRate, int taxYear, FilingStatus status)` (default → `computeMaxIncomeForTargetRate`; age-aware overrides in `CombinedTaxCalculator` and `FederalOnlyTaxStrategy`).
  - `TaxCalculationStrategy.standardDeduction(int taxYear, FilingStatus status)` → `Optional<BigDecimal>` (default `Optional.empty()`; `CombinedTaxCalculator` and `FederalOnlyTaxStrategy` return the AGE-AWARE standard deduction from their own household/birth-year age resolution). Task 7 uses it for the LTCG stacking floor; Task 9 uses it to fix the engine's age-unaware floor.

- [ ] **Step 1: Write the failing `RealTermsDeflator` test**

Create `backend/wealthview-core/src/test/java/com/wealthview/core/projection/tax/RealTermsDeflatorTest.java`:

```java
package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RealTermsDeflatorTest {

    @Test
    void factor_zeroYears_isOne() {
        assertThat(RealTermsDeflator.factor(0, new BigDecimal("0.025"))).isEqualByComparingTo("1");
    }

    @Test
    void factor_negativeYears_isOne() {
        assertThat(RealTermsDeflator.factor(-3, new BigDecimal("0.025"))).isEqualByComparingTo("1");
    }

    @Test
    void factor_nullOrZeroRate_isOne() {
        assertThat(RealTermsDeflator.factor(10, null)).isEqualByComparingTo("1");
        assertThat(RealTermsDeflator.factor(10, BigDecimal.ZERO)).isEqualByComparingTo("1");
    }

    @Test
    void factor_tenYearsAtTwoPointFivePercent_matchesClosedForm() {
        // 1.025^10 = 1.2800845441963578... ; 1 / that = 0.78119840172..., HALF_UP at scale 10
        assertThat(RealTermsDeflator.factor(10, new BigDecimal("0.025"))).isEqualByComparingTo("0.7811984017");
    }

    @Test
    void factor_doubleOverload_matchesBigDecimalOverload() {
        assertThat(RealTermsDeflator.factor(10, 0.025)).isCloseTo(0.7811984017257273, within(1e-12));
        assertThat(RealTermsDeflator.factor(0, 0.025)).isEqualTo(1.0);
        assertThat(RealTermsDeflator.factor(10, 0.0)).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=RealTermsDeflatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `cannot find symbol: variable RealTermsDeflator`.

- [ ] **Step 3: Implement `RealTermsDeflator` and route the two existing copies through it**

Create `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/RealTermsDeflator.java`:

```java
package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * The single real-terms deflator for statutorily FIXED-NOMINAL thresholds (SS provisional-income
 * thresholds, the NIIT threshold): {@code 1/(1+inflationRate)^yearsFromBase}. Replaces the copies
 * that previously lived in {@link SocialSecurityTaxCalculator} and {@link CapitalGainsTaxCalculator}
 * (behavior-identical) so every engine deflates on one clock.
 */
public final class RealTermsDeflator {

    private RealTermsDeflator() {
    }

    public static BigDecimal factor(int yearsFromBase, @Nullable BigDecimal inflationRate) {
        if (yearsFromBase <= 0 || inflationRate == null || inflationRate.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal growth = BigDecimal.ONE.add(inflationRate).pow(yearsFromBase);
        return BigDecimal.ONE.divide(growth, SCALE + 6, ROUNDING);
    }

    public static double factor(int yearsFromBase, double inflationRate) {
        if (yearsFromBase <= 0 || inflationRate == 0.0) {
            return 1.0;
        }
        return 1.0 / Math.pow(1.0 + inflationRate, yearsFromBase);
    }
}
```

In `SocialSecurityTaxCalculator.java` and `CapitalGainsTaxCalculator.java`, delete the private static `thresholdDeflator(int, BigDecimal)` method and replace each call `thresholdDeflator(yearsFromBase, inflationRate)` with `RealTermsDeflator.factor(yearsFromBase, inflationRate)` (SS: 1 call; capital gains: 2 calls, in `niitThresholdReal` and `computeNiit`).

- [ ] **Step 4: Run the deflator and the existing SS / capital-gains suites**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest='RealTermsDeflatorTest,SocialSecurityTaxCalculatorTest,CapitalGainsTaxCalculatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (existing tests unchanged — the refactor is behavior-identical).

- [ ] **Step 5: Write the failing accessor tests**

Append to `SocialSecurityTaxCalculatorTest.java` (the class constructs `new SocialSecurityTaxCalculator()`; if it names the instance differently, use that name):

```java
    @Test
    void thresholds_singleAtBase_areNominal25kAnd34k() {
        var t = new SocialSecurityTaxCalculator().thresholds(FilingStatus.SINGLE, 0, new BigDecimal("0.025"));

        assertThat(t.base()).isEqualByComparingTo("25000");
        assertThat(t.upper()).isEqualByComparingTo("34000");
    }

    @Test
    void thresholds_mfjTenYearsOut_areDeflatedAtScale4() {
        // deflator(10, 2.5%) = 0.7811984017 ; 32000 x d = 24998.3489 ; 44000 x d = 34372.7297
        var t = new SocialSecurityTaxCalculator()
                .thresholds(FilingStatus.MARRIED_FILING_JOINTLY, 10, new BigDecimal("0.025"));

        assertThat(t.base()).isEqualByComparingTo("24998.3489");
        assertThat(t.upper()).isEqualByComparingTo("34372.7297");
    }
```

Append to `CapitalGainsTaxCalculatorTest.java` (its `setUp` already stubs single 2025 LTCG brackets: 0% to 48,350, 15% to 533,400):

```java
    @Test
    void ltcgBandRoom_ordinaryAndGainBelowZeroCeiling_reportsRemainingRoomInBothBands() {
        // stacked = 30000 + 10000 = 40000 ; zero room 48350-40000 = 8350 ; fifteen room 533400-48350 = 485050
        var room = calculator.ltcgBandRoom(bd("30000"), bd("10000"), 2025, FilingStatus.SINGLE);

        assertThat(room.zeroRoom()).isEqualByComparingTo("8350");
        assertThat(room.fifteenRoom()).isEqualByComparingTo("485050");
    }

    @Test
    void ltcgBandRoom_ordinaryAboveZeroCeiling_zeroRoomIsZeroAndFifteenRoomStartsAtOrdinary() {
        // stacked = 60000 > 48350 ; fifteen room 533400-60000 = 473400
        var room = calculator.ltcgBandRoom(bd("60000"), bd("0"), 2025, FilingStatus.SINGLE);

        assertThat(room.zeroRoom()).isEqualByComparingTo("0");
        assertThat(room.fifteenRoom()).isEqualByComparingTo("473400");
    }

    @Test
    void ltcgBandRoom_negativeOrdinary_isFlooredAtZero() {
        var room = calculator.ltcgBandRoom(bd("-5000"), bd("0"), 2025, FilingStatus.SINGLE);

        assertThat(room.zeroRoom()).isEqualByComparingTo("48350");
    }
```

Append to `IrmaaSurchargeCalculatorTest.java`:

```java
    @Test
    void loadTiers_single2025_exposesSixTiersWithAnnualPerPersonSurcharge() {
        var tiers = calculator.loadTiers(2025, FilingStatus.SINGLE);

        assertThat(tiers).hasSize(6);
        assertThat(tiers.get(0).magiCeiling()).isEqualByComparingTo("106000");
        assertThat(tiers.get(0).annualSurchargePerPerson()).isEqualByComparingTo("0");
        // (74.00 + 13.70) x 12 = 1052.40
        assertThat(tiers.get(1).annualSurchargePerPerson()).isEqualByComparingTo("1052.40");
        assertThat(tiers.get(5).magiCeiling()).isNull();
    }

    @Test
    void loadTiers_unseededYear_fallsBackLikeComputeAnnualSurcharge() {
        // The fixture stubs anyInt(), so 2031 resolves to the same 2025 rows.
        assertThat(calculator.loadTiers(2031, FilingStatus.MARRIED_FILING_JOINTLY)).hasSize(6);
    }
```

Append to `FederalTaxCalculatorTest.java` (uses the class's mocked `taxBracketRepository`, `standardDeductionRepository`, and `calculator`; the stubs below use the ACTUAL 2025 seed amounts from `R__seed_standard_deductions.sql`: 15,750 single / 31,500 MFJ, age-65 adders 2,000 / 1,600):

```java
    @Test
    void computeMaxIncomeForBracket_ageAware_addsAge65AdderPerQualifyingPerson() {
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025,
                        "married_filing_jointly"))
                .thenReturn(mfj2025Brackets());
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(
                        new StandardDeductionEntity(2025, "married_filing_jointly", bd("31500"), bd("1600"))));

        // 12% ceiling 48475 + 15750 = 64225 (age 64) ; + 2000 adder = 66225 (age 65)
        assertThat(calculator.computeMaxIncomeForBracket(bd("0.12"), 2025, FilingStatus.SINGLE, 64, null))
                .isEqualByComparingTo("64225");
        assertThat(calculator.computeMaxIncomeForBracket(bd("0.12"), 2025, FilingStatus.SINGLE, 65, null))
                .isEqualByComparingTo("66225");
        // MFJ both 65+: 96950 + 31500 + 2 x 1600 = 131650
        assertThat(calculator.computeMaxIncomeForBracket(bd("0.12"), 2025,
                FilingStatus.MARRIED_FILING_JOINTLY, 67, 66)).isEqualByComparingTo("131650");
        // Top bracket (no ceiling) and an unknown rate both return zero, like the age-less overload.
        assertThat(calculator.computeMaxIncomeForBracket(bd("0.37"), 2025, FilingStatus.SINGLE, 65, null))
                .isEqualByComparingTo("0");
        assertThat(calculator.computeMaxIncomeForBracket(bd("0.15"), 2025, FilingStatus.SINGLE, 65, null))
                .isEqualByComparingTo("0");
    }
```

Append to `FederalOnlyTaxStrategyTest.java` (create a local `FederalTaxCalculator` from mocks so the test is self-contained; add imports for any of `Optional`, `StandardDeductionEntity`, `TaxBracketRepository`, `StandardDeductionRepository`, `HouseholdContext`, `mock`, `when`, `single2025Brackets`, `mfj2025Brackets`, `bd` the file lacks):

```java
    @Test
    void computeGrossCeilingForRate_birthYearAge66_usesAgeAwareDeduction() {
        var bracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(bracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var strategy = new FederalOnlyTaxStrategy(new FederalTaxCalculator(bracketRepo, deductionRepo), 1959);

        // age 66 in 2025: 12% ceiling 48475 + 15750 + 2000 = 66225 (the age-less method still says 64225)
        assertThat(strategy.computeGrossCeilingForRate(bd("0.12"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("66225");
        assertThat(strategy.computeMaxIncomeForTargetRate(bd("0.12"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("64225");
    }

    @Test
    void computeGrossCeilingForRate_householdBothAliveMfj_addsTwoAdders() {
        var bracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(bracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "married_filing_jointly"))
                .thenReturn(mfj2025Brackets());
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(
                        new StandardDeductionEntity(2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        var household = HouseholdContext.of(1958, 95, 1959, 95, 2060);
        var strategy = new FederalOnlyTaxStrategy(new FederalTaxCalculator(bracketRepo, deductionRepo), 1958,
                household);

        // 96950 + 31500 + 1600 x 2 = 131650
        assertThat(strategy.computeGrossCeilingForRate(bd("0.12"), 2025, FilingStatus.MARRIED_FILING_JOINTLY))
                .isEqualByComparingTo("131650");
    }

    @Test
    void standardDeduction_birthYearAge66_returnsAgeAwareAmount() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var strategy = new FederalOnlyTaxStrategy(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo), 1959);

        // 15,750 + 2,000 (age 66)
        assertThat(strategy.standardDeduction(2025, FilingStatus.SINGLE)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("17750"));
    }

    @Test
    void standardDeduction_householdMfjBothAlive_countsBothAdders() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(
                        new StandardDeductionEntity(2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        var strategy = new FederalOnlyTaxStrategy(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo), 1958,
                HouseholdContext.of(1958, 95, 1959, 95, 2060));

        // 31,500 + 1,600 x 2
        assertThat(strategy.standardDeduction(2025, FilingStatus.MARRIED_FILING_JOINTLY)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("34700"));
    }

    @Test
    void standardDeduction_noAgeKnown_returnsBaseAmount() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var strategy = new FederalOnlyTaxStrategy(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo));

        assertThat(strategy.standardDeduction(2025, FilingStatus.SINGLE)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("15750"));
    }
```

Append to `CombinedTaxCalculatorTest.java` (same local-mock pattern; add missing imports as above plus `NullStateTaxCalculator` is in the same package):

```java
    @Test
    void computeGrossCeilingForRate_noStateTax_age66_usesAgeAwareStandardDeduction() {
        var bracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(bracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var combined = new CombinedTaxCalculator(new FederalTaxCalculator(bracketRepo, deductionRepo),
                new NullStateTaxCalculator(), BigDecimal.ZERO, BigDecimal.ZERO, 1959);

        // 48475 + 17750 = 66225
        assertThat(combined.computeGrossCeilingForRate(bd("0.12"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("66225");
    }

    @Test
    void computeGrossCeilingForRate_itemizedBeatsStandard_usesItemizedEvenWithoutStateTax() {
        var bracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(bracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var combined = new CombinedTaxCalculator(new FederalTaxCalculator(bracketRepo, deductionRepo),
                new NullStateTaxCalculator(), bd("20000"), bd("5000"), 1959);

        // SALT = min(0 + 20000, 40000) = 20000 ; itemized = 25000 > 17750 ; 48475 + 25000 = 73475
        assertThat(combined.computeGrossCeilingForRate(bd("0.12"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("73475");
    }

    @Test
    void standardDeduction_birthYearAge66_returnsAgeAwareAmountNotItemized() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        // Large property tax would make itemizing win, but this method reports the STANDARD amount only.
        var combined = new CombinedTaxCalculator(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo),
                new NullStateTaxCalculator(), bd("30000"), bd("10000"), 1959);

        assertThat(combined.standardDeduction(2025, FilingStatus.SINGLE)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("17750"));
    }

    @Test
    void standardDeduction_householdSurvivorFilingSingle_appliesOneAdder() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2045, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2045, "single", bd("15750"), bd("2000"))));
        // Primary (1958) dies 2040; in 2045 the spouse (1959, age 86) files SINGLE alone.
        var household = HouseholdContext.of(1958, 82, 1959, 95, 2060);
        var combined = new CombinedTaxCalculator(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo),
                new NullStateTaxCalculator(), BigDecimal.ZERO, BigDecimal.ZERO, 1958, household);

        assertThat(combined.standardDeduction(2045, FilingStatus.SINGLE)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("17750"));
    }
```

Append the default-method test to `FederalOnlyTaxStrategyTest.java` (it covers the interface default):

```java
    @Test
    void standardDeduction_defaultImplementation_isEmpty() {
        TaxCalculationStrategy bare = new TaxCalculationStrategy() {
            @Override
            public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
                return BigDecimal.ZERO;
            }

            @Override
            public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear,
                                                             FilingStatus status) {
                return BigDecimal.ZERO;
            }
        };

        assertThat(bare.standardDeduction(2025, FilingStatus.SINGLE)).isEmpty();
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest='SocialSecurityTaxCalculatorTest,CapitalGainsTaxCalculatorTest,IrmaaSurchargeCalculatorTest,FederalTaxCalculatorTest,FederalOnlyTaxStrategyTest,CombinedTaxCalculatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation errors for `thresholds`, `ltcgBandRoom`, `loadTiers`, the 5-arg `computeMaxIncomeForBracket`, `computeGrossCeilingForRate`, and `standardDeduction`.

- [ ] **Step 7: Implement the accessors**

Create `LtcgBandRoom.java`:

```java
package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

/** Remaining room (never negative) in the 0% and 15% LTCG bands after ordinary income stacks first. */
public record LtcgBandRoom(BigDecimal zeroRoom, BigDecimal fifteenRoom) {
}
```

Create `SsThresholds.java`:

```java
package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

/** The two Social Security provisional-income thresholds for one year, already deflated to real terms. */
public record SsThresholds(BigDecimal base, BigDecimal upper) {
}
```

Create `IrmaaTier.java`:

```java
package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

/**
 * One IRMAA MAGI tier: "greater than {@code magiFloor}, up to and including {@code magiCeiling}"
 * ({@code null} ceiling = top tier), with the combined Part B + Part D surcharge annualized (x12)
 * for ONE Medicare enrollee.
 */
public record IrmaaTier(BigDecimal magiFloor, @Nullable BigDecimal magiCeiling,
                        BigDecimal annualSurchargePerPerson) {
}
```

`SocialSecurityTaxCalculator.java` — add named constants and `thresholds`, and make `computeTaxableAmount` use them:

```java
    private static final BigDecimal SINGLE_BASE_THRESHOLD = new BigDecimal("25000");
    private static final BigDecimal SINGLE_UPPER_THRESHOLD = new BigDecimal("34000");
    private static final BigDecimal MFJ_BASE_THRESHOLD = new BigDecimal("32000");
    private static final BigDecimal MFJ_UPPER_THRESHOLD = new BigDecimal("44000");

    /**
     * The year's provisional-income thresholds, deflated onto the real-terms clock exactly as
     * {@link #computeTaxableAmount(BigDecimal, BigDecimal, String, int, BigDecimal)} applies them.
     */
    public SsThresholds thresholds(FilingStatus status, int yearsFromBase, BigDecimal inflationRate) {
        boolean mfj = status == FilingStatus.MARRIED_FILING_JOINTLY;
        BigDecimal base = mfj ? MFJ_BASE_THRESHOLD : SINGLE_BASE_THRESHOLD;
        BigDecimal upper = mfj ? MFJ_UPPER_THRESHOLD : SINGLE_UPPER_THRESHOLD;
        BigDecimal deflator = RealTermsDeflator.factor(yearsFromBase, inflationRate);
        if (deflator.compareTo(BigDecimal.ONE) != 0) {
            base = base.multiply(deflator).setScale(SCALE, ROUNDING);
            upper = upper.multiply(deflator).setScale(SCALE, ROUNDING);
        }
        return new SsThresholds(base, upper);
    }
```

and in `computeTaxableAmount(...)` replace the block from `BigDecimal tier1Threshold;` through the deflation `if` block (inclusive) with:

```java
        var thresholds = thresholds(FilingStatus.fromString(filingStatus), yearsFromBase, inflationRate);
        BigDecimal tier1Threshold = thresholds.base();
        BigDecimal tier2Threshold = thresholds.upper();
```

(`FilingStatus.fromString("married_filing_jointly")` → MFJ and anything else → SINGLE, matching the old string comparison.)

`CapitalGainsTaxCalculator.java` — add after `loadLtcgBrackets`:

```java
    /**
     * Room left in the 0% and 15% LTCG bands after {@code ordinaryTaxableIncome} (net of the deduction,
     * floored at zero) and this year's {@code ltcgIncome} are stacked. Zero when the band is already full;
     * zero for a band whose ceiling is not seeded.
     */
    public LtcgBandRoom ltcgBandRoom(BigDecimal ordinaryTaxableIncome, BigDecimal ltcgIncome, int year,
                                     FilingStatus status) {
        BigDecimal stacked = ordinaryTaxableIncome.max(BigDecimal.ZERO).add(ltcgIncome.max(BigDecimal.ZERO));
        BigDecimal zeroCeiling = null;
        BigDecimal fifteenCeiling = null;
        for (var bracket : loadBracketsWithFallback(year, status)) {
            if (bracket.getRate().signum() == 0) {
                zeroCeiling = bracket.getBracketCeiling();
            } else if (bracket.getRate().compareTo(FIFTEEN_PERCENT) == 0) {
                fifteenCeiling = bracket.getBracketCeiling();
            }
        }
        BigDecimal zeroRoom = zeroCeiling != null ? zeroCeiling.subtract(stacked).max(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        BigDecimal fifteenFloor = zeroCeiling != null ? stacked.max(zeroCeiling) : stacked;
        BigDecimal fifteenRoom = fifteenCeiling != null ? fifteenCeiling.subtract(fifteenFloor).max(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        return new LtcgBandRoom(zeroRoom, fifteenRoom);
    }
```

plus the constant next to `NIIT_RATE`:

```java
    private static final BigDecimal FIFTEEN_PERCENT = new BigDecimal("0.15");
```

`IrmaaSurchargeCalculator.java` — add after `computeAnnualSurcharge`:

```java
    /**
     * The tier table for {@code (taxYear, status)} with the same latest-seeded-year fallback as
     * {@link #computeAnnualSurcharge}, each tier's Part B + Part D surcharge annualized for ONE enrollee.
     */
    public List<IrmaaTier> loadTiers(int taxYear, FilingStatus status) {
        return loadTiersWithFallback(taxYear, status).stream()
                .map(t -> new IrmaaTier(t.getMagiFloor(), t.getMagiCeiling(),
                        t.getPartBSurcharge().add(t.getPartDSurcharge())
                                .multiply(MONTHS_PER_YEAR).setScale(SCALE, ROUNDING)))
                .toList();
    }
```

and replace the stale Javadoc paragraph that begins `<p>The per-COVERED-PERSON multiplier is deliberately NOT applied here` (through `feeds this calculator's {@code magi} argument.`) with:

```java
 * <p>Amounts are per Medicare ENROLLEE. Callers multiply by the number of enrolled household members:
 * {@code DeterministicProjectionEngine} by {@code HouseholdContext#age65QualifyingCount}, and
 * {@code TaxSpaceCalculator} by {@code YearTaxPicture#medicareCountInPremiumYear}. See
 * {@code DeterministicProjectionEngine}'s IRMAA orchestration for the 2-year MAGI lookback that feeds
 * this calculator's {@code magi} argument.
```

`FederalTaxCalculator.java` — add after the existing 4-arg `computeMaxIncomeForBracket`:

```java
    /**
     * Age-aware gross-income ceiling for the bracket taxed at {@code targetRate}: the bracket's taxable
     * ceiling (latest-seeded-year fallback, no inflation indexing -- the real-terms frame) plus the
     * standard deduction including the age-65 adder per qualifying person. Zero for the top bracket or an
     * unknown rate. The age-less overloads are unchanged (Phase 1c retires their optimizer callers).
     */
    public BigDecimal computeMaxIncomeForBracket(BigDecimal targetRate, int taxYear, FilingStatus status,
                                                 int age, @Nullable Integer secondQualifyingAge) {
        BigDecimal ceiling = findBracketCeiling(targetRate, taxYear, status);
        if (ceiling.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return ceiling.add(loadStandardDeduction(taxYear, status, age, secondQualifyingAge));
    }
```

`TaxCalculationStrategy.java` — add:

```java
    /**
     * Phase 1a: like {@link #computeMaxIncomeForTargetRate} but using the AGE-AWARE standard deduction
     * (and, where the implementation itemizes, the deduction that would apply at the ceiling). The
     * default delegates to the age-less method for implementations with no age concept.
     */
    default BigDecimal computeGrossCeilingForRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        return computeMaxIncomeForTargetRate(targetRate, taxYear, status);
    }

    /**
     * Phase 1a: the AGE-AWARE federal standard deduction this strategy applies for (taxYear, status) --
     * the same amount its ordinary-tax computation subtracts when it does not itemize. Empty for
     * implementations with no deduction/age concept; callers then fall back to their own lookup.
     */
    default Optional<BigDecimal> standardDeduction(int taxYear, FilingStatus status) {
        return Optional.empty();
    }
```

(add `import java.util.Optional;` to `TaxCalculationStrategy.java`.)

`CombinedTaxCalculator.java` — add after `computeMaxIncomeForTargetRate`:

```java
    @Override
    public BigDecimal computeGrossCeilingForRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        BigDecimal bracketCeiling = federal.findBracketCeiling(targetRate, taxYear, status);
        if (bracketCeiling.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        // Same itemized-vs-standard estimate at the ceiling as computeMaxIncomeForTargetRate, but with the
        // household/birth-year AGE-AWARE standard deduction, and applied even when there is no state tax
        // (property tax + mortgage interest alone can make itemizing win).
        BigDecimal standardDeduction = resolveFederalStandardDeduction(taxYear, status);
        BigDecimal grossEstimate = bracketCeiling.add(standardDeduction);
        BigDecimal estStateTax = state.computeTax(grossEstimate, taxYear, status);
        BigDecimal estItemized = estStateTax.add(primaryResidencePropertyTax).min(saltCap(taxYear))
                .add(primaryResidenceMortgageInterest);
        return bracketCeiling.add(estItemized.max(standardDeduction));
    }

    @Override
    public Optional<BigDecimal> standardDeduction(int taxYear, FilingStatus status) {
        return Optional.of(resolveFederalStandardDeduction(taxYear, status));
    }
```

(add `import java.util.Optional;` to `CombinedTaxCalculator.java`.)

`FederalOnlyTaxStrategy.java` — add after `computeMaxIncomeForTargetRate`:

```java
    @Override
    public BigDecimal computeGrossCeilingForRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        if (household != null) {
            Integer secondAge = status == FilingStatus.MARRIED_FILING_JOINTLY
                    ? household.secondFilerAgeIn(taxYear) : null;
            return federalTaxCalculator.computeMaxIncomeForBracket(
                    targetRate, taxYear, status, household.filerAgeIn(taxYear), secondAge);
        }
        return birthYear != null
                ? federalTaxCalculator.computeMaxIncomeForBracket(targetRate, taxYear, status,
                        taxYear - birthYear, null)
                : computeMaxIncomeForTargetRate(targetRate, taxYear, status);
    }

    @Override
    public Optional<BigDecimal> standardDeduction(int taxYear, FilingStatus status) {
        if (household != null) {
            Integer secondAge = status == FilingStatus.MARRIED_FILING_JOINTLY
                    ? household.secondFilerAgeIn(taxYear) : null;
            return Optional.of(federalTaxCalculator.loadStandardDeduction(
                    taxYear, status, household.filerAgeIn(taxYear), secondAge));
        }
        return Optional.of(birthYear != null
                ? federalTaxCalculator.loadStandardDeduction(taxYear, status, taxYear - birthYear)
                : federalTaxCalculator.loadStandardDeduction(taxYear, status));
    }
```

(add `import java.util.Optional;` to `FederalOnlyTaxStrategy.java`.)

- [ ] **Step 8: Run the tax suite to verify everything passes**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest='RealTermsDeflatorTest,SocialSecurityTaxCalculatorTest,CapitalGainsTaxCalculatorTest,IrmaaSurchargeCalculatorTest,FederalTaxCalculatorTest,FederalOnlyTaxStrategyTest,CombinedTaxCalculatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 9: Confirm no engine behavior moved, then run gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=ProjectionGoldenFileTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS without `-Dupdate.golden` (the deflator move is behavior-identical; nothing else calls the new methods yet).

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/ \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/tax/
git commit -F - <<'EOF'
refactor(core): add tax-space accessors and a shared real-terms deflator

Introduces RealTermsDeflator (BigDecimal + double) and routes the two
copy-pasted SS / NIIT threshold deflators through it (behavior-identical).
Adds the read-only accessors the Phase 1a tax-space calculator needs:
SocialSecurityTaxCalculator.thresholds, CapitalGainsTaxCalculator.ltcgBandRoom,
IrmaaSurchargeCalculator.loadTiers (plus a corrected per-enrollee Javadoc),
an age-aware FederalTaxCalculator.computeMaxIncomeForBracket overload, and
TaxCalculationStrategy.computeGrossCeilingForRate / standardDeduction with
age-aware overrides in CombinedTaxCalculator and FederalOnlyTaxStrategy.
Existing age-less ceiling methods are unchanged.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 7: `TaxSpaceCalculator` with its fixture suite

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/TaxSpaceCalculator.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/tax/TaxSpaceCalculatorTest.java` (new)

**Interfaces:**
- Consumes: Task 5 `YearTaxPicture`, `TaxSpaceYear`, `TaxSpaceYear.BracketRoom`; Task 6 `SocialSecurityTaxCalculator.thresholds`, `CapitalGainsTaxCalculator.ltcgBandRoom`, `CapitalGainsTaxCalculator.niitThresholdReal`, `IrmaaSurchargeCalculator.loadTiers` / `IrmaaTier`, `TaxCalculationStrategy.computeGrossCeilingForRate`, `TaxCalculationStrategy.standardDeduction`, `FederalTaxCalculator.loadOrdinaryBrackets` and `loadStandardDeduction(int, FilingStatus, int, Integer)`.
- Deduction rule (for `ordinaryTaxable` and the LTCG stacking floor): `itemizedDeductions()` of the run strategy's `CombinedTaxResult` when `usedItemized()`; otherwise `strategy.standardDeduction(year, status)`; only when that is empty, `federal.loadStandardDeduction(year, status, primaryAge, spouseAge)` from the picture. The picture's ages are a fallback, never the primary source.
- Produces: `@Component TaxSpaceCalculator(FederalTaxCalculator, CapitalGainsTaxCalculator, @Nullable IrmaaSurchargeCalculator)` with `public TaxSpaceYear compute(YearTaxPicture p, TaxCalculationStrategy strategy)`. Task 9 calls it once per retired year with the run's strategy.
- Semantics Task 9 must honor when building pictures: `primaryAge` = `HouseholdContext.filerAgeIn(year)` (else `year - birthYear`); `spouseAge` = `HouseholdContext.secondFilerAgeIn(year)` (else null). The calculator RE-DERIVES Social Security taxability from provisional income = `ordinaryIncomeExSocialSecurity + traditionalDistributions + rothConversion + ordinaryInterest + qualifiedDividendsAndLtcg` (+ half the benefit); it does not trust `socialSecurityTaxable`. IRMAA and NIIT headroom use `p.magi()`.
- Known engine divergence (do NOT mirror it here): `PoolStrategy.MultiPool.resolveOrdinaryDeduction` (~:1500) nets the LTCG stacking floor with the AGE-UNAWARE standard deduction. Task 9 fixes the engine to use `strategy.standardDeduction(...)` as well, so reconciliation agrees for 65+ filers.

- [ ] **Step 1: Write the failing fixture suite**

Create `backend/wealthview-core/src/test/java/com/wealthview/core/projection/tax/TaxSpaceCalculatorTest.java`:

```java
package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.IrmaaTierRepository;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Hand-worked fixtures against the ACTUAL 2025 seed values (R__seed_*.sql):
 * <ul>
 *   <li>Standard deduction: single 15,750 (+2,000 at 65+); MFJ 31,500 (+1,600 per spouse 65+).</li>
 *   <li>Ordinary single: 10% to 11,925 / 12% to 48,475 / 22% to 103,350 / 24% to 197,300 / 32% to 250,525 /
 *       35% to 626,350 / 37%. MFJ 12% tops out at 96,950.</li>
 *   <li>LTCG single: 0% to 48,350 / 15% to 533,400 / 20%. NIIT threshold 200,000 single / 250,000 MFJ.</li>
 *   <li>SS thresholds single 25,000 / 34,000. IRMAA single tier ceilings 106,000 / 133,000 / 167,000 / ...;
 *       annual per-person surcharges 0 / 1,052.40 / 2,643.60 / 4,234.80 / ...</li>
 * </ul>
 * Year 2025, yearsFromBase 0 (no deflation).
 */
@ExtendWith(MockitoExtension.class)
class TaxSpaceCalculatorTest {

    private static final int YEAR = 2025;
    private static final BigDecimal INFLATION = new BigDecimal("0.025");

    @Mock private TaxBracketRepository taxBracketRepository;
    @Mock private StandardDeductionRepository standardDeductionRepository;
    @Mock private LtcgBracketRepository ltcgBracketRepository;
    @Mock private IrmaaTierRepository irmaaTierRepository;

    private FederalTaxCalculator federal;
    private TaxSpaceCalculator calculator;

    @BeforeEach
    void setUp() {
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(),
                        eq("married_filing_jointly")))
                .thenReturn(mfj2025Brackets());
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(YEAR, "single", bd("15750"), bd("2000"))));
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("married_filing_jointly")))
                .thenReturn(Optional.of(
                        new StandardDeductionEntity(YEAR, "married_filing_jointly", bd("31500"), bd("1600"))));
        stubSingle2025Ltcg(ltcgBracketRepository);
        stubMfj2025Ltcg(ltcgBracketRepository);
        stubSingle2025Irmaa(irmaaTierRepository);
        stubMfj2025Irmaa(irmaaTierRepository);
        federal = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        calculator = new TaxSpaceCalculator(federal, new CapitalGainsTaxCalculator(ltcgBracketRepository),
                new IrmaaSurchargeCalculator(irmaaTierRepository));
    }

    /** A single filer born {@code YEAR - age}, federal-only. */
    private FederalOnlyTaxStrategy singleStrategy(int age) {
        return new FederalOnlyTaxStrategy(federal, YEAR - age);
    }

    private static YearTaxPicture picture(FilingStatus status, int age, Integer spouseAge, String ordinaryExSs,
                                          String ssBenefit, String traditional, String ltcg, String magi,
                                          int medicareCount) {
        return new YearTaxPicture(YEAR, status, age, spouseAge, bd(ordinaryExSs), bd(ssBenefit), BigDecimal.ZERO,
                bd(traditional), BigDecimal.ZERO, BigDecimal.ZERO, bd(ltcg), bd(magi), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, INFLATION, medicareCount);
    }

    @Test
    void compute_zeroIncomeYear_fullDeductionRoomZeroMarginalNoSsNoIrmaa() {
        var p = picture(FilingStatus.SINGLE, 60, null, "0", "0", "0", "0", "0", 0);

        TaxSpaceYear t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0");
        // Every bracket with a ceiling is listed: ceiling + 15,750 deduction, all room.
        assertThat(t.bracketRoom()).hasSize(6);
        assertThat(t.bracketRoom().get(0).rate()).isEqualByComparingTo("0.10");
        assertThat(t.bracketRoom().get(0).grossCeiling()).isEqualByComparingTo("27675"); // 11925 + 15750
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("27675");
        assertThat(t.bracketRoom().get(1).room()).isEqualByComparingTo("64225");          // 48475 + 15750
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("48350");
        assertThat(t.ltcgFifteenRoom()).isEqualByComparingTo("485050");                   // 533400 - 48350
        assertThat(t.provisionalIncome()).isNull();
        assertThat(t.ssBaseThreshold()).isNull();
        assertThat(t.ssUpperThreshold()).isNull();
        assertThat(t.ssInclusionRate()).isNull();
        assertThat(t.niitHeadroom()).isEqualByComparingTo("200000");
        assertThat(t.irmaaPremiumYear()).isNull();
        assertThat(t.irmaaTier()).isNull();
        assertThat(t.irmaaRoomToNextTier()).isNull();
        assertThat(t.irmaaNextTierAnnualCost()).isNull();
        // +1000 ordinary stays under the deduction; +1000 LTCG sits in the 0% band.
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0");
        assertThat(t.age()).isEqualTo(60);
        assertThat(t.year()).isEqualTo(YEAR);
    }

    @Test
    void compute_pensionInTwelvePercentBracket_reportsRoomAndTwelvePercentMarginal() {
        // gross 50,000 ; taxable 50,000 - 15,750 = 34,250 -> 12% bracket.
        var p = picture(FilingStatus.SINGLE, 60, null, "50000", "0", "0", "0", "50000", 0);

        var t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.12");
        assertThat(t.bracketRoom().get(0).rate()).isEqualByComparingTo("0.12");
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("14225");   // 64225 - 50000
        assertThat(t.bracketRoom().get(1).rate()).isEqualByComparingTo("0.22");
        assertThat(t.bracketRoom().get(1).room()).isEqualByComparingTo("69100");   // 103350 + 15750 - 50000
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("14100");                // 48350 - 34250
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.12");    // +1000 x 12%
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0");          // 34250..35250 is in the 0% band
        assertThat(t.niitHeadroom()).isEqualByComparingTo("150000");
    }

    @Test
    void compute_ordinaryPushesGainsFromZeroToFifteen_effectiveOrdinaryIs27Percent() {
        // ordinary 60,000 -> taxable 44,250 (12%) ; LTCG 10,000 stacked 44,250..54,250:
        //   4,100 at 0% (to 48,350) + 5,900 at 15% = 885.
        // +1,000 ordinary: +120 ordinary tax ; 0% room shrinks to 3,100 -> 6,900 at 15% = 1,035 (+150). Total +270.
        // +1,000 LTCG: lands wholly above 48,350 -> +150.
        var p = picture(FilingStatus.SINGLE, 60, null, "60000", "0", "0", "10000", "70000", 0);

        var t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.12");
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("0");
        assertThat(t.ltcgFifteenRoom()).isEqualByComparingTo("479150");          // 533400 - 54250
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("4225");   // 64225 - 60000
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.27");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0.15");
    }

    @Test
    void compute_socialSecurityTorpedo_age66_effectiveOrdinaryIs18Point5Percent() {
        // Age 66 -> deduction 15,750 + 2,000 = 17,750. SS 30,000, pension 20,000.
        // provisional = 20,000 + 15,000 = 35,000 > 34,000:
        //   taxable SS = min(15,000, 4,500) + (1,000 x 0.85) = 5,350 (cap 25,500).
        // gross ordinary 25,350 ; taxable 7,600 -> 10% -> 760.
        // +1,000 ordinary: SS taxable 4,500 + 2,000 x 0.85 = 6,200 (+850) ; gross 27,200 ; taxable 9,450 -> 945 (+185).
        // +1,000 LTCG: provisional +1,000 -> SS +850 -> taxable 8,450 -> 845 (+85) ; the gain itself is in 0%.
        var p = picture(FilingStatus.SINGLE, 66, null, "20000", "30000", "0", "0", "25350", 1);

        var t = calculator.compute(p, singleStrategy(66));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.10");
        assertThat(t.provisionalIncome()).isEqualByComparingTo("35000");
        assertThat(t.ssBaseThreshold()).isEqualByComparingTo("25000");
        assertThat(t.ssUpperThreshold()).isEqualByComparingTo("34000");
        assertThat(t.ssInclusionRate()).isEqualByComparingTo("0.85");
        assertThat(t.bracketRoom().get(0).rate()).isEqualByComparingTo("0.10");
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("4325");   // 11925 + 17750 - 25350
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("40750");               // 48350 - 7600
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.185");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0.085");
        // IRMAA: medicare-age in 2027, MAGI 25,350 sits in tier 0 (<= 106,000).
        assertThat(t.irmaaPremiumYear()).isEqualTo(2027);
        assertThat(t.irmaaTier()).isZero();
        assertThat(t.irmaaRoomToNextTier()).isEqualByComparingTo("80650");        // 106000 - 25350
        assertThat(t.irmaaNextTierAnnualCost()).isEqualByComparingTo("1052.40");  // (74.00 + 13.70) x 12 x 1
    }

    @ParameterizedTest(name = "pension {0} -> provisional {1}, inclusion {2}")
    @CsvSource({
            // SS 20,000 (half = 10,000), single, age 66, no deflation.
            "10000, 20000, 0.00",   // below 25,000 base
            "15000, 25000, 0.50",   // EXACTLY at the base: the next dollar is taxed at 50%
            "20000, 30000, 0.50",   // inside tier 1 (taxable 2,500 < 50% cap)
            "24000, 34000, 0.85",   // EXACTLY at the upper threshold: next dollar enters tier 2
            "40000, 50000, 0.00"    // tier 2 but already capped at 85% of benefits (17,000)
    })
    void compute_ssInclusionRate_acrossThresholdBoundaries(String pension, String provisional, String inclusion) {
        var p = picture(FilingStatus.SINGLE, 66, null, pension, "20000", "0", "0", pension, 1);

        var t = calculator.compute(p, singleStrategy(66));

        assertThat(t.provisionalIncome()).isEqualByComparingTo(provisional);
        assertThat(t.ssInclusionRate()).isEqualByComparingTo(inclusion);
    }

    @Test
    void compute_mfjBothOver65_twoAddersAndIrmaaCostForTwoEnrollees() {
        // Household: primary 1958 (67), spouse 1959 (66), both alive. Deduction 31,500 + 2 x 1,600 = 34,700.
        // Traditional 120,000 -> taxable 85,300 -> 12% (MFJ 12% to 96,950).
        var household = HouseholdContext.of(1958, 95, 1959, 95, 2060);
        var strategy = new FederalOnlyTaxStrategy(federal, 1958, household);
        var p = picture(FilingStatus.MARRIED_FILING_JOINTLY, 67, 66, "0", "0", "120000", "0", "120000", 2);

        var t = calculator.compute(p, strategy);

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.12");
        assertThat(t.bracketRoom().get(0).grossCeiling()).isEqualByComparingTo("131650"); // 96950 + 34700
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("11650");
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.12");
        assertThat(t.niitHeadroom()).isEqualByComparingTo("130000");                     // 250000 - 120000
        assertThat(t.irmaaTier()).isZero();
        assertThat(t.irmaaRoomToNextTier()).isEqualByComparingTo("92000");               // 212000 - 120000
        assertThat(t.irmaaNextTierAnnualCost()).isEqualByComparingTo("2104.80");         // 1052.40 x 2
    }

    @Test
    void compute_irmaaMidTier_reportsTierIndexRoomAndIncrementalCost() {
        // MAGI 140,000 single: tiers <=106k (0), <=133k (1), <=167k (2) -> tier 2.
        // Next-tier cost: (295.90 + 57.00 - 185.00 - 35.30) x 12 = 132.60 x 12 = 1,591.20.
        var p = picture(FilingStatus.SINGLE, 70, null, "0", "0", "140000", "0", "140000", 1);

        var t = calculator.compute(p, singleStrategy(70));

        assertThat(t.irmaaTier()).isEqualTo(2);
        assertThat(t.irmaaRoomToNextTier()).isEqualByComparingTo("27000");               // 167000 - 140000
        assertThat(t.irmaaNextTierAnnualCost()).isEqualByComparingTo("1591.20");
    }

    @Test
    void compute_overNiitThreshold_negativeHeadroomAndNiitInBothMarginals() {
        // Single, age 60: ordinary 180,000 (taxable 164,250, 24%) + LTCG 50,000 -> MAGI 230,000.
        // NIIT base = min(50,000, 230,000 - 200,000) = 30,000.
        // +1,000 ordinary: 24% = 240 ; NIIT base -> 31,000 (+38). Total 278.
        // +1,000 LTCG: 15% = 150 (164,250..215,250 is all in the 15% band) ; NIIT +38. Total 188.
        var p = picture(FilingStatus.SINGLE, 60, null, "180000", "0", "0", "50000", "230000", 0);

        var t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.24");
        assertThat(t.niitHeadroom()).isEqualByComparingTo("-30000");
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("0");
        assertThat(t.ltcgFifteenRoom()).isEqualByComparingTo("319150");                  // 533400 - 214250
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.278");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0.188");
    }

    @Test
    void compute_strategyDeductionTakesPrecedenceOverPictureAges() {
        // The strategy knows the filer is 66 (deduction 17,750); the picture claims 60. Strategy wins:
        // gross 25,350 -> taxable 7,600 -> 0% LTCG room 48,350 - 7,600 = 40,750 (a picture-age lookup
        // would give 15,750 -> taxable 9,600 -> 38,750).
        var p = picture(FilingStatus.SINGLE, 60, null, "25350", "0", "0", "0", "25350", 0);

        var t = calculator.compute(p, singleStrategy(66));

        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("40750");
    }

    @Test
    void compute_strategyWithoutDeductionConcept_fallsBackToPictureAges() {
        // A bare strategy: Optional.empty() standard deduction -> picture age 66 -> 17,750.
        TaxCalculationStrategy bare = new TaxCalculationStrategy() {
            @Override
            public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
                return federal.computeTax(grossIncome, taxYear, status, 66);
            }

            @Override
            public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear,
                                                             FilingStatus status) {
                return federal.computeMaxIncomeForBracket(targetRate, taxYear, status);
            }
        };
        var p = picture(FilingStatus.SINGLE, 66, null, "25350", "0", "0", "0", "25350", 0);

        var t = calculator.compute(p, bare);

        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("40750");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=TaxSpaceCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `cannot find symbol: class TaxSpaceCalculator`.

- [ ] **Step 3: Implement `TaxSpaceCalculator`**

Create `backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/TaxSpaceCalculator.java`:

```java
package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.YearTaxPicture;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Phase 1a (spec §1.2): derives one year's "tax space" from its realized {@link YearTaxPicture}.
 * Effective marginal rates come from re-pricing the year with a +$1,000 ordinary or +$1,000 LTCG probe
 * through the SAME calculators the engine uses: Social Security inclusion is recomputed from the new
 * provisional income and LTCG is re-stacked, so the torpedo and the 0%-to-15% push fall out naturally.
 * "Total tax" = ordinary federal + state (via the run's {@link TaxCalculationStrategy}) + LTCG bracket tax
 * + NIIT. IRMAA is a cliff, reported as a tier distance and dollar jump, never blended into a rate.
 */
@Component
public class TaxSpaceCalculator {

    private static final BigDecimal PROBE = new BigDecimal("1000");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final int RATE_SCALE = 4;
    private static final int INCLUSION_SCALE = 2;
    private static final int IRMAA_LOOKBACK_YEARS = 2;

    private final FederalTaxCalculator federal;
    private final CapitalGainsTaxCalculator capitalGains;
    @Nullable
    private final IrmaaSurchargeCalculator irmaa;
    private final SocialSecurityTaxCalculator socialSecurity = new SocialSecurityTaxCalculator();

    public TaxSpaceCalculator(FederalTaxCalculator federal, CapitalGainsTaxCalculator capitalGains,
                              @Nullable IrmaaSurchargeCalculator irmaa) {
        this.federal = federal;
        this.capitalGains = capitalGains;
        this.irmaa = irmaa;
    }

    public TaxSpaceYear compute(YearTaxPicture p, TaxCalculationStrategy strategy) {
        var base = evaluate(p, strategy, BigDecimal.ZERO, BigDecimal.ZERO);
        var plusOrdinary = evaluate(p, strategy, PROBE, BigDecimal.ZERO);
        var plusLtcg = evaluate(p, strategy, BigDecimal.ZERO, PROBE);
        BigDecimal marginalRate = marginalOrdinaryRate(p, base);
        var ltcgRoom = capitalGains.ltcgBandRoom(base.ordinaryTaxable(), p.qualifiedDividendsAndLtcg(),
                p.year(), p.filingStatus());
        var ss = socialSecuritySection(p);
        var irmaaSection = irmaaSection(p);
        BigDecimal niitHeadroom = capitalGains.niitThresholdReal(p.filingStatus(), p.yearsFromBase(),
                p.inflationRate()).subtract(p.magi());
        return new TaxSpaceYear(p.year(), p.primaryAge(), scale(p.magi()), marginalRate,
                bracketRoom(p, strategy, base.grossOrdinary(), marginalRate),
                scale(ltcgRoom.zeroRoom()), scale(ltcgRoom.fifteenRoom()),
                ss.provisionalIncome(), ss.base(), ss.upper(), ss.inclusionRate(),
                scale(niitHeadroom),
                irmaaSection.premiumYear(), irmaaSection.tier(), irmaaSection.room(), irmaaSection.nextCost(),
                rate(plusOrdinary.totalTax().subtract(base.totalTax())),
                rate(plusLtcg.totalTax().subtract(base.totalTax())));
    }

    private record Evaluation(BigDecimal grossOrdinary, BigDecimal deduction, BigDecimal ordinaryTaxable,
                              BigDecimal totalTax) {
    }

    private Evaluation evaluate(YearTaxPicture p, TaxCalculationStrategy strategy,
                                BigDecimal extraOrdinary, BigDecimal extraLtcg) {
        BigDecimal ssTaxable = socialSecurityTaxable(p, extraOrdinary.add(extraLtcg));
        BigDecimal grossOrdinary = nonSocialSecurityOrdinary(p).add(extraOrdinary).add(ssTaxable);
        BigDecimal ltcg = p.qualifiedDividendsAndLtcg().add(extraLtcg);
        var detail = strategy.computeDetailedTax(grossOrdinary, p.year(), p.filingStatus(), ltcg, ssTaxable);
        BigDecimal deduction = detail.usedItemized() ? detail.itemizedDeductions() : standardDeduction(p, strategy);
        BigDecimal ordinaryTaxable = grossOrdinary.subtract(deduction).max(BigDecimal.ZERO);
        BigDecimal ltcgTax = capitalGains.computeLtcgTax(ordinaryTaxable, ltcg, p.year(), p.filingStatus(),
                p.yearsFromBase(), p.inflationRate(), grossOrdinary.add(ltcg), p.netRentalIncome());
        return new Evaluation(grossOrdinary, deduction, ordinaryTaxable, detail.totalTax().add(ltcgTax));
    }

    /**
     * The non-itemized deduction: the run strategy's own age-aware amount (the one its ordinary tax
     * subtracts), falling back to a picture-age lookup only for strategies with no deduction concept.
     */
    private BigDecimal standardDeduction(YearTaxPicture p, TaxCalculationStrategy strategy) {
        return strategy.standardDeduction(p.year(), p.filingStatus()).orElseGet(() -> {
            Integer secondAge = p.filingStatus() == FilingStatus.MARRIED_FILING_JOINTLY ? p.spouseAge() : null;
            return federal.loadStandardDeduction(p.year(), p.filingStatus(), p.primaryAge(), secondAge);
        });
    }

    private static BigDecimal nonSocialSecurityOrdinary(YearTaxPicture p) {
        return p.ordinaryIncomeExSocialSecurity().add(p.traditionalDistributions())
                .add(p.rothConversion()).add(p.ordinaryInterest());
    }

    /** IRS provisional income before the half-benefit: every AGI item except Social Security itself. */
    private static BigDecimal provisionalExSocialSecurity(YearTaxPicture p) {
        return nonSocialSecurityOrdinary(p).add(p.qualifiedDividendsAndLtcg());
    }

    private BigDecimal socialSecurityTaxable(YearTaxPicture p, BigDecimal extraProvisional) {
        if (p.socialSecurityBenefit().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return socialSecurity.computeTaxableAmount(p.socialSecurityBenefit(),
                provisionalExSocialSecurity(p).add(extraProvisional), p.filingStatus().value(),
                p.yearsFromBase(), p.inflationRate());
    }

    private BigDecimal marginalOrdinaryRate(YearTaxPicture p, Evaluation base) {
        if (base.grossOrdinary().compareTo(base.deduction()) < 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE);
        }
        for (var bracket : federal.loadOrdinaryBrackets(p.year(), p.filingStatus())) {
            if (bracket.ceiling() == null || base.ordinaryTaxable().compareTo(bracket.ceiling()) < 0) {
                return bracket.rate().setScale(RATE_SCALE, ROUNDING);
            }
        }
        return BigDecimal.ZERO.setScale(RATE_SCALE);
    }

    private List<TaxSpaceYear.BracketRoom> bracketRoom(YearTaxPicture p, TaxCalculationStrategy strategy,
                                                       BigDecimal grossOrdinary, BigDecimal marginalRate) {
        var rooms = new ArrayList<TaxSpaceYear.BracketRoom>();
        for (var bracket : federal.loadOrdinaryBrackets(p.year(), p.filingStatus())) {
            if (bracket.ceiling() == null || bracket.rate().compareTo(marginalRate) < 0) {
                continue;
            }
            BigDecimal grossCeiling = strategy.computeGrossCeilingForRate(bracket.rate(), p.year(),
                    p.filingStatus());
            rooms.add(new TaxSpaceYear.BracketRoom(bracket.rate().setScale(RATE_SCALE, ROUNDING),
                    scale(grossCeiling), scale(grossCeiling.subtract(grossOrdinary).max(BigDecimal.ZERO))));
        }
        return List.copyOf(rooms);
    }

    private record SsSection(@Nullable BigDecimal provisionalIncome, @Nullable BigDecimal base,
                             @Nullable BigDecimal upper, @Nullable BigDecimal inclusionRate) {
        static final SsSection NONE = new SsSection(null, null, null, null);
    }

    private SsSection socialSecuritySection(YearTaxPicture p) {
        if (p.socialSecurityBenefit().signum() <= 0) {
            return SsSection.NONE;
        }
        BigDecimal provisional = provisionalExSocialSecurity(p).add(p.socialSecurityBenefit().multiply(HALF));
        var thresholds = socialSecurity.thresholds(p.filingStatus(), p.yearsFromBase(), p.inflationRate());
        // Inclusion per extra $1 of income: 0, 0.50 or 0.85 (0 again once the 50% / 85% cap binds).
        BigDecimal inclusion = socialSecurityTaxable(p, BigDecimal.ONE)
                .subtract(socialSecurityTaxable(p, BigDecimal.ZERO))
                .setScale(INCLUSION_SCALE, ROUNDING);
        return new SsSection(scale(provisional), thresholds.base(), thresholds.upper(), inclusion);
    }

    private record IrmaaSection(@Nullable Integer premiumYear, @Nullable Integer tier,
                                @Nullable BigDecimal room, @Nullable BigDecimal nextCost) {
        static final IrmaaSection NONE = new IrmaaSection(null, null, null, null);
    }

    private IrmaaSection irmaaSection(YearTaxPicture p) {
        if (irmaa == null || p.medicareCountInPremiumYear() <= 0) {
            return IrmaaSection.NONE;
        }
        int premiumYear = p.year() + IRMAA_LOOKBACK_YEARS;
        var tiers = irmaa.loadTiers(premiumYear, p.filingStatus());
        if (tiers.isEmpty()) {
            return IrmaaSection.NONE;
        }
        int index = tierIndex(tiers, p.magi());
        var current = tiers.get(index);
        if (index == tiers.size() - 1) {
            return new IrmaaSection(premiumYear, index, null, null);
        }
        var next = tiers.get(index + 1);
        BigDecimal enrollees = BigDecimal.valueOf(p.medicareCountInPremiumYear());
        return new IrmaaSection(premiumYear, index,
                scale(current.magiCeiling().subtract(p.magi())),
                scale(next.annualSurchargePerPerson().subtract(current.annualSurchargePerPerson())
                        .multiply(enrollees)));
    }

    /** First tier whose ceiling is at or above {@code magi} ("up to and including" semantics). */
    private static int tierIndex(List<IrmaaTier> tiers, BigDecimal magi) {
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal ceiling = tiers.get(i).magiCeiling();
            if (ceiling == null || magi.compareTo(ceiling) <= 0) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    private static BigDecimal rate(BigDecimal taxDelta) {
        return taxDelta.divide(PROBE, RATE_SCALE, ROUNDING);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }
}
```

- [ ] **Step 4: Run the fixture suite to verify it passes**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=TaxSpaceCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (10 tests + 5 parameterized cases). If a figure is off, recompute it by hand from the class Javadoc's seed table before touching the implementation — the fixtures are the specification.

- [ ] **Step 5: Run the core quality gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs`
Expected: BUILD SUCCESS. If PMD flags `TaxSpaceCalculator` for complexity, extract a private method rather than suppressing; any suppression needs an adjacent justification comment.

- [ ] **Step 6: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/tax/TaxSpaceCalculator.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/tax/TaxSpaceCalculatorTest.java
git commit -F - <<'EOF'
feat(core): add TaxSpaceCalculator for per-year tax space

Derives a TaxSpaceYear from a YearTaxPicture: room to the top of each
ordinary bracket (age-aware gross ceilings), 0%/15% LTCG band room after
stacking, the Social Security provisional-income zone and per-dollar
inclusion rate, NIIT headroom, IRMAA tier distance and incremental cost for
the premium year (y+2), and effective marginal rates from +$1,000 ordinary
and LTCG probes re-priced through the same calculators the engine uses
(capturing the SS torpedo and the 0%-to-15% push). IRMAA stays a cliff, not
part of the rate. Fixtures are hand-worked against the 2025 seed values.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 8: fix — Social Security provisional income uses non-SS **taxable** income

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/IncomeSourceProcessor.java` (`process(...)` 11-arg overload, ~:134-226)
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/IncomeSourceProcessorTest.java` (append)

**Interfaces:**
- Consumes: nothing new.
- Produces: unchanged signatures. Behavior: the SS provisional-income base becomes (sum of non-SS sources' `taxableIncome`) + `magi` + `additionalProvisionalIncome`, instead of the sum of non-SS gross `amount`s. The local `ssBenefit` (sum of SS cash inflows) is computed inside the single loop. Task 9 adds it to `IncomeSourceYearResult` as `socialSecurityBenefit`.

Why: `process` currently sums every non-SS source's GROSS real amount into provisional income. For a rental that is gross rent, not net taxable rent; for a self-employment source it skips the SE-tax deduction; for a `tax_free` source it counts money that is not in AGI. IRS provisional income is AGI excluding SS plus half the benefit, so the taxable amounts are the right base.

- [ ] **Step 1: Write the failing tests**

Append to `IncomeSourceProcessorTest.java` (real calculators, so the provisional-income arithmetic is exercised end to end):

```java
    // === Phase 1a Task 8: SS provisional income uses non-SS TAXABLE income ===

    private static IncomeSourceProcessor realProcessor() {
        return new IncomeSourceProcessor(new RentalLossCalculator(), new SocialSecurityTaxCalculator(),
                new SelfEmploymentTaxCalculator());
    }

    @Test
    void process_rentalAndSocialSecurity_provisionalIncomeUsesNetRentNotGrossRent() {
        // Rental: gross 40,000, operating expenses 30,000 -> net taxable 10,000.
        // SS 24,000 (half = 12,000). Single, no deflation (taxYear == baseYear).
        //   Correct provisional = 10,000 + 12,000 = 22,000 <= 25,000 -> SS taxable 0.
        //   Old (gross) provisional = 40,000 + 12,000 = 52,000 -> 4,500 + 18,000 x 0.85 = 19,800.
        var rental = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", IncomeSourceType.RENTAL_PROPERTY,
                new BigDecimal("40000"), 65, null, BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("30000"), null, null, null, null, null);
        var socialSecurity = makeSource(IncomeSourceType.SOCIAL_SECURITY, new BigDecimal("24000"),
                65, null, BigDecimal.ZERO, "taxable");

        var result = realProcessor().process(List.of(rental, socialSecurity), 67, 1, 2025,
                BigDecimal.ZERO, FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO, 2025);

        assertThat(result.socialSecurityTaxable()).isEqualByComparingTo("0");
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo("10000");
    }

    @Test
    void process_taxablePensionAndSocialSecurity_unchangedByTheFix() {
        // Regression guard: a fully taxable pension's taxable == gross, so the result is the same as before.
        // provisional = 30,000 + 12,000 = 42,000 -> 4,500 + 8,000 x 0.85 = 11,300 (cap 20,400).
        var pension = makeSource(IncomeSourceType.PENSION, new BigDecimal("30000"), 65, null,
                BigDecimal.ZERO, "taxable");
        var socialSecurity = makeSource(IncomeSourceType.SOCIAL_SECURITY, new BigDecimal("24000"),
                65, null, BigDecimal.ZERO, "taxable");

        var result = realProcessor().process(List.of(pension, socialSecurity), 67, 1, 2025,
                BigDecimal.ZERO, FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO, 2025);

        assertThat(result.socialSecurityTaxable()).isEqualByComparingTo("11300");
        assertThat(result.totalTaxableIncome()).isEqualByComparingTo("41300");
    }
```

- [ ] **Step 2: Run them to verify the first fails**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=IncomeSourceProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `process_rentalAndSocialSecurity_provisionalIncomeUsesNetRentNotGrossRent`: expected `0` but was `19800.0000`. (The pension regression guard passes.)

- [ ] **Step 3: Collapse the two loops and compute SS taxability after the loop**

In `IncomeSourceProcessor.process(...)` (11-arg overload):

1. Delete the first loop — the block that starts with the comment `// Collect non-SS income first (needed for SS provisional income calc)`, declares `nonSSIncome` and `ssBenefit`, iterates `sources`, and then computes `BigDecimal combinedSsTaxable = ...` (through the end of that ternary). Keep the multi-line `// Combined Social Security taxability (audit B2 / T3-1)` comment; it moves in step 3.

2. Before the (remaining) `for (var source : sources)` loop, declare:

```java
        BigDecimal ssBenefit = BigDecimal.ZERO;
```

and inside the loop, directly after `incomeBySource.merge(sourceKey, result.cashInflow(), BigDecimal::add);`, add:

```java
            if (result instanceof SocialSecurityResult) {
                ssBenefit = ssBenefit.add(result.cashInflow());
            }
```

3. Replace the post-loop block

```java
        // Social Security taxable income is the single combined figure, added once (its per-source
        // cash inflow was already folded into totalCashInflow / incomeBySource in the loop above).
        totalTaxableIncome = totalTaxableIncome.add(combinedSsTaxable);
```

with:

```java
        // Combined Social Security taxability (audit B2 / T3-1): ALL Social Security sources share ONE
        // provisional-income computation. Provisional = non-SS TAXABLE income (Phase 1a Task 8: net
        // rent after expenses/depreciation/loss rules, SE income after its half-SE-tax deduction,
        // tax-free sources excluded -- the IRS worksheet's AGI-ex-SS, not gross cash) + static other
        // income + portfolio ordinary income realized this year (additionalProvisionalIncome) + 50% of
        // the AGGREGATED benefit -- so MFJ spousal benefits combine and portfolio withdrawals/RMDs/
        // conversions/gains drag SS into taxation. Social Security results carry zero taxable income,
        // so totalTaxableIncome here is exactly the non-SS taxable sum.
        BigDecimal nonSsTaxableIncome = totalTaxableIncome;
        BigDecimal combinedSsTaxable = ssBenefit.compareTo(BigDecimal.ZERO) > 0
                ? ssTaxCalculator.computeTaxableAmount(
                        ssBenefit,
                        nonSsTaxableIncome.add(magi).add(additionalProvisionalIncome),
                        filingStatus.value(),
                        Math.max(0, taxYear - baseYear),
                        scenarioInflationRate)
                : BigDecimal.ZERO;

        // Social Security taxable income is the single combined figure, added once (its per-source
        // cash inflow was already folded into totalCashInflow / incomeBySource in the loop above).
        totalTaxableIncome = totalTaxableIncome.add(combinedSsTaxable);
```

- [ ] **Step 4: Run the processor tests to verify they pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=IncomeSourceProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all existing tests plus the two new ones).

- [ ] **Step 5: Run the whole projection suite, including the goldens**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test`
Expected: PASS with no golden change. No golden input combines Social Security with a rental, self-employment, or `tax_free` source (checked: `household-survivor` and `ss-rmd-retiree` pair SS only with `taxable` sources), and no existing engine test method mixes `RENTAL_PROPERTY` with Social Security.

If `ProjectionGoldenFileTest` or another test DOES fail:
1. Confirm the failing scenario contains Social Security plus a rental, self-employment, or `tax_free` source. If it doesn't, the change has a bug: fix the code, not the test.
2. For a golden: regenerate with `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=ProjectionGoldenFileTest -Dupdate.golden=true -Dsurefire.failIfNoSpecifiedTests=false`, review `git diff backend/wealthview-projection/src/test/resources/golden/` line by line (only `socialSecurityTaxable` and the tax/balance figures downstream of it may move, and SS taxable may only DECREASE), and record each moved scenario's final balance and first-year SS taxable (before → after) in the commit body.
3. For a hand-pinned assertion: recompute the expected value by hand with the taxable-income base and update it with a comment showing the arithmetic.

- [ ] **Step 6: Run the projection quality gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/IncomeSourceProcessor.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/IncomeSourceProcessorTest.java
# Add the golden directory ONLY if Step 5 regenerated any file:
# git add backend/wealthview-projection/src/test/resources/golden/
git commit -F - <<'EOF'
fix(projection): base SS provisional income on taxable, not gross, income

IncomeSourceProcessor summed every non-Social-Security source's GROSS cash
into SS provisional income: a rental's gross rent instead of its net taxable
rent, self-employment income before its half-SE-tax deduction, and tax-free
sources that are not in AGI. That overstated SS taxability; for example,
$40k gross / $10k net rent plus $24k SS gave $19,800 of taxable SS instead
of $0.

The two loops collapse into one, and SS taxability is computed after it from
the summed non-SS taxable income (+ other income + realized portfolio income
+ half the benefit), matching the IRS worksheet. Fully taxable sources are
unaffected. Golden files: unchanged (no golden pairs SS with a rental,
self-employment or tax-free source). If a regeneration was needed, list each
moved scenario's final balance and first-year SS taxable, before -> after,
here.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```


### Task 9: Engine emits `YearTaxPicture` + `taxSpace`; reconciliation guard

Spec refs: §1.1, §1.3, §1.2 (consumer side). This task turns the empty `taxPictures` / `taxSpace` lists that Task 5 left in `ProjectionRunDetail` into real data. It adds the reconciliation test that keeps the tax-space model honest against the engine.

**Files:**
- Create: `backend/wealthview-projection/src/test/java/com/wealthview/projection/testutil/GoldenScenarios.java`
- Modify: `backend/wealthview-projection/src/test/java/com/wealthview/projection/ProjectionGoldenFileTest.java` (delegate parsing to `GoldenScenarios`)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/IncomeSourceProcessor.java:50-63` (record) and both `new IncomeSourceYearResult(` sites (~:132, ~:230)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java:1500-1508` (`resolveOrdinaryDeduction`)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java` (ctor, `YearStepResult`, `processYear`, `runProjection`)
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/IncomeSourceProcessorTest.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/MultiPoolCapitalGainsTest.java`
- Test (create): `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTaxPictureTest.java`
- Test (create): `backend/wealthview-projection/src/test/java/com/wealthview/projection/TaxSpaceReconciliationTest.java`

**Interfaces:**
- Consumes:
  - `YearTaxPicture`, `TaxSpaceYear`, `ProjectionRunDetail` (core dto, Tasks 5 and 7).
  - `ProjectionEngine.runDetailed(ProjectionInput)` and `DeterministicProjectionEngine.runDetailed` (Task 5). Task 5 left `taxPictures`/`taxSpace` as `List.of()`.
  - `TaxSpaceCalculator(FederalTaxCalculator, CapitalGainsTaxCalculator, @Nullable IrmaaSurchargeCalculator)` and `TaxSpaceYear compute(YearTaxPicture, TaxCalculationStrategy)` (Task 7).
  - `TaxSpaceYear.provisionalIncome()`: interpreted as IRS provisional income, i.e. other income **plus 50% of the benefit**. See the note in the reconciliation step.
  - Task 8 made `IncomeSourceProcessor`'s provisional income use non-SS **taxable** income.
  - `TaxCalculationStrategy.standardDeduction(int taxYear, FilingStatus status)` → `Optional<BigDecimal>` (Task 6; age-aware in `CombinedTaxCalculator` and `FederalOnlyTaxStrategy`, with their own unit tests).
- Produces:
  - `IncomeSourceProcessor.IncomeSourceYearResult.socialSecurityBenefit()`: a `BigDecimal` with the gross combined SS benefit, appended as the LAST record component.
  - `DeterministicProjectionEngine` public 7-arg `@Autowired` constructor `(FederalTaxCalculator, StateTaxCalculatorFactory, CapitalGainsTaxCalculator, IrmaaSurchargeCalculator, MeterRegistry, CapitalMarketAssumptionsProvider, TaxSpaceCalculator)`, all `@Nullable`. The old 6-arg constructor loses `@Autowired` and delegates with `null`.
  - `runDetailed(input).taxPictures()`: one picture per RETIRED year, in year order.
  - `runDetailed(input).taxSpace()`: one `TaxSpaceYear` per picture when a `TaxSpaceCalculator` and a tax strategy are present, else `List.of()`.
  - Test fixture `com.wealthview.projection.testutil.GoldenScenarios`:
    - `NAMES` (`List<String>`)
    - `MAPPER` (`ObjectMapper`)
    - `ProjectionInput loadInput(String)`
    - `Calculators calculators()` with `record Calculators(FederalTaxCalculator federal, CapitalGainsTaxCalculator capitalGains, IrmaaSurchargeCalculator irmaa)`

- [ ] **Step 1: Extract the golden-scenario loader (pure test refactor)**

Create `backend/wealthview-projection/src/test/java/com/wealthview/projection/testutil/GoldenScenarios.java`. Move these into it, unchanged in behavior, from `ProjectionGoldenFileTest`:
- the body of `parseInput`
- `resolveHousehold`
- `parseAccounts`
- `readResource`
- the repository-stub block that builds the three calculators

```java
package com.wealthview.projection.testutil;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.LifeExpectancy;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.IrmaaSurchargeCalculator;
import com.wealthview.persistence.repository.IrmaaTierRepository;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.mockito.Mockito.mock;

/**
 * Shared loader for the six deterministic-engine golden scenarios
 * ({@code src/test/resources/golden/<name>-input.json}), used by {@code ProjectionGoldenFileTest}
 * (byte-pinned output) and {@code TaxSpaceReconciliationTest} (tax-space vs charged tax).
 */
public final class GoldenScenarios {

    public static final List<String> NAMES = List.of(
            "simple-preretirement",
            "tiered-spending-with-income",
            "multi-pool-roth-conversion",
            "ss-rmd-retiree",
            "accumulation-gap-pension",
            "household-survivor");

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** The three real calculators the golden engine is wired with, backed by 2025 fixture stubs. */
    public record Calculators(FederalTaxCalculator federal, CapitalGainsTaxCalculator capitalGains,
                              IrmaaSurchargeCalculator irmaa) {
    }

    private GoldenScenarios() {
    }

    public static Calculators calculators() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        stubMfj2025(taxBracketRepo, deductionRepo);
        var ltcgBracketRepo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(ltcgBracketRepo);
        stubMfj2025Ltcg(ltcgBracketRepo);
        var irmaaTierRepo = mock(IrmaaTierRepository.class);
        stubSingle2025Irmaa(irmaaTierRepo);
        stubMfj2025Irmaa(irmaaTierRepo);
        return new Calculators(new FederalTaxCalculator(taxBracketRepo, deductionRepo),
                new CapitalGainsTaxCalculator(ltcgBracketRepo), new IrmaaSurchargeCalculator(irmaaTierRepo));
    }

    public static ProjectionInput loadInput(String scenario) throws IOException {
        return parseInput(readResource("golden/" + scenario + "-input.json"));
    }

    // parseInput / resolveHousehold / parseAccounts / readResource: MOVE the four private methods
    // from ProjectionGoldenFileTest here VERBATIM, changing only:
    //   - `private` -> `private static`
    //   - `getClass().getClassLoader()` -> `GoldenScenarios.class.getClassLoader()`
    //   - `new java.util.ArrayList<>` -> `new ArrayList<>` (import above)
    //   - `java.util.Optional.of(` -> `Optional.of(`
    // Keep every comment inside them.
}
```

> The comment block above is an instruction for the executor, not code to keep. Perform the move and delete the comment. The moved methods must keep their exact bodies; they are already in the repo at `ProjectionGoldenFileTest.java:110-255`.

Then edit `ProjectionGoldenFileTest`:
- Delete the four moved methods, the `MAPPER` field and the stub block.
- Replace the top of `run_matchesGoldenFile` (from `var inputJson = ...` through `var engine = new DeterministicProjectionEngine(...)`) with:

```java
        var input = GoldenScenarios.loadInput(scenario);
        var calcs = GoldenScenarios.calculators();

        var engine = new DeterministicProjectionEngine(
                calcs.federal(), null, calcs.capitalGains(), calcs.irmaa());
        var result = engine.run(input);

        var actualJson = GoldenScenarios.MAPPER.writeValueAsString(result);
```

- Keep the `@ValueSource` list and its comments as they are. Annotation values must be compile-time constants.
- Remove now-unused imports (Checkstyle fails on unused imports).

- [ ] **Step 2: Verify the refactor is behavior-neutral**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=ProjectionGoldenFileTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, with all 6 parameterized cases green and no golden file rewritten. Confirm with `git status --short backend/wealthview-projection/src/test/resources/golden/`, which must print nothing.

- [ ] **Step 3: Commit the refactor**

```bash
git add backend/wealthview-projection/src/test/java/com/wealthview/projection/testutil/GoldenScenarios.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/ProjectionGoldenFileTest.java
git commit -m "$(cat <<'EOF'
test(projection): extract golden scenario loader into GoldenScenarios

Moves golden input parsing and the 2025 calculator stub wiring out of
ProjectionGoldenFileTest into a shared test fixture so the upcoming
TaxSpaceReconciliationTest can run the same six scenarios. No behavior
change: all six goldens still match byte-for-byte.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Write the failing test for `socialSecurityBenefit`**

Append to `IncomeSourceProcessorTest` and add `import static org.mockito.ArgumentMatchers.anyInt;`:

```java
    // --- Phase 1a: gross combined Social Security benefit for the tax-space picture ---

    @Test
    void process_socialSecuritySource_reportsGrossCombinedBenefit() {
        var ss = makeSource(IncomeSourceType.SOCIAL_SECURITY, new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, "taxable");
        when(ssTaxCalculator.computeTaxableAmount(any(), any(), any(), anyInt(), any()))
                .thenReturn(new BigDecimal("5000"));

        var result = processor.process(
                List.of(ss), 70, 1, 2030,
                BigDecimal.ZERO, FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO, 0);

        assertThat(result.socialSecurityBenefit()).isEqualByComparingTo("30000");
        assertThat(result.socialSecurityTaxable()).isEqualByComparingTo("5000");
    }

    @Test
    void process_noSocialSecuritySource_reportsZeroBenefit() {
        var pension = makeSource(IncomeSourceType.PENSION, new BigDecimal("20000"), 60, null,
                BigDecimal.ZERO, "taxable");

        var result = processor.process(
                List.of(pension), 70, 1, 2030,
                BigDecimal.ZERO, FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO, 0);

        assertThat(result.socialSecurityBenefit()).isEqualByComparingTo(BigDecimal.ZERO);
    }
```

> If `IncomeSourceType.PENSION` is not the enum constant's name, use the constant `makeSource` callers already use for a pension (`grep -n "IncomeSourceType\." IncomeSourceProcessorTest.java`). If `SocialSecurityTaxCalculator.computeTaxableAmount`'s parameter list changed in Task 6, match the matcher count to it.

- [ ] **Step 5: Run it to verify it fails**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=IncomeSourceProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE with `cannot find symbol: method socialSecurityBenefit()`.

- [ ] **Step 6: Add the record component**

In `IncomeSourceProcessor.java`, append the component to the record:

```java
    record IncomeSourceYearResult(
            BigDecimal totalCashInflow,
            BigDecimal totalTaxableIncome,
            BigDecimal rentalIncomeGross,
            BigDecimal rentalExpensesTotal,
            BigDecimal depreciationTotal,
            BigDecimal rentalLossApplied,
            BigDecimal suspendedLossCarryforward,
            BigDecimal socialSecurityTaxable,
            BigDecimal selfEmploymentTax,
            Map<String, BigDecimal> incomeBySource,
            List<RentalPropertyYearDetail> rentalPropertyDetails,
            BigDecimal netRentalTaxableIncome,
            BigDecimal socialSecurityBenefit
    ) {}
```

Update the empty-sources return (~:132):

```java
            return new IncomeSourceYearResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, priorSuspendedLoss, BigDecimal.ZERO, BigDecimal.ZERO,
                    Map.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
```

Update the final return (~:230). `ssBenefit` is the aggregated local accumulated in the first source loop. After Task 8's reorder, confirm the local is still named `ssBenefit` and still holds the summed gross SS amount:

```java
        return new IncomeSourceYearResult(
                totalCashInflow, totalTaxableIncome,
                rentalIncomeGross, rentalExpensesTotal, depreciationTotal,
                rentalLossApplied, suspendedLoss, combinedSsTaxable, seTax,
                Map.copyOf(incomeBySource), List.copyOf(rentalDetails), netRentalTaxableIncome,
                ssBenefit);
```

Then fix any other construction site: `grep -rn "new IncomeSourceProcessor.IncomeSourceYearResult(\|new IncomeSourceYearResult(" backend --include=*.java` (expected: only the two above).

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='IncomeSourceProcessorTest,ProjectionGoldenFileTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. The goldens are unchanged, because the new component is not serialized on `ProjectionYearDto`.

- [ ] **Step 8: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/IncomeSourceProcessor.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/IncomeSourceProcessorTest.java
git commit -m "$(cat <<'EOF'
feat(projection): expose gross Social Security benefit on income-source result

IncomeSourceYearResult now carries the combined gross SS benefit alongside
the taxable portion, so the engine can build each year's YearTaxPicture
(Phase 1a tax-space calculator needs the benefit to price the SS torpedo).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 9: Write the failing test for the LTCG stacking floor**

**Why:** this is a defect found while planning the reconciliation. `PoolStrategy.MultiPool.resolveOrdinaryDeduction` nets the LTCG stacking floor with the age-UNAWARE 2-arg `loadStandardDeduction`. The ordinary tax, by contrast, uses the age-aware deduction (`CombinedTaxCalculator.resolveFederalStandardDeduction` / `FederalOnlyTaxStrategy`). For a 65+ filer this puts $2,000 single / $1,600-per-spouse MFJ (2025) too much ordinary income under the LTCG band, which overstates LTCG tax.

The golden fixtures seed `additional_age65 = 0`, so the reconciliation test alone would never catch this. Pin it here.

Task 6 already added `TaxCalculationStrategy.standardDeduction` with unit tests for both concrete strategies. This step only pins the engine's use of it.

Append to `MultiPoolCapitalGainsTest` (add imports `com.wealthview.core.testutil.TaxBracketFixtures`, `com.wealthview.persistence.entity.StandardDeductionEntity`, and `static org.mockito.ArgumentMatchers.anyInt` / `eq` and `static org.mockito.Mockito.when` as needed):

```java
    /**
     * Phase 1a: the LTCG stacking floor must net the SAME age-aware standard deduction the ordinary
     * tax uses. Same $40,000-gain / $60,000-ordinary fixture as the first test in this class, but a
     * 66-year-old filer whose 2025 deduction is 15,000 + 2,000 (age-65 adder) = 17,000:
     * floor = 60,000 - 17,000 = 43,000 → 0% room = 48,350 - 43,000 = 5,350 →
     * LTCG tax = (40,000 - 5,350) × 0.15 = 5,197.50. The age-unaware floor (15,000) gives 5,497.50.
     */
    @Test
    void executeWithdrawals_filerOver65_stackingFloorNetsAgeAwareStandardDeduction() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15000"), bd("2000"))));
        var federal = new FederalTaxCalculator(taxBracketRepo, deductionRepo);
        var combinedAge66 = new CombinedTaxCalculator(federal, new NullStateTaxCalculator(),
                ZERO, ZERO, 1959);
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST,
                combinedAge66, null, Map.of(), ZERO, capitalGainsCalc(), ZERO, ZERO, ZERO, BASE_YEAR,
                federal);
        var p = new PoolStrategy.MultiPool(
                PoolFixtures.grouped(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth")),
                ZERO, config);

        var r = p.executeWithdrawals(bd("100000"), YEAR, bd("60000"), ZERO, ZERO, 66);

        assertThat(r.ltcgTax()).isEqualByComparingTo(bd("5197.50"));
    }
```

> The `PoolStrategy.PoolConfig` 17-arg constructor call is copied verbatim from `executeWithdrawals_itemizingYear_stackingFloorNetsItemizedDeductionNotStandard`. If Task 3 added an `earlyAccessAge` parameter to that constructor, add `60` in the same position that test uses after Task 3.

- [ ] **Step 10: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='MultiPoolCapitalGainsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL in `executeWithdrawals_filerOver65_stackingFloorNetsAgeAwareStandardDeduction`: expected 5197.50 but was 5497.50.

- [ ] **Step 11: Use the strategy's age-aware standard deduction for the stacking floor**

`PoolStrategy.java`: replace `resolveOrdinaryDeduction` (~:1500-1508). The `TaxCalculationStrategy` field on `MultiPool` is named `taxCalculator` (~:871). Confirm with `grep -n "private final TaxCalculationStrategy" PoolStrategy.java`.

```java
        private BigDecimal resolveOrdinaryDeduction(CombinedTaxResult ordinaryTaxDetail, int year) {
            if (ordinaryTaxDetail != null && ordinaryTaxDetail.usedItemized()) {
                return ordinaryTaxDetail.itemizedDeductions();
            }
            // Phase 1a: prefer the strategy's AGE-AWARE standard deduction -- the same one the
            // ordinary tax was computed with -- so a 65+ filer's adder is not treated as ordinary
            // income sitting under the LTCG band.
            Optional<BigDecimal> ageAware = taxCalculator != null
                    ? taxCalculator.standardDeduction(year, filingStatus) : Optional.empty();
            return ageAware.orElseGet(() -> federalTaxCalculator != null
                    ? federalTaxCalculator.loadStandardDeduction(year, filingStatus)
                    : BigDecimal.ZERO);
        }
```

Add `import java.util.Optional;` to `PoolStrategy.java` if absent. Update the method's Javadoc sentence "otherwise falls back to the federal standard deduction" to: "otherwise the tax strategy's age-aware standard deduction, falling back to the age-unaware federal figure".

- [ ] **Step 12: Run tests to verify they pass, goldens included**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='MultiPoolCapitalGainsTest,ProjectionGoldenFileTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. The goldens stay unchanged because the 2025 fixtures seed `additional_age65 = 0`.
- If a golden fails: inspect the diff (`mvn ... -Dupdate.golden=true` then `git diff`). Accept it only if every changed year is a 65+ year with LTCG income and LTCG tax went DOWN.
- Record the before/after headline figures in the commit body.

- [ ] **Step 13: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/MultiPoolCapitalGainsTest.java
git commit -m "$(cat <<'EOF'
fix(projection): net LTCG stacking floor with the age-aware standard deduction

MultiPool.resolveOrdinaryDeduction used the age-unaware 2-arg standard
deduction while the ordinary tax used the age-aware one, so for a 65+
filer the age-65 adder was treated as ordinary income under the LTCG
band, overstating LTCG tax (e.g. $5,497.50 vs the correct $5,197.50 on a
$40k gain at $60k ordinary income, single 2025). The stacking floor now
uses TaxCalculationStrategy.standardDeduction(year, status), the
age-aware figure the ordinary tax already uses. Found while planning the Phase 1a tax-space reconciliation.
Goldens unchanged (fixtures seed additional_age65 = 0).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 14: Write the failing engine tests for pictures and tax space**

Create `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTaxPictureTest.java`:

```java
package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.core.projection.tax.TaxSpaceCalculator;
import com.wealthview.projection.testutil.GoldenScenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineTaxPictureTest {

    private static DeterministicProjectionEngine engine(TaxSpaceCalculator taxSpaceCalculator) {
        var calcs = GoldenScenarios.calculators();
        return new DeterministicProjectionEngine(calcs.federal(), null, calcs.capitalGains(), calcs.irmaa(),
                null, null, taxSpaceCalculator);
    }

    private static TaxSpaceYear stubYear(int year) {
        return new TaxSpaceYear(year, 70, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, BigDecimal.ZERO,
                null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void runDetailed_ssRmdRetiree_emitsOnePicturePerRetiredYearInOrder() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");

        var detail = engine(null).runDetailed(input);

        var retiredYears = detail.result().yearlyData().stream()
                .filter(y -> y.retired()).map(y -> y.year()).toList();
        assertThat(detail.taxPictures()).extracting(YearTaxPicture::year).containsExactlyElementsOf(retiredYears);
    }

    @Test
    void runDetailed_ssRmdRetiree_pictureCarriesGrossBenefitAndChargedTaxSplit() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");

        var detail = engine(null).runDetailed(input);

        var byYear = detail.result().yearlyData().stream()
                .collect(java.util.stream.Collectors.toMap(y -> y.year(), y -> y));
        assertThat(detail.taxPictures()).allSatisfy(p -> {
            var dto = byYear.get(p.year());
            BigDecimal penalty = dto.earlyWithdrawalPenalty() != null ? dto.earlyWithdrawalPenalty() : BigDecimal.ZERO;
            BigDecimal seTax = dto.selfEmploymentTax() != null ? dto.selfEmploymentTax() : BigDecimal.ZERO;
            BigDecimal taxLiability = dto.taxLiability() != null ? dto.taxLiability() : BigDecimal.ZERO;
            assertThat(p.chargedOrdinaryAndStateTax().add(p.chargedLtcgTax()).add(penalty).add(seTax))
                    .as("charged split sums to taxLiability in %d", p.year())
                    .isEqualByComparingTo(taxLiability);
            assertThat(p.socialSecurityBenefit()).isGreaterThanOrEqualTo(p.socialSecurityTaxable());
            assertThat(p.yearsFromBase()).isGreaterThanOrEqualTo(0);
        });
        assertThat(detail.taxPictures()).anySatisfy(p ->
                assertThat(p.socialSecurityBenefit()).isPositive());
    }

    @Test
    void runDetailed_noTaxSpaceCalculator_taxSpaceEmpty() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");

        var detail = engine(null).runDetailed(input);

        assertThat(detail.taxSpace()).isEmpty();
    }

    @Test
    void runDetailed_withTaxSpaceCalculator_computesOneTaxSpaceYearPerPicture() throws Exception {
        var input = GoldenScenarios.loadInput("ss-rmd-retiree");
        var calculator = mock(TaxSpaceCalculator.class);
        when(calculator.compute(any(YearTaxPicture.class), any(TaxCalculationStrategy.class)))
                .thenAnswer(inv -> stubYear(inv.<YearTaxPicture>getArgument(0).year()));

        var detail = engine(calculator).runDetailed(input);

        assertThat(detail.taxSpace()).extracting(TaxSpaceYear::year)
                .containsExactlyElementsOf(detail.taxPictures().stream().map(YearTaxPicture::year).toList());
        verify(calculator, times(detail.taxPictures().size())).compute(any(), any());
    }

    @Test
    void run_matchesRunDetailedResult() throws Exception {
        var input = GoldenScenarios.loadInput("household-survivor");
        var eng = engine(null);

        var viaRun = eng.run(input);
        var viaDetailed = eng.runDetailed(input).result();

        assertThat(GoldenScenarios.MAPPER.writeValueAsString(viaRun))
                .isEqualTo(GoldenScenarios.MAPPER.writeValueAsString(viaDetailed));
    }
}
```

> The accessor names `retired()`, `year()`, `earlyWithdrawalPenalty()`, `selfEmploymentTax()` and `taxLiability()` are the flat accessors on `ProjectionYearDto` (spec §7 of the code map: "a flat accessor" per field). Verify with `grep -n "public BigDecimal earlyWithdrawalPenalty\|public BigDecimal selfEmploymentTax\|public BigDecimal taxLiability" backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionYearDto.java` and adapt the calls if a group accessor is required, e.g. `dto.taxBreakdown().earlyWithdrawalPenalty()`.

- [ ] **Step 15: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=DeterministicProjectionEngineTaxPictureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE, because there is no 7-arg `DeterministicProjectionEngine` constructor.

- [ ] **Step 16: Implement picture emission and tax space in the engine**

In `DeterministicProjectionEngine.java`:

(a) Imports: add `com.wealthview.core.projection.dto.TaxSpaceYear`, `com.wealthview.core.projection.dto.YearTaxPicture` and `com.wealthview.core.projection.tax.TaxSpaceCalculator`.

(b) Constant and field:

```java
    /** IRMAA statutory MAGI lookback: year Y's MAGI sets premiums in Y+2 (spec §1.2 IRMAA group). */
    private static final int IRMAA_LOOKBACK_YEARS = 2;
```

```java
    /** Phase 1a: null omits tax-space computation (taxSpace stays empty; pictures still emitted). */
    @Nullable
    private final TaxSpaceCalculator taxSpaceCalculator;
```

(c) Constructors. Remove `@Autowired` from the existing 6-arg constructor and make it delegate. Add the new 7-arg constructor, which takes over the existing 6-arg body plus the new field:

```java
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable IrmaaSurchargeCalculator irmaaSurchargeCalculator,
                                          @Nullable MeterRegistry meterRegistry,
                                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions) {
        this(taxCalculator, stateTaxCalculatorFactory, capitalGainsTaxCalculator, irmaaSurchargeCalculator,
                meterRegistry, capitalMarketAssumptions, null);
    }

    @Autowired
    public DeterministicProjectionEngine(@Nullable FederalTaxCalculator taxCalculator,
                                          @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory,
                                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                          @Nullable IrmaaSurchargeCalculator irmaaSurchargeCalculator,
                                          @Nullable MeterRegistry meterRegistry,
                                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                          @Nullable TaxSpaceCalculator taxSpaceCalculator) {
        // (existing 6-arg body moved here verbatim)
        this.taxSpaceCalculator = taxSpaceCalculator;
    }
```

`TaxSpaceCalculator` is a core `@Component` (Task 7), so Spring injects it automatically. No app-module config change is needed.

(d) `YearStepResult` gains the optional picture:

```java
    private record YearStepResult(ProjectionYearDto yearDto, YearAccumulator nextAccumulator,
                                  @Nullable YearTaxPicture taxPicture) {}
```

(e) At the end of `processYear`, replace the final `return new YearStepResult(...)` with:

```java
        YearTaxPicture taxPicture = retired
                ? buildTaxPicture(ctx, year, age, comp, magiThisYear, selfEmploymentTax)
                : null;

        return new YearStepResult(yearDto, new YearAccumulator(yearsInRetirement, previousWithdrawal, suspendedLoss,
                magiThisYear, acc.magiYearMinus1()), taxPicture);
```

Add the builder below `processYear`. It reuses the values the engine already resolved, so nothing is recomputed:

```java
    /**
     * Phase 1a (spec §1.1): the year's realized income/tax figures, captured for the tax-space
     * calculator and its reconciliation guard. {@code chargedOrdinaryAndStateTax} is the ordinary +
     * state slice of taxLiability -- the same split RetirementTaxAnnotator reconciles (LTCG, SE tax
     * and the early-withdrawal penalty are the three additive federal components outside it).
     * Ages follow the tax strategies' convention: the filer's age (primary while alive, else the
     * survivor's) plus the spouse's only while both are alive AND filing jointly.
     */
    private static YearTaxPicture buildTaxPicture(ProjectionRunContext ctx, int year, int age,
                                                  YearFinanceResolver.YearComputation comp,
                                                  BigDecimal magi, BigDecimal selfEmploymentTax) {
        var household = ctx.input().household();
        var status = ctx.pool().getFilingStatus();
        int filerAge = household != null ? household.filerAgeIn(year) : age;
        Integer spouseAge = household != null && status == FilingStatus.MARRIED_FILING_JOINTLY
                ? household.secondFilerAgeIn(year) : null;
        int premiumYear = year + IRMAA_LOOKBACK_YEARS;
        int medicareCountInPremiumYear = household != null
                ? household.age65QualifyingCount(premiumYear)
                : premiumYear - ctx.birthYear() >= MEDICARE_AGE ? 1 : 0;
        var isResult = comp.isResult();
        BigDecimal ssBenefit = isResult != null ? isResult.socialSecurityBenefit() : BigDecimal.ZERO;
        BigDecimal netRental = isResult != null ? isResult.netRentalTaxableIncome() : BigDecimal.ZERO;
        BigDecimal ssTaxable = comp.socialSecurityTaxable();
        BigDecimal chargedOrdinaryAndState = comp.taxLiability().subtract(comp.ltcgTax())
                .subtract(selfEmploymentTax).subtract(comp.earlyWithdrawalPenalty());
        return new YearTaxPicture(year, status, filerAge, spouseAge,
                comp.effectiveOtherIncome().subtract(ssTaxable), ssBenefit, ssTaxable,
                comp.wdFromTraditional(), comp.conversionAmount(), comp.ordinaryInterestIncome(),
                comp.realizedLtcgIncome(), magi, netRental, chargedOrdinaryAndState, comp.ltcgTax(),
                Math.max(0, year - ctx.currentYear()), ctx.inflationRate(), medicareCountInPremiumYear);
    }
```

(f) In `runProjection` (Task 5 left it returning `ProjectionRunDetail`):
- Declare `var taxPictures = new ArrayList<YearTaxPicture>();` before the year loop.
- Inside the loop, right after `yearlyData.add(step.yearDto());`, add:

```java
            if (step.taxPicture() != null) {
                taxPictures.add(step.taxPicture());
            }
```

In the `new ProjectionRunDetail(...)` construction:
- Replace the `List.of()` passed for `taxPictures` with `List.copyOf(taxPictures)`.
- Replace the `List.of()` passed for `taxSpace` with `computeTaxSpace(taxPictures, ctx.taxStrategy())`.

Add the helper:

```java
    private List<TaxSpaceYear> computeTaxSpace(List<YearTaxPicture> pictures,
                                               @Nullable TaxCalculationStrategy taxStrategy) {
        if (taxSpaceCalculator == null || taxStrategy == null) {
            return List.of();
        }
        return pictures.stream().map(p -> taxSpaceCalculator.compute(p, taxStrategy)).toList();
    }
```

> `ctx.pool().getFilingStatus()` reflects the post-first-death SINGLE flip for survivor years, because `HouseholdTransition.resolveYear` already ran earlier in `processYear`.

- [ ] **Step 17: Run the engine tests and goldens**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='DeterministicProjectionEngineTaxPictureTest,ProjectionGoldenFileTest,DeterministicProjectionEngine*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `run()` output is unchanged, so the goldens stay byte-identical.

- [ ] **Step 18: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTaxPictureTest.java
git commit -m "$(cat <<'EOF'
feat(projection): emit per-year tax pictures and tax space from runDetailed

Each retired year now records a YearTaxPicture (income by kind, gross and
taxable SS, MAGI, filer ages, charged ordinary+state vs LTCG tax) and,
when the TaxSpaceCalculator bean is present, a TaxSpaceYear computed with
the run's own TaxCalculationStrategy. Returned only via runDetailed(), so
run() and the byte-pinned goldens are unchanged. The engine gains a
7-arg @Autowired constructor; the 6-arg one delegates with null.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 19: Write the reconciliation test**

Create `backend/wealthview-projection/src/test/java/com/wealthview/projection/TaxSpaceReconciliationTest.java`:

```java
package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.core.projection.tax.TaxSpaceCalculator;
import com.wealthview.projection.testutil.GoldenScenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 1a guard (spec §1.3): for every retired year of every golden scenario, the tax-space
 * model's inputs ({@link YearTaxPicture}) must reproduce the tax the engine actually charged,
 * within $1. Listed exclusions: the early-withdrawal penalty and SE tax (both outside the
 * tax-space model; already removed from chargedOrdinaryAndStateTax by the engine). A failure here
 * is an engine/calculator disagreement to FIX, never a tolerance to widen.
 */
class TaxSpaceReconciliationTest {

    private static final BigDecimal ONE_DOLLAR = BigDecimal.ONE;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    static Stream<String> scenarios() {
        return GoldenScenarios.NAMES.stream();
    }

    private record Fixture(GoldenScenarios.Calculators calcs, TaxCalculationStrategy strategy,
                           Iterable<YearTaxPicture> pictures, int retiredYears) {
    }

    private static Fixture run(String scenario) throws Exception {
        var calcs = GoldenScenarios.calculators();
        var input = GoldenScenarios.loadInput(scenario);
        var params = ScenarioParams.parseOrEmpty(GoldenScenarios.MAPPER, input.paramsJson());
        var strategy = new TaxStrategyFactory(calcs.federal(), null).buildTaxStrategy(params, input.household());
        var engine = new DeterministicProjectionEngine(calcs.federal(), null, calcs.capitalGains(), calcs.irmaa());
        var detail = engine.runDetailed(input);
        int retiredYears = (int) detail.result().yearlyData().stream().filter(y -> y.retired()).count();
        return new Fixture(calcs, strategy, detail.taxPictures(), retiredYears);
    }

    private static BigDecimal grossOrdinary(YearTaxPicture p) {
        return p.ordinaryIncomeExSocialSecurity().add(p.socialSecurityTaxable())
                .add(p.traditionalDistributions()).add(p.rothConversion()).add(p.ordinaryInterest());
    }

    @ParameterizedTest(name = "picture per retired year: {0}")
    @MethodSource("scenarios")
    void runDetailed_everyRetiredYear_hasExactlyOnePicture(String scenario) throws Exception {
        var f = run(scenario);

        assertThat(f.pictures()).hasSize(f.retiredYears());
    }

    @ParameterizedTest(name = "ordinary+state reconciles: {0}")
    @MethodSource("scenarios")
    void picture_recomputedOrdinaryAndStateTax_matchesChargedWithinOneDollar(String scenario) throws Exception {
        var f = run(scenario);

        for (var p : f.pictures()) {
            var detailTax = f.strategy().computeDetailedTax(grossOrdinary(p), p.year(), p.filingStatus(),
                    p.qualifiedDividendsAndLtcg(), p.socialSecurityTaxable());

            assertThat(detailTax.totalTax()).as("%s %d ordinary+state", scenario, p.year())
                    .isCloseTo(p.chargedOrdinaryAndStateTax(), within(ONE_DOLLAR));
        }
    }

    @ParameterizedTest(name = "LTCG reconciles: {0}")
    @MethodSource("scenarios")
    void picture_recomputedLtcgTax_matchesChargedWithinOneDollar(String scenario) throws Exception {
        var f = run(scenario);

        for (var p : f.pictures()) {
            BigDecimal ltcg = p.qualifiedDividendsAndLtcg();
            BigDecimal expected = BigDecimal.ZERO;
            if (ltcg.signum() > 0) {
                BigDecimal gross = grossOrdinary(p);
                var detailTax = f.strategy().computeDetailedTax(gross, p.year(), p.filingStatus(),
                        ltcg, p.socialSecurityTaxable());
                BigDecimal deduction = detailTax.usedItemized()
                        ? detailTax.itemizedDeductions()
                        : f.strategy().standardDeduction(p.year(), p.filingStatus()).orElseThrow();
                BigDecimal ordinaryForLtcg = gross.subtract(deduction).max(BigDecimal.ZERO);
                expected = f.calcs().capitalGains().computeLtcgTax(ordinaryForLtcg, ltcg, p.year(),
                        p.filingStatus(), p.yearsFromBase(), p.inflationRate(), gross.add(ltcg),
                        p.netRentalIncome());
            }

            assertThat(expected).as("%s %d LTCG", scenario, p.year())
                    .isCloseTo(p.chargedLtcgTax(), within(ONE_DOLLAR));
        }
    }

    @ParameterizedTest(name = "SS taxable reconciles: {0}")
    @MethodSource("scenarios")
    void picture_socialSecurityTaxable_matchesIrsProvisionalIncomeWithinOneDollar(String scenario) throws Exception {
        var f = run(scenario);
        var taxSpaceCalculator = new TaxSpaceCalculator(
                f.calcs().federal(), f.calcs().capitalGains(), f.calcs().irmaa());

        for (var p : f.pictures()) {
            if (p.socialSecurityBenefit().signum() <= 0) {
                continue;
            }
            BigDecimal provisionalOther = p.ordinaryIncomeExSocialSecurity().add(p.traditionalDistributions())
                    .add(p.rothConversion()).add(p.ordinaryInterest()).add(p.qualifiedDividendsAndLtcg());

            BigDecimal expectedSsTaxable = new SocialSecurityTaxCalculator().computeTaxableAmount(
                    p.socialSecurityBenefit(), provisionalOther, p.filingStatus().value(),
                    p.yearsFromBase(), p.inflationRate());
            var space = taxSpaceCalculator.compute(p, f.strategy());

            assertThat(expectedSsTaxable).as("%s %d SS taxable", scenario, p.year())
                    .isCloseTo(p.socialSecurityTaxable(), within(ONE_DOLLAR));
            assertThat(space.provisionalIncome()).as("%s %d provisional income", scenario, p.year())
                    .isCloseTo(provisionalOther.add(p.socialSecurityBenefit().multiply(HALF)), within(ONE_DOLLAR));
        }
    }
}
```

Notes for the executor:
- `CapitalGainsTaxCalculator.computeLtcgTax` 8-arg order is `(ordinaryTaxableIncome, ltcgIncome, year, status, yearsFromBase, inflationRate, magi, netRentalIncome)`. That is the rental overload the engine calls at `PoolStrategy` ~:1488. Re-check the order after Task 6 / Task 15.
- `TaxSpaceYear.provisionalIncome()` is asserted as IRS provisional income: other income + 50% of benefits. If Task 7 implemented it WITHOUT the half benefit, fix the calculator, not this assertion; the TaxSpaceTab labels it "provisional income".
- `SocialSecurityTaxCalculator.computeTaxableAmount` takes the filing status as a String (`filingStatus.value()`, the same call `IncomeSourceProcessor` makes). If Task 6 changed that, match it.

- [ ] **Step 20: Run the reconciliation test**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=TaxSpaceReconciliationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS for all 4 tests × 6 scenarios.

If it fails, apply superpowers:systematic-debugging, then use the triage below. Each fix is its own `fix(projection)` commit with a regression test; regenerate goldens only with a reviewed diff whose deltas are documented in the commit body. Never widen `ONE_DOLLAR` and never skip a scenario.

- **LTCG mismatch only in years where `tax_paid_from_traditional > 0`** (the C2 gross-up). The engine stacks LTCG on the ordinary bundle BEFORE adding the gross-up slice. Fix in `PoolStrategy.executeWithdrawals`: immediately after the `var grossUp = growTraditionalGrossUp(...)` statement, replace `BigDecimal totalWithdrawalTax = grossUp.tax();` with:

  ```java
            // Phase 1a reconciliation: LTCG stacks on the FULL year's ordinary income, including the
            // C2 gross-up slice. Re-price once on the final base; the (non-negative) delta joins the
            // pool-funded tax. Its own funding draw is not re-stacked -- the same documented
            // second-order category as the early-withdrawal penalty's funding.
            BigDecimal ltcgRestackDelta = BigDecimal.ZERO;
            if (grossUp.traditionalGrossUp().signum() > 0 && realizedLtcgIncome.signum() > 0) {
                BigDecimal restackedLtcgTax = computeLtcgTax(realizedGain,
                        taxableIncome.add(grossUp.traditionalGrossUp()), year, detailed, netRentalIncome);
                ltcgRestackDelta = restackedLtcgTax.subtract(ltcgTax).max(BigDecimal.ZERO);
                ltcgTax = ltcgTax.add(ltcgRestackDelta);
            }
            BigDecimal totalWithdrawalTax = grossUp.tax().add(ltcgRestackDelta);
  ```

  Pin it in `MultiPoolCapitalGainsTest` before changing the code. Use a taxable pool too small to pay the year's tax, so part of it is grossed up from traditional, plus an embedded gain. Assert `r.ltcgTax()` equals the hand-computed LTCG on `ordinary + grossUp`.
- **Ordinary+state mismatch.** Compare against `RetirementTaxAnnotator.annotate`'s `totalTaxableIncome`, which this test mirrors. Any difference means the picture took a field from the wrong place. Fix `buildTaxPicture`, not the test.
- **SS taxable mismatch.** The engine's provisional-income composition still differs from IRS (Task 8 should have closed the rental gross-rent case). Fix `IncomeSourceProcessor`.

- [ ] **Step 21: Run the module gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS: PMD, CPD, SpotBugs, Checkstyle and JaCoCo (projection 90% line) all green.

- [ ] **Step 22: Commit**

```bash
git add backend/wealthview-projection/src/test/java/com/wealthview/projection/TaxSpaceReconciliationTest.java
git commit -m "$(cat <<'EOF'
test(projection): reconcile tax-space pictures against charged tax for all goldens

For every retired year of all six golden scenarios, recomputing ordinary
+state tax, LTCG tax (age-aware stacking floor + NIIT) and the taxable
Social Security share from the YearTaxPicture must match what the engine
charged within $1 (spec 1a §1.3). Penalty and SE tax are the listed
exclusions. Also pins TaxSpaceYear.provisionalIncome to the IRS
definition (other income + half of benefits).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

(If Step 20 required an engine fix, commit that fix first as its own `fix(projection): ...` commit, then this one.)

---

### Task 10: Run-response plumbing for `tax_space` and `terminal_value`

Spec refs: §1.4, §3 (output side). `/run` exposes the engine's `taxSpace` and `terminalValue`; `/compare` stays unchanged.

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionRunResult.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionService.java:57-64`
- Modify: `backend/wealthview-api/src/main/java/com/wealthview/api/dto/ProjectionRunResponse.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionServiceTest.java`
- Test: `backend/wealthview-api/src/test/java/com/wealthview/api/controller/ProjectionControllerTest.java`

**Interfaces:**
- Consumes:
  - `ProjectionEngine.runDetailed(ProjectionInput)` → `ProjectionRunDetail(result, taxPictures, taxSpace, terminalValue)` (Tasks 5 and 9).
  - `TaxSpaceYear` (Task 7).
  - `TerminalValue` and `TerminalValue.compute(...)` (Task 5).
- Produces:
  - `ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols, List<String> warnings, List<TaxSpaceYear> taxSpace, @Nullable TerminalValue terminalValue)`, with 2-arg and 3-arg back-compat constructors kept.
  - `ProjectionRunResponse` gains `taxSpace` (wire `tax_space`, omitted when empty) and `terminalValue` (wire `terminal_value`, `null` when absent).

- [ ] **Step 1: Write the failing service test and migrate the existing `runProjection_*` stubs to `runDetailed`**

In `ProjectionServiceTest`, add imports `com.wealthview.core.projection.dto.ProjectionRunDetail`, `com.wealthview.core.projection.dto.TaxSpaceYear` and `com.wealthview.core.projection.dto.TerminalValue`. Then add the helper and the new test:

```java
    private static ProjectionRunDetail detailOf(ProjectionResultResponse result) {
        return new ProjectionRunDetail(result, List.of(), List.of(), null);
    }

    @Test
    void runProjection_engineDetail_surfacesTaxSpaceAndTerminalValue() {
        var scenario = ScenarioMother.scenario(tenant);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        var input = new ProjectionInput(scenarioId, "Plan", LocalDate.of(2055, 1, 1),
                90, new BigDecimal("0.03"), null, List.of(), null, null, List.of());
        when(projectionInputBuilder.buildWithMetadata(scenario, tenantId))
                .thenReturn(new ProjectionInputResult(input, List.of()));
        var engineResult = new ProjectionResultResponse(scenarioId, List.of(), BigDecimal.ZERO, 0, null);
        var taxSpaceYear = new TaxSpaceYear(2055, 62, new BigDecimal("70000"), new BigDecimal("0.12"),
                List.of(), new BigDecimal("26700"), new BigDecimal("500000"), null, null, null, null,
                new BigDecimal("180000"), null, null, null, null, new BigDecimal("0.12"), BigDecimal.ZERO);
        var terminal = TerminalValue.compute(2080, new BigDecimal("400000"), new BigDecimal("300000"),
                new BigDecimal("200000"), new BigDecimal("0.24"), false);
        when(projectionEngine.runDetailed(input))
                .thenReturn(new ProjectionRunDetail(engineResult, List.of(), List.of(taxSpaceYear), terminal));

        var result = service.runProjection(tenantId, scenarioId);

        assertThat(result.result()).isEqualTo(engineResult);
        assertThat(result.taxSpace()).containsExactly(taxSpaceYear);
        assertThat(result.terminalValue()).isEqualTo(terminal);
    }
```

Point every existing `runProjection_*` test at `runDetailed`. The compare tests keep `run`. Run from the repo root:

```bash
sed -i '/void runProjection_/,/^    }/ {
  s/when(projectionEngine.run(input)).thenReturn(engineResult);/when(projectionEngine.runDetailed(input)).thenReturn(detailOf(engineResult));/
  s/verify(projectionEngine).run(input);/verify(projectionEngine).runDetailed(input);/
}' backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionServiceTest.java
grep -n "projectionEngine.run(input)" backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionServiceTest.java
```

Expected `grep` output: nothing. Only `compareScenarios_*` uses `run(input1)` / `run(input2)`.

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=ProjectionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE: `cannot find symbol: method taxSpace()` on `ProjectionRunResult`.

- [ ] **Step 3: Implement `ProjectionRunResult` and the service**

Replace `ProjectionRunResult.java`:

```java
package com.wealthview.core.projection.dto;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * The deterministic engine's projection result for a single scenario run, plus the distinct
 * union of linked-account holding symbols that fell back to a default classification for lack of
 * a tenant override or seed entry (see {@link ProjectionInputResult}), plus any non-fatal
 * run-level warnings (e.g. an unsupported filing state, audit C3), plus (Phase 1a) the per-year
 * tax space and the after-tax legacy value. Kept separate from {@link ProjectionResultResponse}
 * — which the projection golden files pin byte-for-byte — since none of these change the
 * projection math.
 */
public record ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols,
                                   List<String> warnings, List<TaxSpaceYear> taxSpace,
                                   @Nullable TerminalValue terminalValue) {

    /** Back-compat for callers that predate run-level warnings (audit C3): defaults to none. */
    public ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols) {
        this(result, unclassifiedSymbols, List.of(), List.of(), null);
    }

    /** Back-compat for callers that predate Phase 1a tax space / legacy value: defaults to none. */
    public ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols,
                               List<String> warnings) {
        this(result, unclassifiedSymbols, warnings, List.of(), null);
    }
}
```

In `ProjectionService.runProjection`, replace the two lines after `buildWithMetadata`:

```java
        var detail = projectionEngine.runDetailed(inputResult.input());
        return new ProjectionRunResult(detail.result(), inputResult.unclassifiedSymbols(),
                resolveWarnings(scenario), detail.taxSpace(), detail.terminalValue());
```

`compareScenarios` keeps calling `projectionEngine.run(...)`.

- [ ] **Step 4: Run to verify the service tests pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=ProjectionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all existing tests plus the new one).

- [ ] **Step 5: Write the failing controller tests**

Append to `ProjectionControllerTest`. Add imports `com.wealthview.core.projection.dto.TaxSpaceYear`, `com.wealthview.core.projection.dto.TerminalValue`, and `java.math.BigDecimal` if absent:

```java
    @Test
    void run_withTaxSpaceAndTerminalValue_serializesSnakeCaseFields() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        var bracket = new TaxSpaceYear.BracketRoom(
                new BigDecimal("0.12"), new BigDecimal("126700"), new BigDecimal("56700"));
        var taxSpaceYear = new TaxSpaceYear(2031, 62, new BigDecimal("70000"), new BigDecimal("0.12"),
                List.of(bracket), new BigDecimal("26700"), new BigDecimal("500000"),
                null, null, null, null, new BigDecimal("180000"),
                null, null, null, null, new BigDecimal("0.12"), BigDecimal.ZERO);
        var terminal = TerminalValue.compute(2060, new BigDecimal("400000"), new BigDecimal("300000"),
                new BigDecimal("200000"), new BigDecimal("0.24"), false);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of(), List.of(), List.of(taxSpaceYear), terminal));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax_space.length()").value(1))
                .andExpect(jsonPath("$.tax_space[0].year").value(2031))
                .andExpect(jsonPath("$.tax_space[0].marginal_ordinary_rate").value(0.12))
                .andExpect(jsonPath("$.tax_space[0].bracket_room[0].gross_ceiling").value(126700))
                .andExpect(jsonPath("$.tax_space[0].ltcg_zero_room").value(26700))
                .andExpect(jsonPath("$.tax_space[0].effective_marginal_ordinary").value(0.12))
                .andExpect(jsonPath("$.terminal_value.heir_tax_rate").value(0.24))
                .andExpect(jsonPath("$.terminal_value.after_tax_legacy").value(804000))
                .andExpect(jsonPath("$.terminal_value.at_second_death").value(false));
    }

    @Test
    void run_emptyTaxSpaceAndNoTerminalValue_omitsTaxSpaceAndNullsTerminalValue() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of()));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax_space").doesNotExist())
                .andExpect(jsonPath("$.terminal_value").value(org.hamcrest.Matchers.nullValue()));
    }
```

`804000` = 400,000 × (1 − 0.24) + 300,000 + 200,000.

> If `TerminalValue.compute` scales `afterTaxLegacy` to 4 places (`804000.0000`), `jsonPath(...).value(804000)` still matches, because JsonPath compares numerically for numbers. If it does not, use `.value(804000.0)`.

- [ ] **Step 6: Run to verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-api -am test -Dtest=ProjectionControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `$.tax_space` does not exist, because `ProjectionRunResponse` does not map the new fields yet.

- [ ] **Step 7: Implement `ProjectionRunResponse`**

Replace the record and its factory (keep the existing class Javadoc and extend its last sentence with "plus the Phase 1a `taxSpace` / `terminalValue`"):

```java
public record ProjectionRunResponse(
        UUID scenarioId,
        List<ProjectionYearDto> yearlyData,
        BigDecimal finalBalance,
        int yearsInRetirement,
        SpendingFeasibilitySummary spendingFeasibility,
        BigDecimal finalNetWorth,
        List<String> unclassifiedSymbols,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<TaxSpaceYear> taxSpace,
        @Nullable TerminalValue terminalValue) {

    public static ProjectionRunResponse from(ProjectionRunResult runResult) {
        var result = runResult.result();
        return new ProjectionRunResponse(
                result.scenarioId(), result.yearlyData(), result.finalBalance(),
                result.yearsInRetirement(), result.spendingFeasibility(), result.finalNetWorth(),
                runResult.unclassifiedSymbols(), runResult.warnings(),
                runResult.taxSpace(), runResult.terminalValue());
    }
}
```

Add imports `com.wealthview.core.projection.dto.TaxSpaceYear`, `com.wealthview.core.projection.dto.TerminalValue` and `org.springframework.lang.Nullable`. Then check for direct constructions: `grep -rn "new ProjectionRunResponse(" backend --include=*.java` (expected: only `from`). Fix any test that constructs it by appending `List.of(), null`.

- [ ] **Step 8: Run to verify pass, then the module gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-api -am test -Dtest='ProjectionControllerTest,ProjectionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

Run: `cd backend && mvn -q -T1 -pl wealthview-core,wealthview-api -am verify -DskipITs`
Expected: BUILD SUCCESS (core 90% line, api 80% line, branch floors held).

- [ ] **Step 9: Run the projection HTTP IT end to end**

Run: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true -Dit.test='Projection*IT'`
Expected: BUILD SUCCESS. The real Spring context now wires `TaxSpaceCalculator` into the 7-arg engine constructor, and `/run` still returns 200.

If no `Projection*IT` class exists, find the IT that calls `/run` with `grep -rln '/run' backend/wealthview-app/src/test/java` and run that one.

- [ ] **Step 10: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionRunResult.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionService.java \
        backend/wealthview-api/src/main/java/com/wealthview/api/dto/ProjectionRunResponse.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionServiceTest.java \
        backend/wealthview-api/src/test/java/com/wealthview/api/controller/ProjectionControllerTest.java
git commit -m "$(cat <<'EOF'
feat(api): expose tax_space and terminal_value on the projection run response

ProjectionService.runProjection now calls the engine's runDetailed and
carries the per-year TaxSpaceYear list and the after-tax legacy
TerminalValue through ProjectionRunResult to ProjectionRunResponse
(tax_space omitted when empty, terminal_value null when absent). The
/compare endpoint keeps returning the byte-pinned ProjectionResultResponse.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Frontend Tax Space tab, After-tax Legacy card and heir-rate field

Spec refs: §1.4 (tab), §3 (card and input).

**Files:**
- Modify: `frontend/src/types/projection.ts` (new `BracketRoom`, `TaxSpaceYear`, `TerminalValue`; optional fields on `ProjectionResult`; `heir_tax_rate` on `CreateScenarioRequest`)
- Create: `frontend/src/components/TaxSpaceTab.tsx`
- Create: `frontend/src/components/TaxSpaceTab.test.tsx`
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx` (`TabId`, tab list, tab body, legacy card, card grid)
- Modify: `frontend/src/pages/ProjectionDetailPage.test.tsx`
- Modify: `frontend/src/components/scenario/scenarioFormFields.ts` (`heirTaxRate`)
- Modify: `frontend/src/components/ScenarioForm.tsx` (`buildInitialFields`, destructure, `handleSubmit`)
- Modify: `frontend/src/components/scenario/ScenarioTaxSection.tsx` (heir-rate input)
- Modify: `frontend/src/components/ScenarioForm.test.tsx` (add a row to the percent-field `describe.each` table)

**Interfaces:**
- Consumes: the `/run` wire fields `tax_space` (omitted when empty) and `terminal_value` (`null` when absent) from Task 10. `heir_tax_rate` is accepted by the backend from Task 5 (decimal, 0–0.50, default 0.24 when omitted).
- Produces:
  - `TaxSpaceTab` (default export) with props `{ taxSpace: TaxSpaceYear[] }`.
  - `ProjectionResult.tax_space?: TaxSpaceYear[] | null` and `ProjectionResult.terminal_value?: TerminalValue | null`.
  - `ScenarioFormFields.heirTaxRate: number | null`, a display percent.

- [ ] **Step 1: Add the types**

In `frontend/src/types/projection.ts`, add after `SpendingFeasibility`:

```ts
/** One bracket's remaining room this year (Phase 1a tax space). Gross-income terms. */
export interface BracketRoom {
    rate: number;
    gross_ceiling: number;
    room: number;
}

/** Per-retired-year tax space (backend `TaxSpaceYear`; spec 1a §1.2). Rates are decimals. */
export interface TaxSpaceYear {
    year: number;
    age: number;
    magi: number;
    marginal_ordinary_rate: number;
    /** Current bracket first, then every higher bracket. */
    bracket_room: BracketRoom[];
    ltcg_zero_room: number;
    ltcg_fifteen_room: number;
    /** IRS provisional income (other income + half of benefits); null without Social Security. */
    provisional_income: number | null;
    ss_base_threshold: number | null;
    ss_upper_threshold: number | null;
    /** Extra taxable benefit per $1 of ordinary income: 0, 0.5 or 0.85; null without Social Security. */
    ss_inclusion_rate: number | null;
    /** Negative when MAGI is over the NIIT threshold. */
    niit_headroom: number;
    /** Premium year this year's MAGI sets (year + 2); null unless someone is 65+ by then. */
    irmaa_premium_year: number | null;
    irmaa_tier: number | null;
    /** Null at the top tier. */
    irmaa_room_to_next_tier: number | null;
    irmaa_next_tier_annual_cost: number | null;
    effective_marginal_ordinary: number;
    effective_marginal_ltcg: number;
}

/** After-tax legacy value (backend `TerminalValue`; spec 1a §3). */
export interface TerminalValue {
    year: number;
    traditional: number;
    roth: number;
    taxable: number;
    heir_tax_rate: number;
    after_tax_legacy: number;
    at_second_death: boolean;
}
```

Extend `ProjectionResult`. The new fields are optional because `/compare` payloads and older cache entries lack them:

```ts
export interface ProjectionResult extends ProjectionCompareResult {
    unclassified_symbols: string[] | null;
    warnings: string[] | null;
    /** Phase 1a: omitted by the backend when empty. */
    tax_space?: TaxSpaceYear[] | null;
    terminal_value?: TerminalValue | null;
}
```

Add to `CreateScenarioRequest`, next to `include_depression_years`:

```ts
    /** Heirs' tax rate on inherited traditional balances (decimal, 0-0.50). Omitted ⇒ server default 0.24. */
    heir_tax_rate?: number | null;
```

- [ ] **Step 2: Write the failing `TaxSpaceTab` test**

Create `frontend/src/components/TaxSpaceTab.test.tsx`:

```tsx
import { render, screen, within } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    tableStyle: {},
}));

import TaxSpaceTab from './TaxSpaceTab';
import type { TaxSpaceYear } from '../types/projection';

function makeYear(overrides: Partial<TaxSpaceYear> = {}): TaxSpaceYear {
    return {
        year: 2031,
        age: 62,
        magi: 70000,
        marginal_ordinary_rate: 0.12,
        bracket_room: [{ rate: 0.12, gross_ceiling: 126700, room: 56700 }],
        ltcg_zero_room: 26700,
        ltcg_fifteen_room: 500000,
        provisional_income: null,
        ss_base_threshold: null,
        ss_upper_threshold: null,
        ss_inclusion_rate: null,
        niit_headroom: 180000,
        irmaa_premium_year: null,
        irmaa_tier: null,
        irmaa_room_to_next_tier: null,
        irmaa_next_tier_annual_cost: null,
        effective_marginal_ordinary: 0.12,
        effective_marginal_ltcg: 0,
        ...overrides,
    };
}

describe('TaxSpaceTab', () => {
    it('renders one row per year with bracket, room and capital-gains room', () => {
        render(<TaxSpaceTab taxSpace={[makeYear()]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).getByText('62')).toBeInTheDocument();
        expect(within(row).getByText('$70,000')).toBeInTheDocument();
        expect(within(row).getAllByText('12.0%').length).toBeGreaterThanOrEqual(2);
        expect(within(row).getByText('$56,700')).toBeInTheDocument();
        expect(within(row).getByText('$26,700')).toBeInTheDocument();
        expect(within(row).getByText('$180,000')).toBeInTheDocument();
    });

    it('shows dashes when Social Security and IRMAA do not apply', () => {
        render(<TaxSpaceTab taxSpace={[makeYear()]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    });

    it('labels the Social Security inclusion zone and the IRMAA tier with its premium year', () => {
        render(<TaxSpaceTab taxSpace={[makeYear({
            provisional_income: 52000, ss_base_threshold: 32000, ss_upper_threshold: 44000,
            ss_inclusion_rate: 0.85,
            irmaa_premium_year: 2033, irmaa_tier: 0, irmaa_room_to_next_tier: 36000,
            irmaa_next_tier_annual_cost: 2100,
        })]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).getByText('85¢ per $1')).toBeInTheDocument();
        expect(within(row).getByText('Tier 0 · $36,000 to next (2033)')).toBeInTheDocument();
    });

    it('marks the IRMAA top tier when there is no next tier', () => {
        render(<TaxSpaceTab taxSpace={[makeYear({
            irmaa_premium_year: 2033, irmaa_tier: 5, irmaa_room_to_next_tier: null,
        })]} />);

        expect(screen.getByText('Tier 5 (top) · 2033')).toBeInTheDocument();
    });

    it('renders the column explainers and the planning disclaimer', () => {
        render(<TaxSpaceTab taxSpace={[makeYear()]} />);

        expect(screen.getByText(/not tax advice/i)).toBeInTheDocument();
        expect(screen.getByText(/0% capital-gains room/i)).toBeInTheDocument();
    });

    it('renders an empty-state message for an empty list', () => {
        render(<TaxSpaceTab taxSpace={[]} />);

        expect(screen.getByText(/no retirement years/i)).toBeInTheDocument();
    });
});
```

- [ ] **Step 3: Run to verify failure**

Run: `cd frontend && npx vitest run src/components/TaxSpaceTab.test.tsx`
Expected: FAIL with `Failed to resolve import "./TaxSpaceTab"`.

- [ ] **Step 4: Implement `TaxSpaceTab`**

Create `frontend/src/components/TaxSpaceTab.tsx`:

```tsx
import React from 'react';
import { formatCurrency } from '../utils/format';
import { tableStyle } from '../utils/styles';
import type { TaxSpaceYear } from '../types/projection';

interface TaxSpaceTabProps {
    taxSpace: TaxSpaceYear[];
}

const DASH = '—';

function pct(rate: number | null): string {
    return rate == null ? DASH : `${(rate * 100).toFixed(1)}%`;
}

function money(value: number | null | undefined): string {
    return value == null ? DASH : formatCurrency(value);
}

function ssZone(rate: number | null): string {
    return rate == null ? DASH : `${Math.round(rate * 100)}¢ per $1`;
}

function irmaa(y: TaxSpaceYear): string {
    if (y.irmaa_premium_year == null || y.irmaa_tier == null) return DASH;
    if (y.irmaa_room_to_next_tier == null) return `Tier ${y.irmaa_tier} (top) · ${y.irmaa_premium_year}`;
    return `Tier ${y.irmaa_tier} · ${formatCurrency(y.irmaa_room_to_next_tier)} to next (${y.irmaa_premium_year})`;
}

const EXPLAINERS: [string, string][] = [
    ['MAGI', 'Modified adjusted gross income — the figure ACA credits, IRMAA and the NIIT key off.'],
    ['Bracket / Room to top', 'Your ordinary-income bracket and how much more income fits before the next one.'],
    ['0% capital-gains room', 'Long-term gains or qualified dividends you could still realize at a 0% federal rate.'],
    ['SS zone', 'How much extra Social Security becomes taxable for each extra $1 of ordinary income.'],
    ['NIIT headroom', 'Distance to the 3.8% net investment income tax threshold (negative = over it).'],
    ['IRMAA', 'Medicare surcharge tier this year’s income sets, two years later.'],
    ['Eff. marginal', 'Total tax on the next $1,000 of ordinary income or capital gains, all effects included.'],
];

export default function TaxSpaceTab({ taxSpace }: TaxSpaceTabProps) {
    if (taxSpace.length === 0) {
        return <p style={{ color: '#666' }}>No retirement years in this projection.</p>;
    }

    const th: React.CSSProperties = {
        textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff',
    };
    const td: React.CSSProperties = { textAlign: 'right', padding: '0.5rem' };

    return (
        <div>
            <h4 style={{ marginBottom: '0.5rem' }}>Tax Space by Year</h4>
            <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
                <table style={tableStyle}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ ...th, textAlign: 'left' }}>Year</th>
                            <th style={th}>Age</th>
                            <th style={th}>MAGI</th>
                            <th style={th}>Bracket</th>
                            <th style={th}>Room to Top</th>
                            <th style={th}>0% Cap-Gains Room</th>
                            <th style={th}>SS Zone</th>
                            <th style={th}>NIIT Headroom</th>
                            <th style={th}>IRMAA</th>
                            <th style={th}>Eff. Marginal (Ord.)</th>
                            <th style={th}>Eff. Marginal (LTCG)</th>
                        </tr>
                    </thead>
                    <tbody>
                        {taxSpace.map(y => (
                            <tr key={y.year} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ ...td, textAlign: 'left' }}>{y.year}</td>
                                <td style={td}>{y.age}</td>
                                <td style={td}>{money(y.magi)}</td>
                                <td style={td}>{pct(y.marginal_ordinary_rate)}</td>
                                <td style={td}>{money(y.bracket_room[0]?.room)}</td>
                                <td style={td}>{money(y.ltcg_zero_room)}</td>
                                <td style={td}>{ssZone(y.ss_inclusion_rate)}</td>
                                <td style={{ ...td, color: y.niit_headroom < 0 ? '#d32f2f' : undefined }}>
                                    {money(y.niit_headroom)}
                                </td>
                                <td style={td}>{irmaa(y)}</td>
                                <td style={td}>{pct(y.effective_marginal_ordinary)}</td>
                                <td style={td}>{pct(y.effective_marginal_ltcg)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
            <ul style={{ marginTop: '1rem', fontSize: '0.85rem', color: '#555', paddingLeft: '1.25rem' }}>
                {EXPLAINERS.map(([term, text]) => (
                    <li key={term}><strong>{term}:</strong> {text}</li>
                ))}
            </ul>
            <p style={{ fontSize: '0.8rem', color: '#888' }}>
                WealthView provides planning estimates only, not tax advice. All tax calculations are approximations.
            </p>
        </div>
    );
}
```

The first test's row name match (`{ name: /2031/ }`) works because a `<tr>`'s accessible name is its cell text.

- [ ] **Step 5: Run to verify pass**

Run: `cd frontend && npx vitest run src/components/TaxSpaceTab.test.tsx`
Expected: PASS (6 tests).

- [ ] **Step 6: Write the failing page tests**

Append inside the `describe('ProjectionDetailPage', ...)` block in `ProjectionDetailPage.test.tsx`:

```tsx
    it('shows a Tax Space tab when the run returns tax_space, and renders it on click', async () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();
        const { runProjection } = await import('../api/projections');
        vi.mocked(runProjection).mockResolvedValue(makeResult({
            tax_space: [{
                year: 2046, age: 56, magi: 70000, marginal_ordinary_rate: 0.12,
                bracket_room: [{ rate: 0.12, gross_ceiling: 126700, room: 56700 }],
                ltcg_zero_room: 26700, ltcg_fifteen_room: 500000,
                provisional_income: null, ss_base_threshold: null, ss_upper_threshold: null,
                ss_inclusion_rate: null, niit_headroom: 180000,
                irmaa_premium_year: null, irmaa_tier: null, irmaa_room_to_next_tier: null,
                irmaa_next_tier_annual_cost: null,
                effective_marginal_ordinary: 0.12, effective_marginal_ltcg: 0,
            }],
        }));

        await userEvent.click(screen.getByRole('button', { name: /run projection/i }));
        await userEvent.click(screen.getByRole('button', { name: /tax space/i }));

        expect(screen.getByText('Tax Space by Year')).toBeInTheDocument();
    });

    it('hides the Tax Space tab when the run has no tax_space', async () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();
        const { runProjection } = await import('../api/projections');
        vi.mocked(runProjection).mockResolvedValue(makeResult());

        await userEvent.click(screen.getByRole('button', { name: /run projection/i }));

        expect(screen.queryByRole('button', { name: /tax space/i })).not.toBeInTheDocument();
    });

    it('shows the After-tax Legacy card when the run returns terminal_value', async () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();
        const { runProjection } = await import('../api/projections');
        vi.mocked(runProjection).mockResolvedValue(makeResult({
            terminal_value: {
                year: 2080, traditional: 400000, roth: 300000, taxable: 200000,
                heir_tax_rate: 0.24, after_tax_legacy: 804000, at_second_death: false,
            },
        }));

        await userEvent.click(screen.getByRole('button', { name: /run projection/i }));

        expect(screen.getByText('After-tax Legacy')).toBeInTheDocument();
        expect(screen.getByText('$804,000')).toBeInTheDocument();
    });

    it('omits the After-tax Legacy card when terminal_value is null', async () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();
        const { runProjection } = await import('../api/projections');
        vi.mocked(runProjection).mockResolvedValue(makeResult({ terminal_value: null }));

        await userEvent.click(screen.getByRole('button', { name: /run projection/i }));

        expect(screen.queryByText('After-tax Legacy')).not.toBeInTheDocument();
    });
```

> `$804,000` assumes the page's real `formatCurrency` (this test file does not mock `../utils/format`) renders whole dollars with a `$` and thousands separators. Check `frontend/src/utils/format.ts`. If it renders cents (`$804,000.00`), use that exact string.

- [ ] **Step 7: Run to verify failure**

Run: `cd frontend && npx vitest run src/pages/ProjectionDetailPage.test.tsx`
Expected: FAIL. In the new tests the Tax Space tab button and the After-tax Legacy card are absent; the existing tests still pass.

- [ ] **Step 8: Implement the page changes**

In `frontend/src/pages/ProjectionDetailPage.tsx`:

1. Import the tab: `import TaxSpaceTab from '../components/TaxSpaceTab';`
2. Extend the tab union:

```ts
type TabId = 'chart' | 'flows' | 'table' | 'spending' | 'income_tax' | 'income_streams' | 'tax_shield' | 'tax_space';
```

3. Next to the other `has*` flags (~:106-112):

```ts
    const taxSpace = result?.tax_space ?? [];
    const hasTaxSpace = taxSpace.length > 0;
```

4. In the `TabBar` `tabs` array, right after the `income_tax` entry:

```tsx
                                ...(hasTaxSpace ? [{ key: 'tax_space' as TabId, label: 'Tax Space' }] : []),
```

5. Tab body, after the `income_tax` block:

```tsx
                        {activeTab === 'tax_space' && hasTaxSpace && (
                            <TaxSpaceTab taxSpace={taxSpace} />
                        )}
```

6. Result cards. Change the first card grid's `gridTemplateColumns: 'repeat(5, 1fr)'` to `'repeat(auto-fit, minmax(160px, 1fr))'`, so a sixth card fits. Insert the legacy card directly after the "Final Balance" `SummaryCard`:

```tsx
                        {result.terminal_value && (
                            <SummaryCard
                                label="After-tax Legacy"
                                value={formatCurrency(result.terminal_value.after_tax_legacy)}
                                description={`What heirs keep: traditional balance after a ${Math.round(result.terminal_value.heir_tax_rate * 100)}% heir tax rate, plus Roth and taxable at full value (cost basis steps up at death)${result.terminal_value.at_second_death ? ', valued at the second death' : ''}.`}
                            />
                        )}
```

- [ ] **Step 9: Run to verify pass**

Run: `cd frontend && npx vitest run src/pages/ProjectionDetailPage.test.tsx src/components/TaxSpaceTab.test.tsx`
Expected: PASS.

- [ ] **Step 10: Write the failing heir-rate form tests**

In `frontend/src/components/ScenarioForm.test.tsx`, add a third row to the percent-field `describe.each` table (~:281-291). Update the comment above it to say "three simple params_json percent fields":

```tsx
        {
            label: 'Heir Tax Rate (%)', paramKey: 'heir_tax_rate' as const,
            defaultDisplay: '24', hydrateFraction: 0.5, hydrateDisplay: '50',
            submitPct: '30', submitFraction: 0.3,
        },
```

This generates the same 5 behaviors for the heir rate:
- defaults to 24;
- hydrates 0.5 → 50;
- submits 30% → 0.3;
- omits the field when blank, so the server default 0.24 applies;
- round-trips a genuine 0%.

- [ ] **Step 11: Run to verify failure**

Run: `cd frontend && npx vitest run src/components/ScenarioForm.test.tsx`
Expected: FAIL. The 5 new `heir_tax_rate` cases cannot find a field labeled "Heir Tax Rate (%)"; all other cases still pass.

- [ ] **Step 12: Implement the heir-rate field**

`frontend/src/components/scenario/scenarioFormFields.ts`: add to `ScenarioFormFields`, after `includeDepressionYears`:

```ts
    /** Heirs' tax rate on inherited traditional balances, display percent (null = blank ⇒ server default 24%). */
    heirTaxRate: number | null;
```

`frontend/src/components/ScenarioForm.tsx`:
- In `buildInitialFields`, after `includeDepressionYears`:

```ts
        heirTaxRate: parsedParams.heir_tax_rate != null ? parsedParams.heir_tax_rate * 100 : 24,
```

- Add `heirTaxRate` to the `const { ... } = fields;` destructure (~:154).
- In `handleSubmit`'s request, after `include_depression_years`:

```ts
                heir_tax_rate: heirTaxRate != null ? heirTaxRate / 100 : undefined,
```

`frontend/src/components/scenario/ScenarioTaxSection.tsx`: destructure `heirTaxRate` with the other fields. Add the input as the LAST child of the grid `<div>`, outside the `{state && (...)}` block, so it shows for every scenario:

```tsx
                <FormField
                    label="Heir Tax Rate (%)"
                    helpText="Tax rate your heirs would pay on inherited traditional (pre-tax) balances. Used for the After-tax Legacy figure (default 24%)."
                >
                    <input
                        style={inputStyle}
                        type="number"
                        step="1"
                        min="0"
                        max="50"
                        value={heirTaxRate ?? ''}
                        onChange={e => setField('heirTaxRate', e.target.value === '' ? null : Number(e.target.value))}
                    />
                </FormField>
```

- [ ] **Step 13: Run tests, typecheck, lint and coverage**

Run: `cd frontend && npx vitest run src/components/ScenarioForm.test.tsx src/components/TaxSpaceTab.test.tsx src/pages/ProjectionDetailPage.test.tsx`
Expected: PASS.

Run: `cd frontend && npm run typecheck && npm run lint`
Expected: both exit 0.

Run: `cd frontend && npm run test:coverage`
Expected: PASS with the ratchet floors from `vite.config.ts` (statements 83, branches 75, functions 74, lines 86) held. If coverage rose above a floor, raise that floor in `vite.config.ts` in this commit.

- [ ] **Step 14: Commit**

```bash
git add frontend/src/types/projection.ts \
        frontend/src/components/TaxSpaceTab.tsx frontend/src/components/TaxSpaceTab.test.tsx \
        frontend/src/pages/ProjectionDetailPage.tsx frontend/src/pages/ProjectionDetailPage.test.tsx \
        frontend/src/components/scenario/scenarioFormFields.ts frontend/src/components/ScenarioForm.tsx \
        frontend/src/components/scenario/ScenarioTaxSection.tsx frontend/src/components/ScenarioForm.test.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): add Tax Space tab, After-tax Legacy card and heir tax rate input

The projection page shows a read-only Tax Space tab (MAGI, bracket and
room to top, 0% capital-gains room, Social Security inclusion zone, NIIT
headroom, IRMAA tier/room/premium year, effective marginal rates) when
the run returns tax_space, and an After-tax Legacy card beside Final
Balance when it returns terminal_value. Both stay hidden for compare
payloads and older cached results. The scenario form gains an Heir Tax
Rate (%) field (default 24, sent as a decimal, omitted when blank).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```


### Task 12: D5 — tax-funding sales realize taxable gains (both engines)

Two commits: 12a deterministic engine, then 12b Monte Carlo. Each commit re-pins only the tests whose literals move, and records the before/after numbers in the commit body.

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TaxableLotsBd.java` (add `peekFifoGain`)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`
  - interface: add `hasPendingTaxSaleGain()` next to `getFilingStatus()`
  - `MultiPool`: new field, `executeWithdrawals` tail (~:1126-1225), new `BillInputs` / `TaxBill` / `computeBill` / `settleTaxSaleGain`, `MultiPoolMemento` / `snapshot` / `restore` (~:1438-1462), `deductFromPools` (~:1701-1721)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/YearFinanceResolver.java:226` (pre-retirement branch condition)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TaxableLots.java` (add `peekFifoGain`)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialPools.java`
  - add the `taxSaleGain` accumulator, `sellTaxableForTax`, `drainTaxSaleGain`, `peekTaxableSaleGain`
  - change `deductTaxFromPools` and `debitTaxableWithTraditionalSpillover`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialSimulator.java`
  - `applyLtcgTax` (~:918), plus a new package-private `ltcgTaxForYear`
  - `seedCashReserve` (~:826), `applyTrialConversion` (~:1047)
- Test (create): `backend/wealthview-projection/src/test/java/com/wealthview/projection/MultiPoolTaxSaleGainTest.java`
- Test (create): `backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialSimulatorTaxSaleGainTest.java`
- Test (modify): `TaxableLotsBdTest.java`, `TaxableLotsTest.java`, `DeterministicProjectionEngineRothConversionTest.java`
- Re-pin (procedure in Steps 12a-8 and 12b-7):
  - `backend/wealthview-projection/src/test/resources/golden/*.json`
  - `MonteCarloSpendingOptimizerCharacterizationTest.java`
  - any other literal-pin characterization test the run reports

**Interfaces:**
- Consumes:
  - Task 3: `MultiPool`'s `earlyAccessAge` field (set from `PoolConfig.Builder.earlyAccessAge(int)`; sites compare `age < earlyAccessAge`).
  - Task 3: `applyTrialConversion`'s `age < earlyAccessAge` comparison.
- Produces:
  - `TaxableLotsBd.peekFifoGain(BigDecimal amount): BigDecimal` and `TaxableLots.peekFifoGain(double amount): double`, both non-mutating.
  - `PoolStrategy.hasPendingTaxSaleGain(): boolean`.
  - `TrialPools`: `sellTaxableForTax(double): double`, `drainTaxSaleGain(): double`, `peekTaxableSaleGain(double): double`.
  - `TrialSimulator.ltcgTaxForYear(TrialPools tp, double realizedGain, double dividendIncome, LtcgTaxTable ltcgTable, double ordinaryStack, double netRentalIncome): double` (package-private static).
  - Behavior: `WithdrawalTaxResult.realizedLtcgIncome()` / `ltcgTax()` now include gains from tax-funding sales. Task 9's `YearTaxPicture.qualifiedDividendsAndLtcg` / `chargedLtcgTax` therefore stay reconcilable.

**Design (read before coding):**

Deterministic engine:
- `deductFromPools` now books the FIFO gain of every taxable slice it sells into `MultiPool.pendingTaxSaleGain`. The value may be negative for loss lots.
- A conversion-tax sale earlier in the year therefore leaves a pending gain. `executeWithdrawals` folds that gain into the year's realized gain.
- The year's own bill is solved as a fixed point in the gain of the sale that funds it:
  1. Compute the bill at gain 0.
  2. Peek the FIFO gain of selling `min(bill, taxableAvail)`.
  3. Recompute the bill at that gain.
  4. Take the chord tax-per-sale-dollar `r = Δbill / sale0`, capped to `[0, GROSS_UP_WARM_START_RATE_CAP]`.
  5. Warm-jump to `sale = bill0 / (1 − r)`.
  6. Run at most 3 polish passes until the peeked gain moves by less than `GROSS_UP_TOLERANCE` ($1).
- The early-withdrawal penalty is now part of the bill, so its funding sale is priced too. The cascade order is unchanged: one `deductFromPools(tax+penalty)` drains exactly as `deductFromPools(tax)` followed by `deductFromPools(penalty)` did.
- Realized LTCG income stays `max(0, …)`, so a loss offsets the year's gains and never produces negative tax.
- A not-yet-retired year with a conversion and no RMD previously never called `executeWithdrawals`. `YearFinanceResolver` now calls it whenever a pending tax-sale gain exists, so that gain is taxed in the year it was realized.

Monte Carlo:
- `TrialPools` accumulates the FIFO gain of every non-spending taxable sale into `taxSaleGain`:
  - tax cascades: interest, RMD, withdrawal, base-income, penalty and conversion tax
  - the pre-60 conversion-tax sale
  - the cash-reserve seed
  - the cash-reserve refill
- `applyLtcgTax` (the last tax of the trial-year) drains the accumulator into LTCG income, floored at 0.
- It prices the LTCG bill's own funding sale with the same closed form plus one polish pass, then discards the accumulator after the drain.

- [ ] **Step 12a-1: Write failing tests for the non-mutating FIFO gain peek (both lot classes)**

Append to `TaxableLotsBdTest.java`. Add any imports it lacks: `java.math.BigDecimal`, `static org.assertj.core.api.Assertions.assertThat`.

```java
    @Test
    void peekFifoGain_partialOldestLot_matchesSellFifoWithoutMutating() {
        var lots = new TaxableLotsBd();
        lots.addLot(new BigDecimal("100000"), new BigDecimal("200000"));   // 50% embedded gain
        lots.addLot(new BigDecimal("50000"));                              // at cost

        BigDecimal peeked = lots.peekFifoGain(new BigDecimal("7014"));

        assertThat(peeked).isEqualByComparingTo("3507.0000");
        assertThat(lots.totalValue()).isEqualByComparingTo("250000");
        assertThat(lots.totalBasis()).isEqualByComparingTo("150000");
        assertThat(lots.sellFifo(new BigDecimal("7014"))).isEqualByComparingTo(peeked);
    }

    @Test
    void peekFifoGain_lossLot_returnsNegativeGain() {
        var lots = new TaxableLotsBd();
        lots.addLot(new BigDecimal("300000"), new BigDecimal("200000"));   // basis above value

        assertThat(lots.peekFifoGain(new BigDecimal("7014"))).isEqualByComparingTo("-3507.0000");
    }

    @Test
    void peekFifoGain_amountAboveTotal_cappedAtTotalValue() {
        var lots = new TaxableLotsBd();
        lots.addLot(new BigDecimal("600"), new BigDecimal("1000"));

        assertThat(lots.peekFifoGain(new BigDecimal("5000"))).isEqualByComparingTo("400.0000");
    }
```

Append to `TaxableLotsTest.java`:

```java
    @Test
    void peekFifoGain_spanningLots_matchesSellFifoWithoutMutating() {
        var lots = new TaxableLots();
        lots.addLot(100); lots.grow(1.0);   // A: basis 100, value 200
        lots.addLot(100);                    // B: basis 100, value 100

        double peeked = lots.peekFifoGain(250);

        assertThat(peeked).isEqualTo(100.0, within(1e-9));
        assertThat(lots.totalValue()).isEqualTo(300.0, within(1e-9));
        assertThat(lots.sellFifo(250)).isEqualTo(peeked, within(1e-9));
    }
```

- [ ] **Step 12a-2: Run them; verify they fail to compile**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='TaxableLotsBdTest,TaxableLotsTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: COMPILATION ERROR, `cannot find symbol: method peekFifoGain`.

- [ ] **Step 12a-3: Implement the peeks**

In `TaxableLotsBd.java`, directly below `sellFifo`:

```java
    /** D5: the realized gain {@link #sellFifo} WOULD return for {@code amount}, without mutating any
     * lot -- lets the tax-funding fixed point in {@code PoolStrategy.MultiPool} price the gain of the
     * sale that pays a bill before the sale happens. Negative for loss lots. */
    BigDecimal peekFifoGain(BigDecimal amount) {
        BigDecimal remaining = amount.min(totalValue()).max(BigDecimal.ZERO);
        BigDecimal gain = BigDecimal.ZERO;
        for (BigDecimal[] lot : lots) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal basis = lot[0];
            BigDecimal value = lot[1];
            if (value.compareTo(remaining) <= 0) {
                gain = gain.add(value.subtract(basis));
                remaining = remaining.subtract(value);
            } else {
                BigDecimal soldBasis = basis.multiply(remaining)
                        .divide(value, DIV_SCALE, ROUNDING).setScale(SCALE, ROUNDING);
                gain = gain.add(remaining.subtract(soldBasis));
                remaining = BigDecimal.ZERO;
            }
        }
        return gain.setScale(SCALE, ROUNDING);
    }
```

In `TaxableLots.java`, directly below `sellFifo(double)`:

```java
    /** D5: the realized gain {@link #sellFifo} WOULD return for {@code amount}, without mutating any
     * lot (Monte Carlo twin of {@code TaxableLotsBd#peekFifoGain}). Negative for loss lots. */
    double peekFifoGain(double amount) {
        double remaining = Math.min(amount, totalValue());
        double gain = 0;
        for (double[] lot : lots) {
            if (remaining <= 1e-12) {
                break;
            }
            double basis = lot[0];
            double value = lot[1];
            if (value <= remaining + 1e-12) {
                gain += value - basis;
                remaining -= value;
            } else {
                gain += remaining - basis * (remaining / value);
                remaining = 0;
            }
        }
        return gain;
    }
```

- [ ] **Step 12a-4: Run; verify PASS**

Run the Step 12a-2 command. Expected: BUILD SUCCESS, the 4 new tests pass.

- [ ] **Step 12a-5: Write the failing deterministic D5 tests**

Create `backend/wealthview-projection/src/test/java/com/wealthview/projection/MultiPoolTaxSaleGainTest.java`:

```java
package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalOnlyTaxStrategy;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * D5 (Phase 1a): the gain realized by selling taxable lots to PAY tax is itself LTCG income, taxed in
 * the same year through the normal stacking -- previously discarded. Fixtures: single filer, 2025
 * ($15,000 standard deduction; 0% LTCG ceiling $48,350; 12% bracket ends $48,475; 22% to $103,350).
 */
class MultiPoolTaxSaleGainTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2025;
    private static final int AGE_RETIRED = 65;

    private static FederalTaxCalculator federal() {
        var brackets = mock(TaxBracketRepository.class);
        var deductions = mock(StandardDeductionRepository.class);
        stubSingle2025(brackets, deductions);
        return new FederalTaxCalculator(brackets, deductions);
    }

    private static CapitalGainsTaxCalculator capitalGains() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    private static HypotheticalAccountInput taxable(String balance, String basis) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(basis), "taxable");
    }

    private static PoolStrategy.MultiPool pool(String taxableBalance, String taxableBasis,
                                               WithdrawalOrder order, String annualConversion) {
        var federal = federal();
        var config = PoolStrategy.PoolConfig.builder(FilingStatus.SINGLE, ZERO, bd(annualConversion), "fixed",
                        null, null, order, new FederalOnlyTaxStrategy(federal), null)
                .capitalGainsTaxCalculator(capitalGains())
                .federalTaxCalculator(federal)
                .baseYear(YEAR)
                .build();
        return new PoolStrategy.MultiPool(
                PoolFixtures.grouped(taxable(taxableBalance, taxableBasis),
                        new HypotheticalAccountInput(bd("500000"), ZERO, ZERO, "traditional"),
                        new HypotheticalAccountInput(ZERO, ZERO, ZERO, "roth")),
                ZERO, config);
    }

    @Test
    void executeWithdrawals_ordinaryTaxPaidFromGainLots_taxesTheSaleGainAtFifteenPercent() {
        // $70k traditional draw -> ordinary tax 7,014.00 (taxable 55,000). Paying it sells 50%-gain
        // lots; the gain stacks above the $48,350 0% ceiling -> 15%. Fixed point:
        // sale S = 7014 + 0.15 * 0.5 * S  ->  S = 7014 / 0.925 = 7582.70; LTCG = 568.70.
        var p = pool("200000", "100000", WithdrawalOrder.TRADITIONAL_FIRST, "0");

        var r = p.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isCloseTo(bd("568.70"), within(bd("0.01")));
        assertThat(r.taxLiability()).isCloseTo(bd("7582.70"), within(bd("0.01")));
        assertThat(r.realizedLtcgIncome()).isCloseTo(bd("3791.35"), within(bd("0.02")));
    }

    @Test
    void executeWithdrawals_ordinaryTaxPaidFromLossLots_lossOffsetsAndNeverCreatesNegativeTax() {
        // Same bill funded from a lot carrying a LOSS (basis 300k > value 200k): the realized loss
        // nets LTCG income to max(0, -3507) = 0 -> zero LTCG tax, and the ordinary bill is untouched.
        var p = pool("200000", "300000", WithdrawalOrder.TRADITIONAL_FIRST, "0");

        var r = p.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isEqualByComparingTo(ZERO);
        assertThat(r.realizedLtcgIncome()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("7014.00"));
    }

    @Test
    void executeWithdrawals_noEmbeddedGain_billUnchangedFromPreD5() {
        // Basis == value: the funding sale realizes nothing -> exactly the pre-D5 ordinary bill.
        var p = pool("200000", "200000", WithdrawalOrder.TRADITIONAL_FIRST, "0");

        var r = p.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("7014.00"));
    }

    @Test
    void conversionTaxSale_gainPendsUntilExecuteWithdrawalsThenIsTaxed() {
        // $80k fixed conversion -> tax 9,214.00 paid from 50%-gain lots -> pending gain 4,607.
        // executeWithdrawals (zero spend need) taxes it stacked on 80,000 - 15,000 = 65,000 (15%),
        // plus the LTCG bill's own funding sale: 691.05 / 0.925 = 747.08.
        var p = pool("200000", "100000", WithdrawalOrder.TAXABLE_FIRST, "80000");

        p.executeRothConversion(YEAR, ZERO, ZERO, ZERO);

        assertThat(p.hasPendingTaxSaleGain()).isTrue();

        var r = p.executeWithdrawals(ZERO, YEAR, ZERO, bd("80000"), ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isCloseTo(bd("747.08"), within(bd("0.01")));
        assertThat(r.realizedLtcgIncome()).isCloseTo(bd("4980.54"), within(bd("0.02")));
        assertThat(p.hasPendingTaxSaleGain()).isFalse();
    }

    @Test
    void snapshotRestore_pendingTaxSaleGainRestored() {
        var p = pool("200000", "100000", WithdrawalOrder.TAXABLE_FIRST, "80000");
        var memento = p.snapshot();
        p.executeRothConversion(YEAR, ZERO, ZERO, ZERO);

        p.restore(memento);

        assertThat(p.hasPendingTaxSaleGain()).isFalse();
    }
}
```

Append to `DeterministicProjectionEngineRothConversionTest.java`. Add imports: `java.util.Optional`, `com.wealthview.core.projection.dto.AssetAllocation`, `com.wealthview.core.projection.dto.HypotheticalAccountInput`, `com.wealthview.core.projection.tax.CapitalGainsTaxCalculator`, `com.wealthview.core.projection.tax.FederalTaxCalculator`, `com.wealthview.persistence.repository.LtcgBracketRepository`, `static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg`, `static org.assertj.core.api.Assertions.within`, `static org.mockito.Mockito.mock`.

```java
    @Test
    void run_preRetirementConversionPaidFromGainLots_saleGainTaxedInSameYear() {
        // Accumulation year (age 40, retires in 20 years): no spend draw and no RMD, so before D5
        // executeWithdrawals never ran and the conversion-tax sale's gain went untaxed. Now the
        // pending gain forces the zero-need withdrawal cycle: 9,214.00 conversion tax sells 50%-gain
        // lots -> 4,607 gain at 15% plus its own funding sale -> 747.08 capital-gains tax. Fee, dividend
        // and interest yields are zeroed so year-1 lot values stay exactly at the seeded 50% gain.
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var ltcgRepo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(ltcgRepo);
        var engineTax = new DeterministicProjectionEngine(
                new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository), null,
                new CapitalGainsTaxCalculator(ltcgRepo));
        var input = createInput(
                LocalDate.now().plusYears(20), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 80000,
                 "fee_rate": 0, "dividend_yield": 0, "interest_yield": 0}
                """.formatted(LocalDate.now().getYear() - 40),
                List.of(
                        new HypotheticalAccountInput(bd("200000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                                Optional.of(BigDecimal.ZERO), bd("100000"), "taxable"),
                        acct("500000.0000", "0", "0", "traditional")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("80000"));
        assertThat(year1.capitalGainsTax()).isCloseTo(bd("747.08"), within(bd("0.01")));
    }
```

- [ ] **Step 12a-6: Run; verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='MultiPoolTaxSaleGainTest,DeterministicProjectionEngineRothConversionTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: COMPILATION ERROR, `cannot find symbol: method hasPendingTaxSaleGain()`.

- [ ] **Step 12a-7: Implement the deterministic D5**

(a) In `PoolStrategy` (the sealed interface), next to `FilingStatus getFilingStatus();`:

```java
    /** D5: true when a tax-funding taxable sale earlier this year realized a gain (or loss) that the
     * year's withdrawal cycle has not yet taxed -- lets a not-yet-retired conversion year run the
     * zero-need withdrawal cycle so that gain is taxed in the year it was realized. */
    boolean hasPendingTaxSaleGain();
```

(b) In `MultiPool`, next to the other mutable fields (`qualifiedDividendIncome`, `ordinaryInterestIncome`):

```java
        /** D5: FIFO gain realized this year by {@link #deductFromPools}'s taxable slice (conversion-tax
         * sales) that {@link #executeWithdrawals} has not yet folded into the year's LTCG income. May be
         * negative (loss lots). Captured by the memento so the Social Security fixed point restores it. */
        private BigDecimal pendingTaxSaleGain = BigDecimal.ZERO;
```

Next to the other `MultiPool` constants (`GROSS_UP_TOLERANCE`, `MAX_GROSS_UP_ITERATIONS`):

```java
        /** D5: polish passes after the closed-form warm start in {@link #settleTaxSaleGain}. */
        private static final int MAX_TAX_SALE_GAIN_ITERATIONS = 3;
```

Implement the interface method next to `getFilingStatus()`:

```java
        @Override
        public boolean hasPendingTaxSaleGain() {
            return pendingTaxSaleGain.signum() != 0;
        }
```

(c) In `executeWithdrawals`, replace everything from `BigDecimal realizedGain = lots.sellFifo(fromTaxable);` through the final `return new WithdrawalTaxResult(...);` with:

```java
            // Selling the taxable draw FIFO realizes a long-term capital gain (oldest lots first).
            // The traditional/Roth spend draws split proportionally by owner balance (task 4).
            BigDecimal spendSaleGain = lots.sellFifo(fromTaxable);
            traditional.debitProportional(fromTraditional);
            roth.debitProportional(fromRoth);

            // T18a-1: the RMD was already forced out of traditional (into a fresh taxable lot) before
            // this method ran; fold it into this year's traditional-sourced ordinary income.
            BigDecimal rmdForced = rmdAmount != null ? rmdAmount.max(BigDecimal.ZERO) : BigDecimal.ZERO;
            BigDecimal traditionalSpendIncome = fromTraditional.add(rmdForced);

            // D5: gains realized earlier this year by conversion-tax sales (deductFromPools) join this
            // year's realized gain, and the bill's OWN funding sale is solved as a fixed point.
            BigDecimal baseRealizedGain = spendSaleGain.add(pendingTaxSaleGain);
            pendingTaxSaleGain = BigDecimal.ZERO;
            TaxBill bill = settleTaxSaleGain(new BillInputs(year, effectiveOtherIncome, conversionAmount,
                    traditionalSpendIncome, baseRealizedGain, alreadyChargedBaseTax, extraPoolFundedTax,
                    federallyTaxedSocialSecurity, netRentalIncome, age, lots.totalValue(), traditional.total()));

            // The converged C2 gross-up draw is itself a traditional distribution -- see GrossUpResult.
            BigDecimal traditionalOrdinaryIncome = traditionalSpendIncome.add(bill.grossUp().traditionalGrossUp());

            // One drain for tax + penalty: the cascade order is identical to the former two
            // back-to-back drains (taxable first, then traditional, then Roth).
            TaxSourceResult withdrawalTaxSource = bill.total().compareTo(BigDecimal.ZERO) > 0
                    ? deductFromPools(bill.total()) : TaxSourceResult.ZERO;
            // deductFromPools just booked the funding sale's gain again; settleTaxSaleGain already
            // priced it, so drop it rather than taxing it twice.
            pendingTaxSaleGain = BigDecimal.ZERO;

            // T18a-5a: the aggregate includes the forced RMD excess -- see the WithdrawalTaxResult javadoc.
            return new WithdrawalTaxResult(
                    fromTaxable.add(fromTraditional).add(fromRoth).add(rmdForced),
                    bill.total(),
                    fromTaxable, traditionalOrdinaryIncome, fromRoth, withdrawalTaxSource, bill.ltcgTax(),
                    bill.realizedLtcgIncome(), bill.earlyWithdrawalPenalty(), ordinaryInterestIncome);
        }

        /** D5: everything the year's withdrawal-cycle bill depends on EXCEPT the gain of the taxable
         * sale that funds it. {@code taxableAvail}/{@code traditionalAvail} are frozen post-spend-draw,
         * pre-drain snapshots (the C2 gross-up contract). */
        private record BillInputs(int year, BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                                  BigDecimal traditionalSpendIncome, BigDecimal baseRealizedGain,
                                  BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax,
                                  BigDecimal federallyTaxedSocialSecurity, BigDecimal netRentalIncome, int age,
                                  BigDecimal taxableAvail, BigDecimal traditionalAvail) {}

        /** D5: one evaluation of the year's full bill (ordinary bundle + LTCG + C2 gross-up + the 10%
         * early-withdrawal penalty) at a candidate tax-funding-sale gain. */
        private record TaxBill(BigDecimal total, BigDecimal ltcgTax, BigDecimal realizedLtcgIncome,
                               GrossUpResult grossUp, BigDecimal earlyWithdrawalPenalty) {}

        private TaxBill computeBill(BillInputs in, BigDecimal taxSaleGain) {
            BigDecimal realizedGain = in.baseRealizedGain().add(taxSaleGain);
            // Floored at zero: a net realized loss offsets the year's gains/dividends but never yields
            // a negative tax (its small AGI offset stays out of scope).
            BigDecimal realizedLtcgIncome = realizedGain.add(qualifiedDividendIncome).max(BigDecimal.ZERO);
            BigDecimal taxableIncome = in.traditionalSpendIncome().add(in.effectiveOtherIncome())
                    .add(in.conversionAmount()).add(ordinaryInterestIncome);
            var ordinaryTax = computeOrdinaryTax(taxableIncome, in.year(), in.effectiveOtherIncome(),
                    in.conversionAmount(), in.alreadyChargedBaseTax(), realizedLtcgIncome,
                    in.federallyTaxedSocialSecurity());
            BigDecimal ltcgTax = computeLtcgTax(realizedGain, taxableIncome, in.year(), ordinaryTax.detailed(),
                    in.netRentalIncome());
            var grossUp = growTraditionalGrossUp(taxableIncome, in.year(), in.effectiveOtherIncome(),
                    in.conversionAmount(), in.alreadyChargedBaseTax(), realizedLtcgIncome,
                    in.federallyTaxedSocialSecurity(), ltcgTax, in.extraPoolFundedTax(), ordinaryTax,
                    in.taxableAvail(), in.traditionalAvail());
            // T18a-4: 10% additional tax on the year's full traditional DISTRIBUTION before early-access
            // age (spend draw + RMD + C2 gross-up slice); conversions are out of scope.
            BigDecimal penalty = in.age() < earlyAccessAge
                    ? in.traditionalSpendIncome().add(grossUp.traditionalGrossUp())
                            .multiply(EARLY_WITHDRAWAL_PENALTY_RATE)
                    : BigDecimal.ZERO;
            return new TaxBill(grossUp.tax().add(penalty), ltcgTax, realizedLtcgIncome, grossUp, penalty);
        }

        /**
         * D5: solves {@code bill = Bill(gainOf(min(bill, taxableAvail)))} -- the taxable sale that pays
         * the year's bill realizes a gain, which raises the bill. Same convergence shape as
         * {@link #growTraditionalGrossUp}: one naive pass, a closed-form warm jump off the measured
         * tax-per-sale-dollar chord ({@code sale = bill0 / (1 - r)}), then at most
         * {@link #MAX_TAX_SALE_GAIN_ITERATIONS} polish passes to a $1 gain tolerance. Never mutates a
         * pool ({@code peekFifoGain} only); the LAST {@code computeBill} call is always at the returned
         * gain, so {@code lastTaxBreakdown} reflects the settled bill. No embedded gain => returns the
         * gain-0 bill, byte-identical to pre-D5.
         */
        private TaxBill settleTaxSaleGain(BillInputs in) {
            TaxBill bill = computeBill(in, BigDecimal.ZERO);
            BigDecimal sale0 = bill.total().min(in.taxableAvail());
            BigDecimal gain = lots.peekFifoGain(sale0);
            if (gain.abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
                return bill;
            }
            TaxBill atGain = computeBill(in, gain);
            BigDecimal taxPerSaleDollar = atGain.total().subtract(bill.total())
                    .divide(sale0, SCALE + 4, ROUNDING)
                    .max(BigDecimal.ZERO)
                    .min(GROSS_UP_WARM_START_RATE_CAP);
            BigDecimal warmSale = bill.total()
                    .divide(BigDecimal.ONE.subtract(taxPerSaleDollar), SCALE + 4, ROUNDING)
                    .min(in.taxableAvail());
            BigDecimal settledGain = lots.peekFifoGain(warmSale);
            TaxBill current = computeBill(in, settledGain);
            for (int i = 0; i < MAX_TAX_SALE_GAIN_ITERATIONS; i++) {
                BigDecimal impliedGain = lots.peekFifoGain(current.total().min(in.taxableAvail()));
                if (impliedGain.subtract(settledGain).abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
                    break;
                }
                settledGain = impliedGain;
                current = computeBill(in, settledGain);
            }
            return current;
        }
```

Delete the now-dead `BigDecimal earlyWithdrawalPenalty = age < ... ;` block and the separate `deductFromPools(earlyWithdrawalPenalty)` call. Both are replaced above. The penalty comment moved into `computeBill`.

(d) In `deductFromPools`, replace

```java
            // Pay from taxable first, selling lots FIFO. The gain realized by this tax-payment sale
            // is deliberately not itself taxed (a second-order effect out of scope for this model).
            BigDecimal fromTax = remaining.min(lots.totalValue());
            lots.sellFifo(fromTax);
```

with

```java
            // Pay from taxable first, selling lots FIFO. D5: the realized gain pends until the year's
            // withdrawal cycle taxes it (executeWithdrawals folds it in, or prices it via
            // settleTaxSaleGain when this drain IS that cycle's bill).
            BigDecimal fromTax = remaining.min(lots.totalValue());
            pendingTaxSaleGain = pendingTaxSaleGain.add(lots.sellFifo(fromTax));
```

(e) Memento:
- Add a component `BigDecimal pendingTaxSaleGain` at the end of `record MultiPoolMemento(...)`.
- In `snapshot()`, pass `pendingTaxSaleGain` as the last argument.
- In `restore(...)`, add `this.pendingTaxSaleGain = m.pendingTaxSaleGain();`.

(f) Remove the now-false parenthetical in the `growTraditionalGrossUp` javadoc ("the same category as the taxable-slice realized-gain discard in {@link #deductFromPools}"). Replace it with "(re-stacking LTCG against a higher ordinary floor as the gross-up grows is a documented, out-of-scope second-order effect)". In the same javadoc, change "the taxable slice keeps its existing untaxed-sale treatment" to "the taxable slice's realized gain is priced by {@link #settleTaxSaleGain} (D5)".

(g) In `YearFinanceResolver`, change the pre-retirement branch condition

```java
        } else if (rmdForced.compareTo(BigDecimal.ZERO) > 0) {
```

to

```java
        } else if (rmdForced.compareTo(BigDecimal.ZERO) > 0 || pool.hasPendingTaxSaleGain()) {
```

Then append this sentence to that branch's comment: "D5: also runs when a conversion-tax sale realized a gain this year, so the gain is taxed in the year it was realized."

- [ ] **Step 12a-8: Run the new tests, then the full module; re-pin moved literals**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest='MultiPoolTaxSaleGainTest,DeterministicProjectionEngineRothConversionTest,TaxableLots*Test' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

Capture the golden headline numbers BEFORE regenerating:

```bash
cd backend/wealthview-projection/src/test/resources/golden
for f in accumulation-gap-pension household-survivor multi-pool-roth-conversion simple-preretirement ss-rmd-retiree tiered-spending-with-income; do
  echo "$f before=$(git show HEAD:backend/wealthview-projection/src/test/resources/golden/$f.json | jq '.finalBalance')"
done
```

Run the whole module: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: the following may FAIL and only these:
- `ProjectionGoldenFileTest` cases whose scenarios sell gain-bearing lots to pay tax
- literal-pin characterization tests, such as `PoolStrategyCharacterizationTest`

For EACH failure:
- Read the assertion message.
- If it is a pinned literal (an `isEqualByComparingTo(new BigDecimal("…"))` characterization pin, or the golden JSON), the pin moves. Confirm the direction is "more tax / lower balance", or "unchanged" for zero-gain years.
- Any other failure is a real regression. Debug it (superpowers:systematic-debugging) and do not re-pin it.

Regenerate goldens: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=ProjectionGoldenFileTest -Dsurefire.failIfNoSpecifiedTests=false -Dupdate.golden=true`.

Review `git diff --stat backend/wealthview-projection/src/test/resources/golden`. Re-run the per-file loop with `after=$(jq '.finalBalance' $f.json)` and record each before/after pair.

Update each other moved literal pin to the value its failure message reports. Add a `// D5 (Phase 1a): was <old>` comment beside it.

Re-run the full module. Expected: BUILD SUCCESS.

- [ ] **Step 12a-9: Gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`

Expected: BUILD SUCCESS. PMD, CPD, SpotBugs, Checkstyle and JaCoCo are green.

- [ ] **Step 12a-10: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/TaxableLotsBd.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/TaxableLots.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/YearFinanceResolver.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/ \
        backend/wealthview-projection/src/test/resources/golden/
git commit -F - <<'EOF'
fix(projection): tax the gain realized by tax-funding taxable sales (deterministic)

D5 (Phase 1a). Selling FIFO lots to pay a tax bill realized a capital gain that
PoolStrategy.deductFromPools discarded, understating tax for brokerage-heavy
plans. The gain now pends on the pool and is folded into the year's LTCG income
by executeWithdrawals; the bill's own funding sale is solved as a fixed point
(closed-form warm start sale = bill/(1-r), <=3 polish passes, $1 tolerance)
mirroring the C2 gross-up. The 10% early-withdrawal penalty joins the same bill
so its funding sale is priced too (one drain, identical cascade order). A
not-yet-retired conversion year now runs the zero-need withdrawal cycle when a
tax-sale gain is pending, so the gain is taxed in the year realized. Loss lots
offset gains; LTCG income stays floored at zero.

Golden deltas (finalBalance before -> after):
<one line per golden file from Step 12a-8>
Other re-pinned characterization literals:
<test#method: old -> new, one line each>

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

Fill in the two `<…>` blocks with the actual numbers recorded in Step 12a-8 before running the command. The commit body must list real values.

- [ ] **Step 12b-1: Write the failing Monte Carlo D5 tests**

Create `backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialSimulatorTaxSaleGainTest.java`:

```java
package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** D5 (Phase 1a), Monte Carlo side: non-spending taxable sales accumulate their realized gain on
 * {@link TrialPools}; {@link TrialSimulator#ltcgTaxForYear} taxes it with the year's LTCG and prices
 * the LTCG bill's own funding sale. */
class TrialSimulatorTaxSaleGainTest {

    /** taxable 200,000 at 50% embedded gain; traditional 500,000; no Roth. */
    private static TrialPools gainPools() {
        var lots = new TaxableLots();
        lots.addLot(100_000, 200_000);
        return new TrialPools(new double[]{200_000, 500_000, 0, 0, 0}, lots);
    }

    private static TrialPools lossPools() {
        var lots = new TaxableLots();
        lots.addLot(300_000, 200_000);
        return new TrialPools(new double[]{200_000, 500_000, 0, 0, 0}, lots);
    }

    @Test
    void deductTaxFromPools_gainLots_accumulatesTaxSaleGain() {
        var tp = gainPools();

        tp.deductTaxFromPools(10_000);

        assertThat(tp.drainTaxSaleGain()).isEqualTo(5_000.0, within(1e-6));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void sellTaxable_spendingSale_doesNotAccumulate() {
        var tp = gainPools();

        double gain = tp.sellTaxable(10_000);

        assertThat(gain).isEqualTo(5_000.0, within(1e-6));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void sellTaxableForTaxAndRefill_accumulate() {
        var tp = gainPools();

        tp.sellTaxableForTax(4_000);
        tp.debitTaxableWithTraditionalSpillover(6_000);

        assertThat(tp.drainTaxSaleGain()).isEqualTo(5_000.0, within(1e-6));
    }

    @Test
    void ltcgTaxForYear_flatFifteen_includesAccumulatedGainAndOwnFundingSale() {
        // realized 8,000 + accumulated tax-sale gain 2,000 = 10,000 LTCG -> 1,500 at a flat 15%.
        // The 1,500 bill sells 50%-gain lots: T = 0.15 * (10,000 + 0.5 T) -> T = 1500 / 0.925.
        var tp = gainPools();
        tp.sellTaxableForTax(4_000);   // accumulates 2,000 of gain

        double tax = TrialSimulator.ltcgTaxForYear(tp, 8_000, 0, LtcgTaxTable.flat(0.15), 0, 0);

        assertThat(tax).isEqualTo(1500.0 / 0.925, within(0.01));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void ltcgTaxForYear_accumulatedLossExceedsGain_zeroTax() {
        var tp = lossPools();
        tp.sellTaxableForTax(40_000);  // accumulates -20,000

        double tax = TrialSimulator.ltcgTaxForYear(tp, 8_000, 0, LtcgTaxTable.flat(0.15), 0, 0);

        assertThat(tax).isEqualTo(0.0, within(1e-12));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void ltcgTaxForYear_nullTable_zeroTaxAndAccumulatorDrained() {
        var tp = gainPools();
        tp.sellTaxableForTax(4_000);

        double tax = TrialSimulator.ltcgTaxForYear(tp, 8_000, 0, null, 0, 0);

        assertThat(tax).isEqualTo(0.0, within(1e-12));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }
}
```

- [ ] **Step 12b-2: Run; verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=TrialSimulatorTaxSaleGainTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: COMPILATION ERROR, `cannot find symbol: method drainTaxSaleGain()`.

- [ ] **Step 12b-3: Implement the `TrialPools` accumulator**

In `TrialPools.java`, add a field below `lots`:

```java
    /** D5: realized FIFO gain of this trial-year's NON-spending taxable sales (tax cascades,
     * pre-60 conversion-tax sale, cash-reserve seed/refill), drained once per year by
     * {@link TrialSimulator#ltcgTaxForYear}. Negative for loss lots. */
    private double taxSaleGain;
```

Below `sellTaxable`:

```java
    /** D5: {@link #sellTaxable} for a tax-payment or cash-reserve sale -- the realized gain is
     * accumulated for this year's LTCG instead of being discarded. */
    double sellTaxableForTax(double amount) {
        double gain = sellTaxable(amount);
        taxSaleGain += gain;
        return gain;
    }

    /** D5: returns and resets the accumulated tax-sale gain. */
    double drainTaxSaleGain() {
        double drained = taxSaleGain;
        taxSaleGain = 0;
        return drained;
    }

    /** D5: the gain {@link #sellTaxable} WOULD realize for {@code amount}; mutates nothing. */
    double peekTaxableSaleGain(double amount) {
        return lots.peekFifoGain(amount);
    }
```

In `debitTaxableWithTraditionalSpillover`, change `sellTaxable(amount);` to `sellTaxableForTax(amount);`.

In `deductTaxFromPools`, change `lots.sellFifo(taxableSold);` to `taxSaleGain += lots.sellFifo(taxableSold);`. Also update that method's javadoc: replace "the realized gain is a second-order tax-payment sale, deliberately untaxed" with "the realized gain is accumulated for this year's LTCG (D5)".

Also update `sellTaxable`'s javadoc. Replace "several callers deliberately discard it (second-order tax-payment/churn sales are untaxed by design)" with "spending-sale callers tax it directly; tax-payment and cash-reserve sales use {@link #sellTaxableForTax} (D5)".

- [ ] **Step 12b-4: Implement `ltcgTaxForYear` and route the sales**

In `TrialSimulator.java`:

(a) Replace the body of `applyLtcgTax` with:

```java
        double ltcgTax = ltcgTaxForYear(tp, realizedGain, dividendIncome, ltcgTable, ordinaryStack, netRentalIncome);
        if (ltcgTax > 0) {
            deductTaxFromPoolsGrossedUp(ltcgTax, tp, ordinaryTable, ordinaryStack);
        }
        // The LTCG bill's own funding sale was priced inside ltcgTaxForYear; discard what that drain
        // just accumulated so it is not carried into next year.
        tp.drainTaxSaleGain();
```

Add this method directly after `applyLtcgTax`:

```java
    /**
     * D5: this trial-year's LTCG tax on the spending sale's {@code realizedGain} + qualified
     * {@code dividendIncome} + every non-spending taxable sale's accumulated gain (drained from
     * {@code tp}; a net loss floors LTCG income at zero), INCLUDING the gain of the sale that will
     * fund this bill -- closed form {@code sale = tax / (1 - t·g)} from one measured chord, then one
     * polish pass (the hot-loop budget; the deterministic engine polishes up to 3 times). A
     * {@code null} table returns 0 (the accumulator is still drained).
     */
    static double ltcgTaxForYear(TrialPools tp, double realizedGain, double dividendIncome,
                                 @Nullable LtcgTaxTable ltcgTable, double ordinaryStack, double netRentalIncome) {
        double ltcgIncome = Math.max(0, realizedGain + tp.drainTaxSaleGain() + dividendIncome);
        if (ltcgTable == null || ltcgIncome <= 0) {
            return 0;
        }
        double stack = Math.max(0, ordinaryStack);
        double tax = ltcgTable.taxAt(stack, ltcgIncome, netRentalIncome);
        double sale0 = Math.min(tax, Math.max(0, tp.taxable()));
        if (sale0 <= 0) {
            return tax;
        }
        double gain0 = tp.peekTaxableSaleGain(sale0);
        if (gain0 <= 0) {
            return tax;
        }
        double taxPerSaleDollar = (ltcgTable.taxAt(stack, ltcgIncome + gain0, netRentalIncome) - tax) / sale0;
        double warmSale = Math.min(tax / (1 - Math.min(Math.max(taxPerSaleDollar, 0), GROSS_UP_RATE_CAP)),
                Math.max(0, tp.taxable()));
        return ltcgTable.taxAt(stack, ltcgIncome + tp.peekTaxableSaleGain(warmSale), netRentalIncome);
    }
```

`GROSS_UP_RATE_CAP` is the existing 0.5 cap constant in `TrialSimulator`. Add `import org.springframework.lang.Nullable;` if it is not already imported (it is, for `regimeAt`).

(b) In `seedCashReserve`, change `tp.sellTaxable(cashFromTaxable);` to `tp.sellTaxableForTax(cashFromTaxable);`. Update its javadoc: replace "its gain is a pre-retirement carve-out left untaxed" with "its gain is taxed with year 0's LTCG (D5)".

(c) In `applyTrialConversion`, change `tp.sellTaxable(taxPaid);   // conversion-tax sale synced; gain untaxed (second-order)` to `tp.sellTaxableForTax(taxPaid);   // D5: conversion-tax sale gain taxed with this year's LTCG`.

(d) In `simulateTrial`, update the comment above `applyTrialWithdrawals(...)`. Replace "secondary taxable sales — the withdrawal-tax payment and cash-reserve replenishment — sell FIFO to keep the lots in sync but their gain is deliberately discarded (untaxed)" with "secondary taxable sales — the withdrawal-tax payment and cash-reserve replenishment — accumulate their gain on TrialPools for this year's LTCG (D5)".

- [ ] **Step 12b-5: Run the new tests; verify PASS**

Run the Step 12b-2 command. Expected: PASS (6 tests).

- [ ] **Step 12b-6: Run the full module; re-pin moved Monte Carlo literals**

Before running, record the current pinned literals:

```bash
grep -n 'isEqualByComparingTo(new BigDecimal' backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerCharacterizationTest.java
```

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: only seed-pinned Monte Carlo literal tests may fail, for example:
- `MonteCarloSpendingOptimizerCharacterizationTest`
- `StochasticMortalityGoldenTest`
- `JointConversionSearchGatedObjectiveTest`'s `PRE_T26_*` constants

For each failing literal:
- Confirm the direction (median/p10 balances lower or equal, failure rate higher or equal).
- Update it to the reported actual value with a `// D5 (Phase 1a): was <old>` comment.

`JointConversionSearchGatedObjectiveTest`'s toggle-off baseline is described as "pinned permanently" against pre-T26 behavior. That still holds: the toggle-off PATH is unchanged, only the tax model moved. Update its constants the same way, and put a one-line note in that test's javadoc citing D5. Any non-literal (behavioral or ordering) failure is a regression: debug it, never re-pin it.

Re-run. Expected: BUILD SUCCESS.

- [ ] **Step 12b-7: Gates and the app integration tests that exercise the optimizer**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`

Expected: BUILD SUCCESS.

Run: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true -Dit.test='GuardrailControllerIT,Projection*IT'`

Expected: BUILD SUCCESS. If an IT pins a now-moved number, apply the same rule as above: a literal pin moves with a `// D5` comment; a behavioral failure gets debugged.

- [ ] **Step 12b-8: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialPools.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialSimulator.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/
git commit -F - <<'EOF'
fix(projection): tax tax-funding and cash-reserve sale gains in the Monte Carlo

D5 (Phase 1a), Monte Carlo side. TrialPools now accumulates the FIFO gain of
every non-spending taxable sale (tax cascades, pre-60 conversion-tax sale,
cash-reserve seed and refill) instead of discarding it; ltcgTaxForYear drains
it into the year's LTCG income (floored at zero, so losses offset) and prices
the LTCG bill's own funding sale with a closed-form warm start plus one polish
pass. Spending sales are unchanged (already taxed).

Re-pinned Monte Carlo literals (old -> new):
<test#field: old -> new, one line each, from Step 12b-6>

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

Fill in the re-pin block with real values before running the command.

---

### Task 13: D13 — persist conversion settings so reoptimize stops guessing (V081)

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V081__guardrail_profile_conversion_settings.sql`
- Modify: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/GuardrailSpendingProfileEntity.java`
  - add two fields after `gateOnAdaptiveRules` (~:108)
  - add accessors after `setGateOnAdaptiveRules` (~:325)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java`
  - `populateGuardrailEntity` (~:164-205)
  - `reoptimize` (~:279-284)
- Test (create): `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/GuardrailProfileConversionSettingsIntegrationTest.java`
- Test (modify): `backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java`

**Interfaces:**
- Consumes: none from earlier 1a tasks.
- Produces:
  - `GuardrailSpendingProfileEntity.isOptimizeConversions(): boolean` / `setOptimizeConversions(boolean)`
  - `getDynamicSequencingBracketRate(): BigDecimal` / `setDynamicSequencingBracketRate(BigDecimal)`
  - columns `optimize_conversions boolean NOT NULL DEFAULT false` and `dynamic_sequencing_bracket_rate numeric(5,4)`

- [ ] **Step 1: Write the failing repository integration test**

Create `GuardrailProfileConversionSettingsIntegrationTest.java`:

```java
package com.wealthview.persistence.repository;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailProfileConversionSettingsIntegrationTest extends AbstractIntegrationTest {

    private static final String V081 = "db/migration/V081__guardrail_profile_conversion_settings.sql";

    @Autowired
    private GuardrailSpendingProfileRepository repository;

    @Autowired
    private TestEntityManager em;

    private TenantEntity tenant;
    private ProjectionScenarioEntity scenario;

    @BeforeEach
    void setUp() {
        tenant = em.persistAndFlush(new TenantEntity("Tenant A"));
        scenario = em.persistAndFlush(new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{\"birth_year\":1968}"));
    }

    private GuardrailSpendingProfileEntity newProfile() {
        var entity = new GuardrailSpendingProfileEntity(tenant, scenario, "Plan", new BigDecimal("30000"));
        entity.setScenarioHash("hash");
        return entity;
    }

    @Test
    void conversionSettings_roundTrip() {
        var entity = newProfile();
        entity.setOptimizeConversions(true);
        entity.setDynamicSequencingBracketRate(new BigDecimal("0.1200"));

        var saved = repository.saveAndFlush(entity);
        em.clear();
        var reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.isOptimizeConversions()).isTrue();
        assertThat(reloaded.getDynamicSequencingBracketRate()).isEqualByComparingTo("0.1200");
    }

    @Test
    void conversionSettings_defaultToOffAndNull_onLegacyInsert() {
        var saved = repository.saveAndFlush(newProfile());
        em.clear();
        var reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.isOptimizeConversions()).isFalse();
        assertThat(reloaded.getDynamicSequencingBracketRate()).isNull();
    }

    @Test
    void v081Backfill_rowWithConversionSchedule_becomesOptimizeConversionsTrue() throws IOException {
        var withSchedule = newProfile();
        withSchedule.setConversionSchedule("{\"years\":[]}");
        var withoutSchedule = newProfile();
        withoutSchedule.setScenarioHash("hash-2");
        var a = repository.saveAndFlush(withSchedule);
        var b = repository.saveAndFlush(withoutSchedule);

        em.getEntityManager().createNativeQuery(backfillStatement()).executeUpdate();
        em.clear();

        assertThat(repository.findById(a.getId()).orElseThrow().isOptimizeConversions()).isTrue();
        assertThat(repository.findById(b.getId()).orElseThrow().isOptimizeConversions()).isFalse();
    }

    /** The UPDATE statement exactly as committed in V081, so this test pins the shipped SQL. */
    private static String backfillStatement() throws IOException {
        try (InputStream in = GuardrailProfileConversionSettingsIntegrationTest.class.getClassLoader()
                .getResourceAsStream(V081)) {
            assertThat(in).as(V081).isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("UPDATE");
            return sql.substring(start, sql.indexOf(';', start));
        }
    }
}
```

- [ ] **Step 2: Run; verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-persistence -am test -Dtest=GuardrailProfileConversionSettingsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: COMPILATION ERROR, `cannot find symbol: method setOptimizeConversions(boolean)`.

If the persistence module runs its `*IntegrationTest` classes under Failsafe rather than Surefire, use `mvn -q -T1 -pl wealthview-persistence -am verify -Dsurefire.skip=true -Dit.test=GuardrailProfileConversionSettingsIntegrationTest` instead. Check which plugin picks it up with `grep -n "IntegrationTest" backend/wealthview-persistence/pom.xml backend/pom.xml`. The expected failure is the same.

- [ ] **Step 3: Write the migration**

Create `V081__guardrail_profile_conversion_settings.sql`:

```sql
-- V081 (Phase 1a / D13): persist the two Roth-conversion optimizer settings reoptimize needs.
-- Before this, GuardrailProfileService.reoptimize inferred optimize_conversions from
-- traditional_exhaustion_buffer != null -- always true, because every save writes a buffer --
-- so reoptimizing a profile created WITHOUT conversions silently turned them on, and the
-- dynamic-sequencing bracket rate was dropped entirely. Backfill: a profile had conversions
-- enabled exactly when the optimizer produced a conversion schedule for it.
ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS optimize_conversions boolean NOT NULL DEFAULT false;

ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS dynamic_sequencing_bracket_rate numeric(5,4);

UPDATE guardrail_spending_profiles
    SET optimize_conversions = (conversion_schedule IS NOT NULL);

COMMENT ON COLUMN guardrail_spending_profiles.optimize_conversions IS
  'Whether the optimizer ran the joint Roth-conversion search for this profile; echoed by reoptimize.';
COMMENT ON COLUMN guardrail_spending_profiles.dynamic_sequencing_bracket_rate IS
  'Dynamic-sequencing bracket ceiling used for this profile (null = not set); echoed by reoptimize.';
```

- [ ] **Step 4: Add the entity fields and accessors**

In `GuardrailSpendingProfileEntity.java`, after the `gateOnAdaptiveRules` field:

```java
    @Column(name = "optimize_conversions", nullable = false)
    private boolean optimizeConversions = false;

    @Column(name = "dynamic_sequencing_bracket_rate", precision = 5, scale = 4)
    private BigDecimal dynamicSequencingBracketRate;
```

After `setGateOnAdaptiveRules`:

```java
    public boolean isOptimizeConversions() {
        return optimizeConversions;
    }

    public void setOptimizeConversions(boolean optimizeConversions) {
        this.optimizeConversions = optimizeConversions;
    }

    public BigDecimal getDynamicSequencingBracketRate() {
        return dynamicSequencingBracketRate;
    }

    public void setDynamicSequencingBracketRate(BigDecimal dynamicSequencingBracketRate) {
        this.dynamicSequencingBracketRate = dynamicSequencingBracketRate;
    }
```

- [ ] **Step 5: Run the IT; verify PASS**

Run the Step 2 command. Expected: PASS (3 tests). Flyway applies V081 to the Testcontainers database.

If `EntityAccessorRoundtripTest` enumerates accessors reflectively, it should still pass. Run it too: `-Dtest='GuardrailProfileConversionSettingsIntegrationTest,EntityAccessorRoundtripTest,EntityBranchCoverageTest'`.

- [ ] **Step 6: Write the failing service tests**

Add to `GuardrailProfileServiceTest.java`. Imports `ArgumentCaptor` and `GuardrailPhaseInput` are already present or available in `com.wealthview.core.projection.dto`.

```java
    @Test
    void reoptimize_profileSavedWithoutConversions_keepsConversionsOff() {
        var entity = storedProfile();
        entity.setOptimizeConversions(false);
        entity.setTraditionalExhaustionBuffer(5);   // always written -- the old inference read this
        stubReoptimizeHappyPath(entity);
        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        when(spendingOptimizer.optimize(captor.capture())).thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.reoptimize(tenantId, scenarioId);

        assertThat(captor.getValue().optimizeConversions()).isFalse();
    }

    @Test
    void reoptimize_profileSavedWithConversions_echoesConversionsAndDynamicSequencingRate() {
        var entity = storedProfile();
        entity.setOptimizeConversions(true);
        entity.setConversionBracketRate(new BigDecimal("0.22"));
        entity.setRmdTargetBracketRate(new BigDecimal("0.12"));
        entity.setDynamicSequencingBracketRate(new BigDecimal("0.12"));
        stubReoptimizeHappyPath(entity);
        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        when(spendingOptimizer.optimize(captor.capture())).thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.reoptimize(tenantId, scenarioId);

        assertThat(captor.getValue().optimizeConversions()).isTrue();
        assertThat(captor.getValue().dynamicSequencingBracketRate()).isEqualByComparingTo("0.12");
    }

    @Test
    void reoptimize_preservesUsersSavedPhasesExactly() {
        var entity = storedProfile();
        entity.setPhases("""
                [{"name":"Go-go","startAge":62,"endAge":70,"priorityWeight":3,"targetSpending":90000},
                 {"name":"Slow-go","startAge":71,"endAge":80,"priorityWeight":2,"targetSpending":70000},
                 {"name":"No-go","startAge":81,"endAge":null,"priorityWeight":1,"targetSpending":50000}]
                """);
        stubReoptimizeHappyPath(entity);
        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        when(spendingOptimizer.optimize(captor.capture())).thenReturn(baseOptimizerResponse());
        var savedCaptor = ArgumentCaptor.forClass(GuardrailSpendingProfileEntity.class);
        when(guardrailRepository.save(savedCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.reoptimize(tenantId, scenarioId);

        assertThat(captor.getValue().phases()).containsExactly(
                new GuardrailPhaseInput("Go-go", 62, 70, 3, new BigDecimal("90000")),
                new GuardrailPhaseInput("Slow-go", 71, 80, 2, new BigDecimal("70000")),
                new GuardrailPhaseInput("No-go", 81, null, 1, new BigDecimal("50000")));
        assertThat(savedCaptor.getValue().getPhases()).contains("Go-go", "Slow-go", "No-go");
    }

    @Test
    void optimize_persistsOptimizeConversionsAndDynamicSequencingRate() {
        stubOptimizeHappyPath();
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(baseOptimizerResponse());
        var savedCaptor = ArgumentCaptor.forClass(GuardrailSpendingProfileEntity.class);
        when(guardrailRepository.save(savedCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        var request = GuardrailOptimizationRequest.builder()
                .scenarioId(scenarioId)
                .name("Plan")
                .essentialFloor(new BigDecimal("30000"))
                .optimizeConversions(true)
                .conversionBracketRate(new BigDecimal("0.22"))
                .rmdTargetBracketRate(new BigDecimal("0.12"))
                .dynamicSequencingBracketRate(new BigDecimal("0.12"))
                .build();

        service.optimize(tenantId, scenarioId, request);

        assertThat(savedCaptor.getValue().isOptimizeConversions()).isTrue();
        assertThat(savedCaptor.getValue().getDynamicSequencingBracketRate()).isEqualByComparingTo("0.12");
    }

    /** A stored profile with every NOT NULL field populated, ready for reoptimize. */
    private GuardrailSpendingProfileEntity storedProfile() {
        var entity = new GuardrailSpendingProfileEntity(tenant, scenario, "Existing Plan", new BigDecimal("30000"));
        entity.setPhases("[]");
        entity.setYearlySpending("[]");
        entity.setScenarioHash("old-hash");
        entity.setReturnMean(new BigDecimal("0.10"));
        entity.setTrialCount(5000);
        entity.setConfidenceLevel(new BigDecimal("0.95"));
        entity.setTerminalBalanceTarget(BigDecimal.ZERO);
        return entity;
    }
```

If `GuardrailPhaseInput` has a different canonical-constructor arity than `(String, int, Integer, int, BigDecimal)` (confirmed at `GuardrailPhaseInput.java:5-11`), adjust the expected values to match. If `GuardrailOptimizationRequest.builder()` lacks one of these setters, use the method names it declares (see `GuardrailOptimizationRequest.java:36-63`).

- [ ] **Step 7: Run; verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=GuardrailProfileServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL.
- `reoptimize_profileSavedWithoutConversions_keepsConversionsOff`: expected false but was true.
- `reoptimize_profileSavedWithConversions_echoes…`: `dynamicSequencingBracketRate` is null.
- `optimize_persists…`: false / null.

- [ ] **Step 8: Implement**

In `populateGuardrailEntity`, after `entity.setGateOnAdaptiveRules(...)`:

```java
        // D13: persisted so reoptimize echoes the user's actual choice instead of inferring it.
        entity.setOptimizeConversions(optimizationInput.optimizeConversions());
        entity.setDynamicSequencingBracketRate(optimizationInput.dynamicSequencingBracketRate());
```

In `reoptimize`, replace

```java
                .optimizeConversions(existing.getTraditionalExhaustionBuffer() != null)
```

with

```java
                // D13: the persisted flag (V081), not an inference from the always-written buffer.
                .optimizeConversions(existing.isOptimizeConversions())
```

Then replace the two-line comment `// dynamicSequencingBracketRate is intentionally left unset ...` / `// it is not persisted on the profile, ...` with:

```java
                .dynamicSequencingBracketRate(existing.getDynamicSequencingBracketRate())
```

- [ ] **Step 9: Run; verify PASS, then gates**

Run the Step 7 command. Expected: PASS, including every existing `reoptimize_*` test.

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs`

Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit (two commits: migration first, then the fix)**

```bash
git add backend/wealthview-persistence/src/main/resources/db/migration/V081__guardrail_profile_conversion_settings.sql \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/GuardrailSpendingProfileEntity.java \
        backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/GuardrailProfileConversionSettingsIntegrationTest.java
git commit -F - <<'EOF'
db(persistence): add guardrail profile conversion settings columns

V081__guardrail_profile_conversion_settings.sql adds optimize_conversions
(boolean NOT NULL DEFAULT false) and dynamic_sequencing_bracket_rate
(numeric(5,4), nullable) to guardrail_spending_profiles, backfilling
optimize_conversions = (conversion_schedule IS NOT NULL): a profile had
conversions enabled exactly when the optimizer produced a schedule.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java
git commit -F - <<'EOF'
fix(core): reoptimize uses the persisted conversion settings

D13 (Phase 1a). reoptimize inferred optimize_conversions from
traditional_exhaustion_buffer != null, which is always true because every save
writes a buffer -- so reoptimizing a no-conversion profile silently turned
conversions on, and the dynamic-sequencing bracket rate was never echoed.
optimize now persists both (V081) and reoptimize reads them back. A test also
pins that reoptimize preserves the user's saved phases exactly.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 14: D15 — complete the stale-plan / seed signature

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java`: `scenarioSignature` (~:385-430) and its javadoc (~:362-384)
- Test (modify): `backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java`. This replaces `computeScenarioHash_irrelevantParamsDoNotChangeHash` (~:270-279), which asserted the OLD behavior that `filing_status` does not change the hash.

**Interfaces:**
- Consumes (Task 2): `ScenarioParams.birthMonth(): Integer`, `ScenarioParams.spouseBirthMonth(): Integer`.
- Produces: `scenarioSignature` covers `filing_status`, `state`, `other_income`, the resolved `withdrawal_order`, `birth_month` and `spouse_birth_month`. Every existing profile reads as stale once after deploy; no data changes.

- [ ] **Step 1: Write the failing tests (and correct the obsolete one)**

In `GuardrailProfileServiceTest.java`, replace the whole `computeScenarioHash_irrelevantParamsDoNotChangeHash` method with:

```java
    @Test
    void computeScenarioHash_deterministicOnlyParamDoesNotChangeHash() {
        // withdrawal_strategy drives only the deterministic engine's no-plan withdrawal rate; the
        // Monte Carlo never reads it, so it must not invalidate a guardrail profile.
        var scenarioMinimal = ScenarioMother.guardrailScenario(tenant, "Plan", "{\"birth_year\":1968}");
        var scenarioWithExtras = ScenarioMother.guardrailScenario(tenant, "Plan",
                "{\"birth_year\":1968,\"withdrawal_strategy\":\"vanguard_dynamic_spending\"}");

        var hash1 = GuardrailProfileService.computeScenarioHash(scenarioMinimal, List.of());
        var hash2 = GuardrailProfileService.computeScenarioHash(scenarioWithExtras, List.of());

        assertThat(hash1).isEqualTo(hash2);
    }

    static Stream<Arguments> monteCarloAffectingParams() {
        return Stream.of(
                Arguments.of("filing_status", "{\"birth_year\":1968,\"filing_status\":\"single\"}",
                        "{\"birth_year\":1968,\"filing_status\":\"married_filing_jointly\"}"),
                Arguments.of("state", "{\"birth_year\":1968}",
                        "{\"birth_year\":1968,\"state\":\"CA\"}"),
                Arguments.of("other_income", "{\"birth_year\":1968}",
                        "{\"birth_year\":1968,\"other_income\":25000}"),
                Arguments.of("withdrawal_order", "{\"birth_year\":1968,\"withdrawal_order\":\"taxable_first\"}",
                        "{\"birth_year\":1968,\"withdrawal_order\":\"dynamic_sequencing\"}"),
                Arguments.of("birth_month", "{\"birth_year\":1968}",
                        "{\"birth_year\":1968,\"birth_month\":3}"),
                Arguments.of("spouse_birth_month", "{\"birth_year\":1968,\"spouse_birth_year\":1970}",
                        "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"spouse_birth_month\":9}"));
    }

    @ParameterizedTest(name = "{0} changes the hash")
    @MethodSource("monteCarloAffectingParams")
    void computeScenarioHash_monteCarloAffectingParam_changesHash(String field, String before, String after) {
        var hashBefore = GuardrailProfileService.computeScenarioHash(
                ScenarioMother.guardrailScenario(tenant, "Plan", before), List.of());
        var hashAfter = GuardrailProfileService.computeScenarioHash(
                ScenarioMother.guardrailScenario(tenant, "Plan", after), List.of());

        assertThat(hashAfter).as(field).isNotEqualTo(hashBefore);
    }

    @Test
    void computeScenarioHash_absentWithdrawalOrderEqualsExplicitDefault() {
        // The RESOLVED order is hashed, so omitting the key and spelling out the default agree.
        var implicit = ScenarioMother.guardrailScenario(tenant, "Plan", "{\"birth_year\":1968}");
        var explicit = ScenarioMother.guardrailScenario(tenant, "Plan",
                "{\"birth_year\":1968,\"withdrawal_order\":\"taxable_first\"}");

        assertThat(GuardrailProfileService.computeScenarioHash(implicit, List.of()))
                .isEqualTo(GuardrailProfileService.computeScenarioHash(explicit, List.of()));
    }
```

Add imports if absent:
- `java.util.stream.Stream`
- `org.junit.jupiter.params.ParameterizedTest`
- `org.junit.jupiter.params.provider.Arguments`
- `org.junit.jupiter.params.provider.MethodSource`

- [ ] **Step 2: Run; verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test -Dtest=GuardrailProfileServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL on the `filing_status`, `state`, `other_income`, `withdrawal_order`, `birth_month` and `spouse_birth_month` cases ("expected not equal").

- [ ] **Step 3: Implement**

In `scenarioSignature`, extend the params chain after `.append('|').append(hashParams.longevityConditionalAge());`. Replace that terminating line with:

```java
                .append('|').append(hashParams.longevityConditionalAge())
                // D15 (Phase 1a): Monte-Carlo-affecting inputs previously missing from the
                // signature -- tax tables (filing status, state), the ordinary base (other
                // income), pool sequencing (RESOLVED withdrawal order, so an absent key and the
                // explicit default hash alike), and early-access dates (birth months).
                .append('|').append(hashParams.filingStatus())
                .append('|').append(hashParams.state())
                .append('|').append(hashParams.otherIncome())
                .append('|').append(hashParams.resolvedWithdrawalOrder())
                .append('|').append(hashParams.birthMonth())
                .append('|').append(hashParams.spouseBirthMonth());
```

Append one sentence to the method's javadoc paragraph: "Phase 1a (D15) adds filing status, state, other income, the resolved withdrawal order and both birth months; changing the signature re-seeds every profile, so existing profiles read stale once after this ships (a flag only -- no stored data changes)."

- [ ] **Step 4: Run; verify PASS, then gates**

Run the Step 2 command. Expected: PASS.

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs`

Expected: BUILD SUCCESS.

Run the guardrail IT, which exercises staleness end to end: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true -Dit.test=GuardrailControllerIT`.

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/GuardrailProfileServiceTest.java
git commit -F - <<'EOF'
fix(core): include all Monte-Carlo-affecting inputs in the scenario signature

D15 (Phase 1a). scenarioSignature drives both stale-profile detection and the
optimizer seed, but omitted filing_status, state, other_income, the withdrawal
order and (new in 1a) birth_month/spouse_birth_month -- changing any of them
left a stale guardrail plan looking current. The resolved withdrawal order is
hashed so an absent key equals the explicit default. The old test asserting
filing_status does NOT change the hash encoded the defect and is replaced.

Release note: every existing guardrail profile reads stale once after deploy
(the signature changed). This is a flag only; no stored data is modified.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 15: D16 — NIIT threshold deflation uses calendar years from base (Monte Carlo)

The deterministic engine already deflates the NIIT threshold by `year - baseYear` (`PoolStrategy.MultiPool#computeLtcgTax`, ~:1488), so it needs no change. Task 6 already routed `CapitalGainsTaxCalculator.niitThresholdReal` through `RealTermsDeflator`. The Monte Carlo `LtcgTaxTable.computeAll`, however, passes the retirement-anchored `y` as `yearsFromBase`. Social Security thresholds use `retirementYearOffsetFromBase + y` (`OptimizationContextBuilder.java:282`), so the two clocks disagree whenever `baseYear != retirementYear`.

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/LtcgTaxTable.java` (`computeAll` overloads, ~:198-240)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/OptimizationContextBuilder.java:345-346`
- Test (modify): `backend/wealthview-projection/src/test/java/com/wealthview/projection/LtcgTaxTableTest.java`

**Interfaces:**
- Consumes (Task 6): `CapitalGainsTaxCalculator.niitThresholdReal`, already deflating through `RealTermsDeflator`.
- Produces: `LtcgTaxTable.computeAll(CapitalGainsTaxCalculator, FederalTaxCalculator, int retirementYear, int years, FilingStatus, double inflationRate, Integer birthYear, HouseholdContext household, int retirementYearOffsetFromBase): LtcgTaxTable[]`. The 7- and 8-arg overloads delegate with offset 0, which is the old retirement-anchored clock.

- [ ] **Step 1: Write the failing test**

Append to `LtcgTaxTableTest.java`, using the class's existing `capitalGainsCalc()` / `federalTaxCalc()` helpers. Write the test with these values:

```java
    @Test
    void computeAll_retirementYearOffsetFromBase_deflatesNiitOnCalendarClock() {
        // Retirement 10 calendar years after the base year: year index 0 must deflate the fixed
        // nominal NIIT threshold by 10 years (the SAME clock the Social Security thresholds use),
        // not by 0. MAGI 180k sits between the deflated (~$156k) and undeflated ($200k)
        // thresholds, so only the calendar clock charges NIIT.
        var tables = LtcgTaxTable.computeAll(capitalGainsCalc(), federalTaxCalc(),
                2025, 1, FilingStatus.SINGLE, 0.025, null, null, 10);
        var calendarAnchored = LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(),
                2025, FilingStatus.SINGLE, 10, 0.025, -1);
        var retirementAnchored = LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(),
                2025, FilingStatus.SINGLE, 0, 0.025, -1);

        assertThat(tables[0].taxAt(140_000, 40_000)).isEqualTo(calendarAnchored.taxAt(140_000, 40_000));
        assertThat(tables[0].taxAt(140_000, 40_000)).isGreaterThan(retirementAnchored.taxAt(140_000, 40_000));
    }
```

- [ ] **Step 2: Run; verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=LtcgTaxTableTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: COMPILATION ERROR. There is no 9-arg `computeAll`.

- [ ] **Step 3: Implement**

In `LtcgTaxTable.java`, rename the body of the 8-arg `computeAll(..., @Nullable HouseholdContext household)` into a new 9-arg overload. Make the 8-arg version delegate:

```java
    static LtcgTaxTable[] computeAll(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                      @Nullable FederalTaxCalculator federalTaxCalculator,
                                      int retirementYear, int years, FilingStatus filingStatus,
                                      double inflationRate, @Nullable Integer birthYear,
                                      @Nullable HouseholdContext household) {
        return computeAll(capitalGainsTaxCalculator, federalTaxCalculator, retirementYear, years,
                filingStatus, inflationRate, birthYear, household, 0);
    }

    /**
     * D16 (Phase 1a): like the 8-arg overload, but deflates each year's fixed-nominal NIIT threshold
     * by CALENDAR years from the base year ({@code retirementYearOffsetFromBase + y}) -- the same
     * clock {@code OptimizationContextBuilder} uses for the Social Security thresholds and the
     * deterministic engine uses ({@code year - baseYear}). Offset 0 reproduces the old
     * retirement-anchored clock exactly.
     */
    static LtcgTaxTable[] computeAll(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                      @Nullable FederalTaxCalculator federalTaxCalculator,
                                      int retirementYear, int years, FilingStatus filingStatus,
                                      double inflationRate, @Nullable Integer birthYear,
                                      @Nullable HouseholdContext household, int retirementYearOffsetFromBase) {
        LtcgTaxTable[] tables = new LtcgTaxTable[years];
        if (capitalGainsTaxCalculator == null) {
            Arrays.fill(tables, ZERO);
            return tables;
        }
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            int yearsFromBase = Math.max(0, retirementYearOffsetFromBase + y);
            if (household != null) {
                int age = household.filerAgeIn(taxYear);
                Integer secondAge = filingStatus == FilingStatus.MARRIED_FILING_JOINTLY
                        ? household.secondFilerAgeIn(taxYear) : null;
                tables[y] = build(capitalGainsTaxCalculator, federalTaxCalculator, taxYear, filingStatus,
                        yearsFromBase, inflationRate, age, secondAge);
            } else {
                int age = birthYear != null ? taxYear - birthYear : -1;
                tables[y] = build(capitalGainsTaxCalculator, federalTaxCalculator, taxYear, filingStatus,
                        yearsFromBase, inflationRate, age);
            }
        }
        return tables;
    }
```

In `OptimizationContextBuilder.buildRegime` (~:345-346), change the call to pass the offset:

```java
        LtcgTaxTable[] ltcg = LtcgTaxTable.computeAll(capitalGainsTaxCalculator, taxCalculator,
                frame.retirementYear(), frame.years(), status, frame.inflationRate(), frame.birthYear(), household,
                frame.retirementYearOffsetFromBase());
```

- [ ] **Step 4: Run; verify PASS, then the module**

Run the Step 2 command. Expected: PASS.

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: BUILD SUCCESS. A seed-pinned Monte Carlo literal can move only if its fixture has `baseYear != retirementYear` with MAGI near the NIIT threshold. If one moves, re-pin it with a `// D16 (Phase 1a): was <old>` comment, and record it for the commit body.

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/LtcgTaxTable.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/OptimizationContextBuilder.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/
git commit -F - <<'EOF'
fix(projection): deflate the Monte Carlo NIIT threshold on the calendar clock

D16 (Phase 1a). LtcgTaxTable.computeAll deflated the fixed-nominal NIIT
threshold by the retirement-anchored year index, while the Social Security
thresholds (OptimizationContextBuilder) and the deterministic engine use
calendar years from the base year. Whenever retirement is after the base year
the Monte Carlo applied a too-high NIIT threshold. computeAll now takes the
retirement-year offset; the existing overloads delegate with offset 0.

Re-pinned literals (if any): <test#field: old -> new>

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

If no literal moved, replace the re-pin line with "No pinned literals moved." before committing.

---

### Task 16: D4 conversion-table Bracket column, D7 cash-return default

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/GuardrailResponseBuilder.java`: `buildConvScheduleResponse` (~:357-397), plus a new `bracketLabel` helper
- Test (create): `backend/wealthview-projection/src/test/java/com/wealthview/projection/GuardrailResponseBuilderBracketLabelTest.java`
- Modify: `frontend/src/utils/optimizerConfig.ts:59`
- Test (modify): `frontend/src/utils/optimizerConfig.test.ts:17` and `:65`

**Interfaces:**
- Consumes: `OrdinaryTaxTable.rateAt(double)`; `TaxIncomeContext.ordinaryTaxTableByYear()` / `taxableIncomeByYear()`.
- Produces: `ConversionYearDetail.bracketUsed` is a non-null percent string (e.g. `"12%"`) whenever per-year ordinary tables exist. `GuardrailResponseBuilder.bracketLabel(OrdinaryTaxTable, double): String` (package-private static, `@Nullable` return).

- [ ] **Step 1: Write the failing backend test**

Create `GuardrailResponseBuilderBracketLabelTest.java`:

```java
package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** D4 (Phase 1a): the conversion schedule's Bracket column is the marginal rate of the conversion's
 * LAST dollar. Single 2025: $15,000 deduction; 12% ends at $48,475 taxable ($63,475 gross). */
class GuardrailResponseBuilderBracketLabelTest {

    private static OrdinaryTaxTable single2025() {
        var brackets = mock(TaxBracketRepository.class);
        var deductions = mock(StandardDeductionRepository.class);
        stubSingle2025(brackets, deductions);
        return OrdinaryTaxTable.build(new FederalTaxCalculator(brackets, deductions), 2025, FilingStatus.SINGLE, -1);
    }

    @Test
    void bracketLabel_topDollarInTwelvePercent_returnsTwelvePercent() {
        assertThat(GuardrailResponseBuilder.bracketLabel(single2025(), 50_000)).isEqualTo("12%");
    }

    @Test
    void bracketLabel_exactlyAtTwelvePercentCeiling_isStillTwelveNotTwentyTwo() {
        assertThat(GuardrailResponseBuilder.bracketLabel(single2025(), 63_475)).isEqualTo("12%");
    }

    @Test
    void bracketLabel_withinStandardDeduction_returnsZeroPercent() {
        assertThat(GuardrailResponseBuilder.bracketLabel(single2025(), 10_000)).isEqualTo("0%");
    }

    @Test
    void bracketLabel_noTable_returnsNull() {
        assertThat(GuardrailResponseBuilder.bracketLabel(null, 50_000)).isNull();
    }
}
```

- [ ] **Step 2: Run; verify failure**

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am test -Dtest=GuardrailResponseBuilderBracketLabelTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: COMPILATION ERROR, `cannot find symbol: method bracketLabel`.

- [ ] **Step 3: Implement**

In `GuardrailResponseBuilder.java`, add next to `findPhaseName`:

```java
    /** One cent below the conversion's top dollar, so a conversion that exactly fills a bracket
     * reports THAT bracket, not the next one ({@link OrdinaryTaxTable#rateAt} is the rate on the
     * NEXT dollar). */
    private static final double TOP_DOLLAR_EPSILON = 0.01;

    /** D4 (Phase 1a): the marginal federal bracket of a conversion's last dollar as a percent label,
     * e.g. {@code "12%"}; {@code "0%"} when the conversion stays inside the standard deduction;
     * {@code null} when the run has no per-year ordinary tax table. */
    @Nullable
    static String bracketLabel(@Nullable OrdinaryTaxTable table, double grossIncomeWithConversion) {
        if (table == null) {
            return null;
        }
        double rate = table.rateAt(Math.max(0, grossIncomeWithConversion - TOP_DOLLAR_EPSILON));
        return Math.round(rate * 100) + "%";
    }
```

Add `import org.springframework.lang.Nullable;` if it is absent. In `buildConvScheduleResponse`, before the loop, add:

```java
        OrdinaryTaxTable[] ordinaryTables = ctx.taxIncome().ordinaryTaxTableByYear();
```

Then replace the loop body's `ConversionYearDetail` construction so that the gross is computed once and the trailing `null` becomes the label:

```java
            if (convSchedule.conversionByYear()[y] > 0) {
                double grossWithConversion = ctx.taxIncome().taxableIncomeByYear()[y]
                        + convSchedule.conversionByYear()[y];
                convYears.add(new ConversionYearDetail(
                        calendarYear, age,
                        toBD(convSchedule.conversionByYear()[y]),
                        toBD(convSchedule.conversionTaxByYear()[y]),
                        toBD(convSchedule.traditionalBalance()[y]),
                        toBD(convSchedule.rothBalance()[y]),
                        toBD(convSchedule.projectedRmd()[y]),
                        toBD(ctx.taxIncome().incomeByYear()[y]),
                        toBD(grossWithConversion),
                        bracketLabel(ordinaryTables != null ? ordinaryTables[y] : null, grossWithConversion)));
            }
```

- [ ] **Step 4: Run; verify PASS, then the module and gates**

Run the Step 2 command. Expected: PASS (4 tests).

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`

Expected: BUILD SUCCESS. Any existing test asserting `bracketUsed()` is null fails and must be updated to the label. Search with `grep -rn "bracketUsed" backend/*/src/test`.

- [ ] **Step 5: Write the failing frontend test (D7)**

In `frontend/src/utils/optimizerConfig.test.ts`, change line 17 to `expect(config.cashReturnRatePct).toBe(1.5);`. In the "falls back to defaults for absent nullable fields" test, change line 65 to `expect(config.cashReturnRatePct).toBe(1.5);`. Add to the `defaultOptimizerConfig` describe block:

```ts
    it('defaults the cash return to the backend real-terms default (1.5%), not 4%', () => {
        const request = toRequest(defaultOptimizerConfig());

        expect(request.cash_return_rate).toBeCloseTo(0.015);
    });
```

- [ ] **Step 6: Run; verify failure**

Run: `cd frontend && npx vitest run src/utils/optimizerConfig.test.ts`

Expected: FAIL. "expected 4 to be 1.5", and `cash_return_rate` 0.04 is not close to 0.015.

- [ ] **Step 7: Implement**

In `frontend/src/utils/optimizerConfig.ts`, change `cashReturnRatePct: 4,` (line 59) to:

```ts
        // D7 (Phase 1a): REAL return on the cash bucket -- matches the backend default
        // (GuardrailProfileService.DEFAULT_CASH_RETURN_RATE = 0.015). 4% real was optimistic.
        cashReturnRatePct: 1.5,
```

- [ ] **Step 8: Run; verify PASS, plus the page test, typecheck and lint**

Run: `cd frontend && npx vitest run src/utils/optimizerConfig.test.ts src/pages/SpendingOptimizerPage.test.tsx`

Expected: PASS. If a page test asserts the old "4" input value, update it to "1.5".

Run: `cd frontend && npm run typecheck && npm run lint`

Expected: no errors.

- [ ] **Step 9: Commit (two commits)**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/GuardrailResponseBuilder.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/GuardrailResponseBuilderBracketLabelTest.java
git commit -F - <<'EOF'
fix(projection): populate the conversion schedule's Bracket column

D4 (Phase 1a). GuardrailResponseBuilder passed null for every
ConversionYearDetail.bracketUsed, so the UI's Bracket column was always blank.
It now reports the marginal federal bracket of the conversion's last dollar
(read one cent below the top so an exactly-filled bracket reports itself),
from the year's OrdinaryTaxTable.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git add frontend/src/utils/optimizerConfig.ts frontend/src/utils/optimizerConfig.test.ts frontend/src/pages/SpendingOptimizerPage.test.tsx
git commit -F - <<'EOF'
fix(frontend): default the optimizer cash return to 1.5% real

D7 (Phase 1a). The optimizer form defaulted cash_return_rate to 4% and always
sent it, overriding the backend's 1.5% default -- in a real-terms simulation 4%
real on cash is optimistic. Profiles that already store a rate keep it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

If `SpendingOptimizerPage.test.tsx` did not change, drop it from `git add`.

---

### Task 17: Docs — API reference, user guide, engine reference

**Files:**
- Modify: `docs/reference/api-reference.md` (Projections ~:343-375 and Guardrail Profiles ~:377-400)
- Modify: `docs/user-guide/retirement-projections.md` (Scenario Basics ~:95-107, Tax Configuration ~:166-183, Headline Cards ~:241-256, Tabs ~:274-286, Capital Gains ~:315-321)
- Modify: `backend/src/site/markdown/projection-engine.md` (Tax Calculation ~:167-196)

**Interfaces:**
- Consumes: the delivered behavior of Tasks 1–16. Names are from the contract: `birth_month`, `spouse_birth_month`, `heir_tax_rate`, `tax_space`, `terminal_value`, `AgeMilestones`, `TaxSpaceCalculator`, V081.
- Produces: documentation only.

- [ ] **Step 1: API reference**

In `docs/reference/api-reference.md` → `## Projections`:
- In the create/update payload paragraph, after `` `longevity_conditional_age`), `` insert: `` the early-access and legacy fields (`birth_month` and `spouse_birth_month`, 1-12, optional — a month requires the matching birth year; `heir_tax_rate`, 0-0.50, default 0.24), ``.
- Replace the paragraph starting `` `run` returns `` with:

```markdown
`run` returns `scenario_id`, `yearly_data`, `final_balance`, `years_in_retirement`,
`spending_feasibility`, `final_net_worth`, `unclassified_symbols`, `tax_space`,
`terminal_value`, and `warnings` (the last is omitted when empty; `tax_space` is omitted
when there are no retired years).

- `tax_space` — one object per retired year: `year`, `age`, `magi`,
  `marginal_ordinary_rate`, `bracket_room` (list of `{rate, gross_ceiling, room}`),
  `ltcg_zero_room`, `ltcg_fifteen_room`, `provisional_income`, `ss_base_threshold`,
  `ss_upper_threshold`, `ss_inclusion_rate`, `niit_headroom`, `irmaa_premium_year`,
  `irmaa_tier`, `irmaa_room_to_next_tier`, `irmaa_next_tier_annual_cost`,
  `effective_marginal_ordinary`, `effective_marginal_ltcg`. Social Security fields are null
  when no benefit is active; IRMAA fields are null unless someone is 65+ in the premium year
  (`year + 2`).
- `terminal_value` — `year`, `traditional`, `roth`, `taxable`, `heir_tax_rate`,
  `after_tax_legacy` (`traditional × (1 − heir_tax_rate) + roth + taxable`; the taxable pool
  passes at full value because its basis steps up at death), and `at_second_death` (true
  when the household's second death ended the projection early).

`compare` responses are unchanged — they carry neither field.
```

In `## Guardrail Profiles`, append to the paragraph ending "…the service resolves defaults for anything omitted.":

```markdown
`optimize_conversions` and `dynamic_sequencing_bracket_rate` are stored on the profile, and
`reoptimize` re-uses both as saved, along with the saved phases.
```

- [ ] **Step 2: User guide**

In `docs/user-guide/retirement-projections.md`, add these rows to the Scenario Basics table directly under **Birth Year**:

```markdown
| **Birth Month** | Optional. Makes early-access dates exact: the calendar year you reach 59½ is treated as penalty-free for traditional withdrawals (time withdrawals after the date). Without it, penalty-free access starts the year you turn 60. |
```

Under **Tax Configuration**, before "**State**", add:

```markdown
**Heir Tax Rate** — The income-tax rate your heirs are assumed to pay on inherited traditional
(pre-tax) money, which they must withdraw within 10 years. Default 24%. Used only for the
**After-tax Legacy** card.
```

In **Spouse / Household**, add a sentence after the spouse birth-year description: "**Spouse Birth Month** works the same way as yours."

In the **Headline Cards** table, add a row after **Net Worth**:

```markdown
| **After-tax Legacy** | What the portfolio is worth to heirs: traditional balance after the heir tax rate, plus Roth and taxable at full value (taxable basis steps up at death). Property equity is not included. |
```

In the **Tabs** table, add a row after **Income & Tax**:

```markdown
| **Tax Space** | For each retirement year: your marginal bracket and the room left in it, room left in the 0% and 15% capital-gains bands, where you sit in the Social Security taxation zone, distance to the 3.8% NIIT threshold, your IRMAA tier and the room to the next one (for the premium year two years later), and your *effective* marginal rate on the next $1,000 of ordinary income or capital gains — counting federal and state tax, extra Social Security taxation and bracket stacking. Planning estimates, not tax advice. |
```

In **Capital Gains on Taxable Accounts**, append:

```markdown
Selling taxable shares to *pay* a tax bill (for example the tax on a Roth conversion) also
realizes a gain, and that gain is taxed in the same year — including in pre-retirement
conversion years. A sale at a loss offsets the year's gains but never creates negative tax.
```

- [ ] **Step 3: Engine reference**

In `backend/src/site/markdown/projection-engine.md` → `### Tax Calculation`:
- In the obligations table, change the **Early withdrawal penalty** row's Notes to: `Pre-59½ traditional distributions; the year you reach 59½ (from birth year + optional birth month, \`AgeMilestones\`) is penalty-free, age 60 when no month is given`.
- Add a row:

```markdown
| Tax-funding sale gains (D5) | `PoolStrategy.MultiPool#settleTaxSaleGain`, `TrialPools#sellTaxableForTax` / `TrialSimulator#ltcgTaxForYear` | Gain realized by selling taxable lots to pay tax (or to seed/refill the Monte Carlo cash reserve) is LTCG income that year; the paying sale's own gain is solved by a closed-form warm start plus polish passes |
```

After the "**Scope limits, stated plainly.**" paragraph, add:

```markdown
**Tax space and legacy value (Phase 1a).** `runDetailed()` returns, alongside the byte-pinned
result, a `YearTaxPicture` per retired year and the `TerminalValue`. `TaxSpaceCalculator`
(core) turns each picture into bracket/LTCG-band room, SS inclusion zone, NIIT headroom,
IRMAA tier distance and effective marginal rates by re-pricing the year with +$1,000 of
ordinary income / LTCG through the same calculators the engine charges with;
`TaxSpaceReconciliationTest` pins that the recomputed tax equals the charged tax within $1
for every golden scenario. The Monte Carlo deflates the NIIT threshold on the same calendar
clock as the Social Security thresholds (D16).
```

Then run `grep -rn "untaxed\|not itself taxed\|deliberately discarded" docs backend/src/site`. For every hit that describes tax-payment or cash-reserve sale gains, reword it to the D5 behavior: "taxed in the year realized".

- [ ] **Step 4: Verify the docs render sensibly and contain no stale claims**

Run: `grep -n "tax_space\|terminal_value\|heir_tax_rate\|birth_month" docs/reference/api-reference.md docs/user-guide/retirement-projections.md`

Expected: each name appears at least once.

Run: `grep -rn "EARLY_WITHDRAWAL_AGE\|age-60 whole-year proxy" docs backend/src/site`

Expected: no stale references. Reword any hit to describe `AgeMilestones`.

- [ ] **Step 5: Commit**

```bash
git add docs/reference/api-reference.md docs/user-guide/retirement-projections.md backend/src/site/markdown/projection-engine.md
git commit -F - <<'EOF'
docs: document Phase 1a tax space, legacy value, birth month and D5

API reference gains the birth_month / spouse_birth_month / heir_tax_rate inputs
and the run response's tax_space and terminal_value, plus reoptimize's reuse of
the persisted conversion settings. The user guide covers the Birth Month and
Heir Tax Rate fields, the After-tax Legacy card, the Tax Space tab and the
now-taxed tax-funding sale gains; the engine reference covers AgeMilestones,
TaxSpaceCalculator / reconciliation, D5 and the D16 NIIT clock.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```


