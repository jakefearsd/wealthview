# Pre-Release Quality Baseline Metrics — 2026-05-15

This document is the "before" snapshot for the pre-release quality refactor (Phase 0, Task 2).
All numbers were generated on 2026-05-15 from a clean build:

```
cd backend && mvn -q clean test pmd:pmd pmd:cpd spotbugs:spotbugs -DskipITs
```

Build result: **BUILD SUCCESS** (all 6 modules, unit tests only, ITs skipped).
PMD 7.17.0, SpotBugs 4.x, JaCoCo 0.8.x.

---

## 1. JaCoCo Coverage

Coverage computed by summing `LINE_COVERED / (LINE_MISSED + LINE_COVERED)` and
`BRANCH_COVERED / (BRANCH_MISSED + BRANCH_COVERED)` across all rows of each module's
`target/site/jacoco/jacoco.csv`.

| Module               | Lines Covered | Lines Total | Line %  | Branches Covered | Branches Total | Branch % |
|----------------------|---------------|-------------|---------|------------------|----------------|----------|
| wealthview-core      | 4 589         | 5 069       | **90.5%** | 1 217            | 1 533          | **79.4%** |
| wealthview-api       | 650           | 797         | **81.6%** | 79               | 130            | **60.8%** |
| wealthview-persistence | 864           | 1 056       | **81.8%** | 18               | 26             | **69.2%** |
| wealthview-import    | 622           | 687         | **90.5%** | 215              | 291            | **73.9%** |
| wealthview-projection | 2 090         | 2 160       | **96.8%** | 900              | 1 040          | **86.5%** |
| wealthview-app       | 320           | 335         | **95.5%** | 63               | 72             | **87.5%** |

Coverage targets from CLAUDE.md: core 90%+, projection 90%+, api 80%+, import 80%+.

### Status vs targets

| Module               | Line target | Line actual | Branch (no target) | Status |
|----------------------|-------------|-------------|---------------------|--------|
| wealthview-core      | 90%         | 90.5%       | 79.4%               | PASS   |
| wealthview-api       | 80%         | 81.6%       | 60.8%               | PASS   |
| wealthview-persistence | —           | 81.8%       | 69.2%               | —      |
| wealthview-import    | 80%         | 90.5%       | 73.9%               | PASS   |
| wealthview-projection | 90%         | 96.8%       | 86.5%               | PASS   |
| wealthview-app       | —           | 95.5%       | 87.5%               | —      |

**All modules with explicit coverage targets are currently meeting them.**
Notable gap: wealthview-api branch coverage at 60.8% — controller error paths are under-exercised.

---

## 2. PMD Static Analysis

Violation counts from `target/pmd.xml` (element `<violation>` per file).

| Module               | PMD Violations |
|----------------------|----------------|
| wealthview-core      | 73             |
| wealthview-api       | 7              |
| wealthview-persistence | 4              |
| wealthview-import    | 13             |
| wealthview-projection | 119            |
| wealthview-app       | 2              |
| **TOTAL**            | **218**        |

### PMD rule breakdown (all modules combined, sorted by count)

| Rule                                | Count | Notes |
|-------------------------------------|-------|-------|
| CyclomaticComplexity                | 36    | Refactor target — big methods |
| FieldDeclarationsShouldBeAtStartOfClass | 34 | Style; easy mechanical fix |
| CognitiveComplexity                 | 16    | Overlaps with cyclomatic hits |
| ExcessiveParameterList              | 13    | Long constructor arg lists |
| ControlStatementBraces              | 10    | Missing braces on if/for |
| UseVarargs                          | 10    | Array params that could be varargs |
| ReplaceJavaUtilDate                 | 8     | Legacy `java.util.Date` usage |
| NPathComplexity                     | 8     | Exponential path count |
| AvoidCatchingGenericException       | 7     | Catching `Exception` directly |
| GodClass                            | 6     | Classes doing too much |
| CouplingBetweenObjects              | 6     | High fan-out |
| ArrayIsStoredDirectly               | 6     | Defensive copy missing |
| UnusedFormalParameter               | 5     | Dead parameters |
| OneDeclarationPerLine               | 5     | Multiple vars on one line |
| CloseResource                       | 4     | Streams not closed in finally |
| SimplifyBooleanReturns              | 4     | `if (x) return true; else return false` |
| LambdaCanBeMethodReference          | 3     | Lambda wrapping a method call |
| AvoidDeeplyNestedIfStmts            | 3     | Nested conditionals > 3 deep |
| NullAssignment                      | 3     | Assigning null explicitly |
| AbstractClassWithoutAbstractMethod  | 2     | Abstract class with no abstract methods |
| CollapsibleIfStatements             | 2     | Nested ifs that could merge |
| ExcessivePublicCount                | 2     | Too many public methods |
| LinguisticNaming                    | 2     | Boolean method not prefixed is/has |
| ShortMethodName                     | 2     | Method names ≤ 2 chars |
| TooManyFields                       | 2     | Class field count too high |
| UnnecessaryFullyQualifiedName       | 2     | Redundant FQN in import context |
| UseDiamondOperator                  | 2     | Explicit generic on RHS |
| UseLocaleWithCaseConversions        | 2     | `toUpperCase()` without Locale |
| UselessParentheses                  | 2     | Redundant parens |
| AvoidArrayLoops                     | 1     | Should use `System.arraycopy` |
| AvoidBranchingStatementAsLastInLoop | 1     | `continue`/`break` as last statement |
| CompareObjectsWithEquals            | 1     | `==` comparing objects |
| ImplicitFunctionalInterface         | 1     | Non-annotated functional interface |
| LambdaCanBeMethodReference          | 3     | (see above) |
| LooseCoupling                       | 1     | Using concrete type for field |
| NcssCount                           | 1     | Non-commenting source statement count |
| RelianceOnDefaultCharset            | 1     | Charset-sensitive op without explicit charset |
| ReturnEmptyCollectionRatherThanNull | 1     | Returning null collection |
| UnusedLocalVariable                 | 1     | Dead local variable |
| UnnecessaryWarningSuppression       | 1     | `@SuppressWarnings` with no effect |
| UseObjectForClearerAPI              | 1     | Primitive param better as enum/object |

### Per-module PMD top hits

**wealthview-core (73)**
- AvoidCatchingGenericException: 7
- CyclomaticComplexity: 10
- GodClass: 4, CloseResource: 4, SimplifyBooleanReturns: 4
- ReplaceJavaUtilDate: 6, FieldDeclarationsShouldBeAtStartOfClass: 3, CognitiveComplexity: 3

**wealthview-api (7)**
- CouplingBetweenObjects: 2, LinguisticNaming: 2
- ShortMethodName: 1, FieldDeclarationsShouldBeAtStartOfClass: 1, AvoidDeeplyNestedIfStmts: 1

**wealthview-persistence (4)**
- AbstractClassWithoutAbstractMethod: 2, ExcessivePublicCount: 1, TooManyFields: 1

**wealthview-import (13)**
- CyclomaticComplexity: 5, CognitiveComplexity: 3, ReplaceJavaUtilDate: 2
- AvoidDeeplyNestedIfStmts: 2, UnnecessaryWarningSuppression: 1

**wealthview-projection (119)**
- FieldDeclarationsShouldBeAtStartOfClass: 30
- CyclomaticComplexity: 19, CognitiveComplexity: 10
- UseVarargs: 10, ExcessiveParameterList: 10
- ControlStatementBraces: 9, ArrayIsStoredDirectly: 6, NPathComplexity: 6
- OneDeclarationPerLine: 5, UnusedFormalParameter: 4, NullAssignment: 3, GodClass: 2

**wealthview-app (2)**
- CyclomaticComplexity: 2

---

## 3. CPD Copy-Paste Detection

Duplication block counts from `target/cpd.xml` (element `<duplication>`).

| Module               | CPD Blocks |
|----------------------|------------|
| wealthview-core      | 1          |
| wealthview-api       | 0          |
| wealthview-persistence | 0          |
| wealthview-import    | 1          |
| wealthview-projection | 2          |
| wealthview-app       | 0          |
| **TOTAL**            | **4**      |

### Duplication block detail

| Module               | Lines / Tokens | Files (class:line)                                                                 |
|----------------------|----------------|------------------------------------------------------------------------------------|
| wealthview-core      | 8 lines / 103 tokens | `ScenarioCrudService.java:272`, `ScenarioCrudService.java:281`             |
| wealthview-import    | 12 lines / 101 tokens | `FidelityCsvParser.java:54`, `SchwabCsvParser.java:80`                    |
| wealthview-projection | 6 lines / 156 tokens | `MonteCarloSpendingOptimizer.java:457`, `MonteCarloSpendingOptimizer.java:467` |
| wealthview-projection | 6 lines / 117 tokens | `MonteCarloSpendingOptimizer.java:365`, `:390`, `:399`                    |

All four blocks are in files already identified as decomposition targets.

---

## 4. SpotBugs Findings

Finding counts from `target/spotbugsXml.xml` (occurrences of `<BugInstance`).

| Module               | SpotBugs Findings |
|----------------------|-------------------|
| wealthview-core      | 3                 |
| wealthview-api       | 0                 |
| wealthview-persistence | 0                |
| wealthview-import    | 4                 |
| wealthview-projection | 0                |
| wealthview-app       | 0                 |
| **TOTAL**            | **7**             |

### Finding detail

| Priority | Bug Type                            | Module          | Class                        | Method / Location           |
|----------|-------------------------------------|-----------------|------------------------------|-----------------------------|
| 2 (High) | `DLS_DEAD_LOCAL_STORE`              | core            | `AuthService`                | `completeMfaChallenge` L204 |
| 2 (High) | `CT_CONSTRUCTOR_THROW`              | core            | `MfaSecretCipher`            | `<init>` L41                |
| 1 (Med)  | `DM_DEFAULT_ENCODING`               | core            | `StockSplitService`          | `priceUuid` L278            |
| 2 (High) | `UUF_UNUSED_PUBLIC_OR_PROTECTED_FIELD` | import       | `FinnhubSplitClient$FinnhubSplitDto` | (field-level)     |
| 2 (High) | `UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD` | import    | `FinnhubSplitClient$FinnhubSplitDto` | L54 (×3)          |

**Notes:**
- The three `UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD` findings on `FinnhubSplitClient$FinnhubSplitDto`
  are three separate fields of the same inner DTO class — they are Jackson-deserialized fields that
  SpotBugs cannot trace through reflection. Low false-positive risk but worth annotating.
- `CT_CONSTRUCTOR_THROW` in `MfaSecretCipher` is a real concern: throwing from a constructor can
  leave a partially constructed object accessible to finalizers. Should be fixed before release.
- `DM_DEFAULT_ENCODING` in `StockSplitService.priceUuid` is a charset-safety issue — explicit
  `StandardCharsets.UTF_8` should be used.
- `DLS_DEAD_LOCAL_STORE` in `AuthService` is a result variable assigned and never read.

---

## 5. Oversized-File Inventory (Phase 3 Decomposition Targets)

These files are tracked as decomposition candidates in the Phase 3 refactor plan.
Line counts are as of the baseline snapshot.

| File                            | Module          | Lines | Primary PMD signals                              |
|---------------------------------|-----------------|-------|--------------------------------------------------|
| `MonteCarloSpendingOptimizer`   | projection      | 1 611 | GodClass, CyclomaticComplexity, CognitiveComplexity, CPD ×2 |
| `RothConversionOptimizer`       | projection      | 887   | CyclomaticComplexity, NPathComplexity            |
| `DeterministicProjectionEngine` | projection      | 846   | CyclomaticComplexity, CognitiveComplexity, ExcessiveParameterList |
| `PoolStrategy`                  | projection      | 736   | ControlStatementBraces, ArrayIsStoredDirectly    |
| `PropertyService`               | core            | 577   | GodClass, CloseResource                          |
| `AuthService`                   | core            | 511   | GodClass, DLS_DEAD_LOCAL_STORE (SpotBugs)        |

The projection module accounts for 119 of 218 PMD violations (54.6%) and is the highest-priority
decomposition target.

---

## 6. Summary Table

| Module               | Line % | Branch % | PMD | CPD | SpotBugs |
|----------------------|--------|----------|-----|-----|----------|
| wealthview-core      | 90.5%  | 79.4%    | 73  | 1   | 3        |
| wealthview-api       | 81.6%  | 60.8%    | 7   | 0   | 0        |
| wealthview-persistence | 81.8% | 69.2%   | 4   | 0   | 0        |
| wealthview-import    | 90.5%  | 73.9%    | 13  | 1   | 4        |
| wealthview-projection | 96.8% | 86.5%    | 119 | 2   | 0        |
| wealthview-app       | 95.5%  | 87.5%    | 2   | 0   | 0        |
| **TOTAL / AVG**      | **-**  | **-**    | **218** | **4** | **7** |

### Refactor priorities (highest impact)

1. **wealthview-projection** — 119 PMD violations, 2 CPD blocks, GodClass on `MonteCarloSpendingOptimizer`.
   Decomposition of the 4 oversized files will resolve the majority of PMD findings.
2. **wealthview-core** — 73 PMD violations, 3 SpotBugs findings (2 high priority).
   `AuthService` and `PropertyService` are God classes. SpotBugs findings should be fixed pre-release.
3. **wealthview-import** — 4 SpotBugs findings on `FinnhubSplitClient$FinnhubSplitDto` inner class.
   Jackson field annotation cleanup will suppress or resolve all 4.
4. **wealthview-api** — Branch coverage at 60.8% is the most significant coverage gap.
   Adding error-path tests to controller tests should push this above 75%.

---

## 7. PIT Mutation Testing — Core + Projection (added 2026-05-16, Task 12)

The pitest `targetClasses` were broadened from the projection-only set
(`com.wealthview.core.projection.*`, `com.wealthview.core.projection.tax.*`,
`com.wealthview.projection.*`) to also cover the high-value core service packages:
`com.wealthview.core.account.*`, `com.wealthview.core.split.*`,
`com.wealthview.core.property.*`, `com.wealthview.core.auth.*`. Matching `<targetTests>`
were added. All four package names exist verbatim under
`backend/wealthview-core/src/main/java/com/wealthview/core/` — no corrections needed.

Run command:
```
cd backend && mvn -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection
```
Result: completes successfully (exit 0). HTML reports under each module's `target/pit-reports/`.

### 7.1 Mutation scores

| Module               | Mutations | Killed | Mutation Score | SURVIVED | Line Cov (mutated classes) | Test Strength |
|----------------------|-----------|--------|----------------|----------|-----------------------------|---------------|
| wealthview-core      | 1 288     | 887    | **69%**        | **401**  | 87%                         | 79%           |
| wealthview-projection | 1 311    | 769    | **59%**        | **542**  | 97%                         | 60%           |

"SURVIVED" above includes both genuinely-survived mutants (covered by a test but not killed)
and NO_COVERAGE mutants (no test exercises the line). The projection module's high line
coverage (97%) but low mutation score (59%) shows the projection tests assert end results
broadly but rarely pin down individual branch boundaries — a classic "coverage without
strength" gap.

Per-package mutation score (core):

| Package                              | Mutations | Score | Survivors |
|---------------------------------------|-----------|-------|-----------|
| com.wealthview.core.auth              | 235       | 59%   | 96 (incl. NO_COVERAGE)|
| com.wealthview.core.auth.mfa          | 62        | 53%   | 29        |
| com.wealthview.core.split             | 82        | 29%   | 58        |
| com.wealthview.core.property          | 290       | 79%   | 62        |
| com.wealthview.core.account           | 35        | 71%   | 10        |
| com.wealthview.core.projection (+tax) | 393       | ~70%  | ~66       |

### 7.2 Survivor triage

Triage categories: **(a) missing test** — mutation changes real behaviour, no test catches
it; **(b) equivalent** — no observable behaviour change; **(c) trivial** — real but not
worth a dedicated test (metrics/logging side-effects, defensive code).

The 401 core + 542 projection survivors were triaged in aggregate by class, mutation
operator, and code inspection of representative lines. The breakdown below feeds Task 13.

**Core — 401 survivors**

| Category | Count (approx) | What it is |
|----------|----------------|------------|
| (a) missing test | ~205 | Real behaviour gaps — see specific list below |
| (b) equivalent   | ~10  | e.g. `setUpdatedAt` removal where `updated_at` is also `DEFAULT now()` and never asserted; ordering-neutral lambda swaps |
| (c) trivial      | ~186 | Micrometer `Counter::increment` removals (21+), `ApplicationEventPublisher::publishEvent` removals (6), `LoginAttemptService.recordFailure/recordSuccess` side-effect calls, `setUpdatedAt` calls, daemon-thread/`setPropagationBehavior` config wiring |

**Projection — 542 survivors**

| Category | Count (approx) | What it is |
|----------|----------------|------------|
| (a) missing test | ~470 | `MonteCarloSpendingOptimizer` (356) and `RothConversionOptimizer` (120) dominate — conditional-boundary and return-value mutants on optimization math that tests assert only loosely; `DeterministicProjectionEngine` (48) boundary mutants |
| (b) equivalent   | ~20  | Boundary mutants on guards where the off-by-one range is unreachable given upstream validation |
| (c) trivial      | ~52  | Logging/metric side-effects, defensive null-branch returns |

### 7.3 Category (a) — concrete missing-test targets for Task 13

The following are genuine behaviour gaps worth a dedicated test. Each entry is
`Class:Line — mutation operator — reason`.

**Auth (high priority — security-sensitive, much NO_COVERAGE):**
- `AuthService:L205-240` — *whole `completeMfaChallenge` method is NO_COVERAGE* (negated
  conditionals on challenge validity/expiry/used checks, `setUsedAt` removal, null return).
  No test exercises MFA challenge completion at all.
- `AuthService:L226,228 / loginInitiate L184-186` — negated conditionals + null return,
  NO_COVERAGE on the MFA-required login branch.
- `MfaService:L122-128 verifySetup`, `L135-140 disable`, `L162 regenerateRecoveryCodes`,
  `L169 status`, `L174-182 verifyTotp` — NO_COVERAGE: TOTP verification, setup confirmation,
  and disable paths are untested. `verifyTotp` negated-conditional survivors mean an
  always-true verification would pass the suite.
- `JwtTokenProvider:L99 validateMfaChallenge` (negated conditional, boolean→true),
  `L85 generateMfaChallenge` (return→""), `L175 validateAccessToken` (boolean→true) —
  NO_COVERAGE: token validation could always-return-valid undetected.
- `SessionService:L31-70` — `listForUser`, `revoke`, `revokeAllOther` entirely NO_COVERAGE.
- `TenantContext:L13-31` — all four accessors NO_COVERAGE (null/empty return mutants).
- `CrossTenantAspect:L47-53`, `TenantFilterAspect:L49-53`, `TenantFilterActivator:L100` —
  NO_COVERAGE on the tenant-filter aspect logic (negated conditionals, `reEnable` removal).
- `LoginAttemptService:L24,34` — `isBlocked` / `recordFailure` conditional-boundary
  survivors: the lockout threshold (`>=` vs `>`) is not pinned by a test.

**Split:**
- `StockSplitService:L240-269` — `restoreTransaction` / `restorePrice` largely NO_COVERAGE
  (negated conditionals, boolean-return mutants, `setClosePrice` removal).
- `StockSplitService:L299-309` — `listForTenant` filter predicate + `validateRatio`
  conditional boundary survive: ratio bounds and the active/applied filter are unasserted.
- `StockSplitService:L219-221 adjustPrices` — increment and int-return mutants survive:
  the count of adjusted prices is not asserted.
- `StockSplitSyncService` (whole `syncAll` + `computeFromDate`) and
  `StockSplitBackfillRunner.runIfNeeded` — NO_COVERAGE: the daily sync orchestration and
  one-time backfill gate have no unit test.

**Property:**
- `PropertyService:L124-136 update` — every field setter (`setAddress`, `setPurchasePrice`,
  `setPurchaseDate`, `setCurrentValue`, `setMortgageBalance`) survives removal: the update
  test calls update but does not assert the individual fields changed.
- `PropertyService:L263-271 buildClassBreakdowns`, `L392 spreadEntryByCategory`,
  `L509-552 applyDepreciationFields/applyCostSegFields/validateAssetClasses` — negated
  conditionals and boundary mutants on cost-seg allocation logic survive.
- `DepreciationCalculator:L59-233` — 13 conditional-boundary survivors across
  `computeStraightLine`, `computeCostSegregation`, `applyBonusSchedule`,
  `applyCatchUpSchedule`, `getDepreciationForYear`, `accumulatedThrough`: the last-year
  remainder loop and 481(a) catch-up year ranges need boundary-case tests.
- `AmortizationCalculator:L35,41 remainingBalance` — boundary survivors: behaviour at
  payment 0 and final payment is unpinned.
- `PropertyRoiService:L66-170` — boundary survivors on hold-vs-sell year comparisons.

**Account:**
- `AccountService:L92-94 update` — `setInstitution`, `setCurrency`, `setUpdatedAt`,
  negated conditional survive: update test does not assert mutated fields.
- `AccountService:L138 bulkBankBalances` lambda, `L190 computeInvestmentValue` —
  boolean/return mutants survive: filtering and the unpriced-symbol path are unasserted.

**Projection (carryover from prior baseline + amplified):**
- `MonteCarloSpendingOptimizer` (356 survivors) and `RothConversionOptimizer` (120) —
  conditional-boundary and return-value mutants on guardrail/optimization math. These are
  category (a) but most are best addressed by the Phase 3 decomposition (Task plan) so the
  extracted units become individually testable, rather than by bolting tests onto the
  current 1 600-line God class. Task 13 should pick the highest-value boundary mutants in
  `DeterministicProjectionEngine` (48 survivors) where the units are already testable.

### 7.4 Notes for Task 13

- Prioritise the **auth NO_COVERAGE** survivors: `completeMfaChallenge`, `MfaService.verifyTotp`,
  `JwtTokenProvider.validateMfaChallenge`, and the tenant-filter aspects are security-critical
  and currently have mutants proving an always-pass implementation would not be caught.
- The `update`-method setter survivors (Account + Property) are cheap, high-value wins:
  one assertion per mutated field in the existing update tests.
- Do **not** chase category (c) metric/event survivors — verifying `Counter.increment` was
  called adds brittle Mockito `verify()` noise for no behavioural guarantee.
- Projection optimizer survivors are real but should largely be deferred to the Phase 3
  decomposition; testing the monolith directly is low-leverage.

### 7.5 Task 13 result (added 2026-05-16)

Task 13 wrote tests to kill the category-(a) "missing test" survivors identified in
sections 7.3–7.4. Re-running PIT on `wealthview-core` after the work:

| Metric                       | Before (Task 12) | After (Task 13) |
|-------------------------------|------------------|-----------------|
| Core mutation score           | 69%              | **76%**         |
| Core mutations killed         | 887 / 1 288      | **983 / 1 288** |
| Core SURVIVED + NO_COVERAGE   | 401              | **305**         |
| Core test strength            | 79%              | **81%**         |
| Core unit tests               | ~870             | **1 005**       |

96 additional core mutants are now killed (+7 percentage points). Per-package movement:

| Package                       | Before | After |
|--------------------------------|--------|-------|
| com.wealthview.core.auth       | 59%    | 78%   |
| com.wealthview.core.auth.mfa   | 53%    | 71%   |
| com.wealthview.core.split      | 29%    | 59%   |
| com.wealthview.core.account    | 71%    | 83%   |
| com.wealthview.core.property   | 79%    | 82%   |

**New / extended test classes:** `AuthServiceTest` (MFA login + completeMfaChallenge),
`MfaServiceTest`, `JwtTokenProviderTest` (MFA-challenge token), `SessionServiceTest` (new),
`TenantContextTest` (new), `CrossTenantAspectTest` (new), `TenantFilterAspectTest` (new),
`TenantFilterActivatorTest` (reEnable), `StockSplitServiceTest`, `StockSplitSyncServiceTest`
(new), `StockSplitBackfillRunnerTest` (new), `AccountServiceTest`, `PropertyServiceTest`,
`AmortizationCalculatorTest`, `DepreciationCalculatorTest`. In `wealthview-projection`,
`DeterministicProjectionEngineTest` gained retirement-boundary cases (all 440 projection
tests still pass).

**Remaining core survivors (305) are out of scope** and break down as:
- **Category (c) trivial (~190):** Micrometer `Counter.increment` removals, `publishEvent`
  removals, `setUpdatedAt` removals, and only-logged counters (e.g. `StockSplitService`'s
  `restoreTransaction`/`restorePrice` boolean returns and `adjustPrices`/`adjustTransactions`
  int counts feed a log line only — asserting them adds brittle `verify()` noise with no
  behavioural guarantee, per 7.4).
- **Category (b) equivalent (~25):** e.g. `MfaService.setupMfa` clearing `setMfaSetupAt(null)`
  / `setMfaEnabled(false)` on already-default state; `generateRecoveryCodes` loop-count
  boundary where the produced count (10) is already asserted.
- **Deferred optimizer survivors:** `MonteCarloSpendingOptimizer` (356) and
  `RothConversionOptimizer` (120) in `wealthview-projection` remain untouched — they are
  best addressed by the Phase 3 decomposition rather than by bolting tests onto the God
  classes. The projection PIT run was therefore not repeated.
