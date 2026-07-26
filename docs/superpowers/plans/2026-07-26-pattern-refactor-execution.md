# Pattern-Refactor Execution Plan — 2026-07-26

Source: pattern-refactoring deep dive 2026-07-25 (6 audit agents, all headline claims verified
first-hand). Register: memory `project_pattern_refactor_audit_2026_07_25.md`. Base commit: 8585106.

## Global Constraints (binding for every task)

**Branch/commits.** Commit directly on `main` — NEVER create branches or worktrees. Conventional
commits (`<type>(<scope>): <summary>` + mandatory body for feat/fix/refactor/db explaining what and
why). Scope = module without `wealthview-` prefix (`core`, `api`, `persistence`, `import`,
`projection`, `app`, `frontend`). One logical change per commit; a task may produce several commits.
End every commit message with:
`Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
**NEVER run `git push`.**

**TDD.** Behavior changes and bug fixes: write the failing test FIRST, watch it fail, then
implement. Pure behavior-preserving refactors: run the covering tests green BEFORE starting, refactor,
run green AFTER — the existing suite is the pin. Never weaken an assertion to get green; if numbers
legitimately move (only Task 1), follow that task's re-pinning protocol.

**Builds (operational lessons — violating these wastes hours).**
- ALL builds/tests FOREGROUND (never `run_in_background`), Bash timeout 600000.
- NEVER run full-reactor `mvn verify` (OOM-kills in this sandbox). Scope to modules.
- Module unit tests: `mvn -f backend/pom.xml -pl wealthview-<module> test -B`
- Single class: append `-Dtest=ClassName`
- After changing an UPSTREAM module (e.g. persistence, core), before testing a dependent module:
  `mvn -f backend/pom.xml install -pl wealthview-<module> -am -DskipTests -B`
- `-Dtest=Foo` SKIPS the quality gates. Any task adding new files or gate-checkable code must run
  `mvn -f backend/pom.xml -pl wealthview-<module> -am verify -DskipITs -B -T1` before committing and
  fix its own gate findings (PMD/CPD/SpotBugs/Checkstyle/JaCoCo all fail the build). New
  suppressions need an adjacent justifying comment. Never lower a coverage floor.
- App-module Testcontainers ITs (only when a task touches them):
  `mvn -f backend/pom.xml verify -pl wealthview-app -B` (Docker is up; can take ~10 min).
- Frontend: `cd frontend && npx vitest run <paths>` for targeted runs, `npm run test` for the suite,
  `npm run lint`. Shared: `npm run test:shared` from repo root.

**House style.** Java 25 / Spring Boot 4.1 / Jackson 3 (`tools.jackson.*` for ObjectMapper;
annotations stay `com.fasterxml.jackson.annotation`). Records for DTOs; static factory
`X.from(entity)`; no Lombok/MapStruct; constructor injection; no wildcard imports; AssertJ only in
tests; test naming `methodUnderTest_stateOrInput_expectedResult`; shared fixtures in
`src/test/java/com/wealthview/<module>/testutil/`; `@MockitoBean` not `@MockBean`. 4-space indent,
max line 120.

**Scope discipline.** Implement exactly the task spec. Audit line numbers are from commit 8585106 —
re-locate by content if drifted. Do NOT touch characterization/golden tests' deliberately-pinned raw
constructors except where a task explicitly says. If the spec conflicts with what you find in the
code, STOP and report NEEDS_CONTEXT with what you found — do not improvise.

---

# Phase A — verified defects

## Task 1: Fix conversion-scoring withdrawal no-op via WithdrawalOrder consolidation

**The verified bug.** `ConversionSimulator.parseWithdrawalOrder()`
(`backend/wealthview-projection/src/main/java/com/wealthview/projection/ConversionSimulator.java`
~line 524) splits `withdrawalOrder` on commas; production feeds it enum tokens:
`OptimizationContextBuilder.java:70` defaults `"taxable_first"` → `PortfolioSetup.withdrawalOrder`
→ `JointConversionSearch.java:67` `.assumptions(...)`. `"taxable_first"` matches no case in
`OrderedWithdrawalStrategy`'s switch (~line 332; `default -> skip`), so for age ≥ 59.5 non-DS runs
the spending withdrawal inside conversion scoring drains nothing and accrues zero withdrawal tax.
Its unit tests all pass comma-lists (e.g. `RothConversionOptimizerTest.java:79`), which is why it
never surfaced.

**Commit 1 (test):** Characterization test pinning the CURRENT buggy behavior: construct a
ConversionSimulator scenario with `withdrawalOrder="taxable_first"`, age ≥ 60, positive spending
need, assert pools are NOT drained and withdrawalTax == 0. Name it to document the bug (e.g.
`selectWithdrawalStrategy_enumTokenOrder_currentlySkipsAllPools_bugPin`). Commit as
`test(projection): pin conversion-scoring no-op for enum withdrawal-order tokens`.

**Commit 2 (fix + consolidation):**
1. Read `wealthview-core/.../core/projection/strategy/WithdrawalOrder.java` (enum with wire tokens
   like `taxable_first`) and `WithdrawalOrderStrategy.java:118-146` (the two switches mapping
   order→pool permutation — this is the authoritative semantics).
2. Add `drawSequence()` to the `WithdrawalOrder` enum returning the pool priority as an array/list of
   the pool tokens (`"taxable"`, `"traditional"`, `"roth"`); `DYNAMIC_SEQUENCING` should throw
   `UnsupportedOperationException` or return the default sequence — match how callers guard it
   (DS is dispatched before ordered strategies at every consumer). Unit-test the mapping for every
   enum constant (this is the failing-test-first step: the test asserts the sequences the
   WithdrawalOrderStrategy switches encode).
3. Consume it at all three duplicate sites: `WithdrawalOrderStrategy`'s permutation switches;
   `TrialSimulator.splitWithdrawal` (~1242-1278, three ~8-line greedy case blocks become one loop
   over the sequence); `ConversionSimulator.parseWithdrawalOrder` → parse via
   `WithdrawalOrder.fromString(...)` (add if absent) and use `drawSequence()`; keep accepting the
   legacy comma-list format ONLY if trivially cheap, otherwise delete it and migrate the unit tests
   that pass comma-lists to enum tokens (they test the strategy, not the format).
4. Flip the commit-1 characterization test to assert the FIXED behavior (pools drained in
   taxable→traditional→roth order, withdrawal tax > 0 when traditional is tapped) and rename it.
5. Run the full projection module suite. EXPECTED: conversion-related characterization/golden values
   may move (spending now depletes pools inside conversion scoring → lower balances, nonzero
   withdrawal tax, possibly different arm choices). Re-pin goldens ONLY after checking each moved
   number's direction is consistent with the fix (balances ≤ before; withdrawal tax ≥ before).
   List every re-pinned value with old→new in your report. If any change is directionally
   inconsistent, STOP and report BLOCKED with the numbers.
   Commit as `fix(projection): draw spending withdrawals in conversion scoring for enum order tokens`
   (body: the token mismatch, the no-op, the consolidation).

**Tests to run:** `mvn -f backend/pom.xml -pl wealthview-projection test -B` and, because
WithdrawalOrder lives in core, `mvn -f backend/pom.xml install -pl wealthview-core -am -DskipTests -B`
first, plus `mvn -f backend/pom.xml -pl wealthview-core test -B`. Gate-check both modules.

## Task 2: Cache overhaul — wire latestPrices, delete dead beans, compose evict annotations

**Verified state.** `CacheConfig.java` (core/config) defines 7 caches; `latestPrices` is evicted at
8 sites but has ZERO `@Cacheable` readers; `taxBrackets`/`standardDeductions` beans are referenced
nowhere (`FederalTaxCalculator` uses private year-keyed `ConcurrentHashMap`s — leave those alone).
The latest-price read idiom `findLatestBySymbolIn(...).stream().collect(toMap(getSymbol,
getClosePrice))` is duplicated at 4 sites: `AccountService.java:154-155` and `:211-212`,
`HoldingService.java:52-53`, `SecurityClassificationService.java:165-166`. AccountService also
internally duplicates its valuation loop (`valueHoldings` :158-170 vs inline in
`computeInvestmentValue` :214-224) and its bulk-map build (:146-156 vs :206-212).

**Spec (3 commits):**
1. `feat(core): LatestPriceLookup component backs the latestPrices cache` — new
   `core/price/LatestPriceLookup` `@Component` with
   `Map<String, BigDecimal> latestFor(Collection<String> symbols)`, `@Cacheable(value =
   "latestPrices", key = "...")` using a canonical key (sort symbols, join). Must be a separate bean
   from its callers so the Spring proxy applies (precedent: `ExchangeRateResolver`). TDD: unit test
   first (repo mock), plus a cache-behavior test if a slice context is cheap — otherwise assert via
   unit test + rely on existing IT coverage. Replace the 4 duplicated map-builds; make
   `computeInvestmentValue` delegate to `bulkLatestPrices`+`valueHoldings` (kill the internal
   duplication).
2. `refactor(core): drop unused taxBrackets/standardDeductions cache beans` — delete the two names
   from CacheConfig (verify zero references first: grep `taxBrackets`, `standardDeductions` outside
   FederalTaxCalculator's private maps).
3. `refactor(core): composed cache-evict annotations` — `@EvictPriceDerivedCaches` (meta-annotation
   bundling `@CacheEvict(value={"latestPrices","accountBalances"}, allEntries=true)`) replacing the
   8 duplicated groups (`PriceService` :60,108,160,171,193; `StockSplitService` :101,146;
   `PriceSyncService` :61); `@EvictExchangeRateCaches` replacing the 3 identical `@Caching(evict=…)`
   groups in `ExchangeRateService` :45-48,70-73,86-89. Annotations live in `core/config`. Leave
   `ImportService`'s programmatic CacheManager evict (:139-142) as-is but add a one-line comment
   only if none exists explaining the self-invocation constraint.

**Tests:** core module suite + gate check. The 8/3 evict sites are annotation-only changes — verify
with the existing service tests plus a targeted grep that no site lost a cache name.

## Task 3: TransactionType enum — one source of truth for the transaction-type closed set

**Verified drift.** The set {buy, sell, dividend, deposit, withdrawal, opening_balance} is declared
in 4 independent places; `CsvTransactionParser.VALID_TYPES` (import module, :28-29) omits
`opening_balance` while `TransactionRequest`'s `@Pattern` (core, :13), the V011 DB CHECK, and
`FidelityPositionsCsvParser` (emits it, :107) include it. ~56 literals across 9 main files,
including a string switch in `HoldingsComputationService` (:108-112) and JPQL literals `'deposit'`
in `TransactionRepository` (:45,54).

**Spec.** New `TransactionType` enum in `wealthview-persistence` (leaf module — visible to all):
constants BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL, OPENING_BALANCE; lowercase wire value via
`@JsonValue` on a `value()` accessor + `@JsonCreator` static `fromValue` (case-tolerant parse,
throws on unknown); JPA `AttributeConverter<TransactionType, String>` (autoApply=false) applied to
`TransactionEntity.type` so the column stays `text` with byte-identical values — NO migration.
Adopt everywhere:
- `TransactionEntity.type` becomes `TransactionType` (converter on the field).
- `TransactionRequest.type`: keep the wire field as the enum (Jackson handles the string); delete
  the `@Pattern` regex; bad values must still yield 400 — verify the existing invalid-type
  controller/IT test still passes (Jackson parse failure → global handler 400) and adjust the
  expected message if needed.
- `HoldingsComputationService` switch → exhaustive enum switch (no default).
- `TransactionRepository` JPQL `'deposit'` literals → `:type` parameter (or enum literal); the
  `computeBalance`/`computeAllBalances` queries take the enum.
- Parsers: broker `ACTION_MAP`s (Fidelity :22-27, Vanguard :22-33, Schwab :23-48), both OFX mappers,
  `FidelityPositionsCsvParser`, and `CsvTransactionParser` map to enum constants;
  `VALID_TYPES` is DELETED — generic CSV validation goes through `TransactionType.fromValue`,
  which FIXES the drift: `opening_balance` becomes importable via generic CSV. Add a
  test for that previously-rejected row (TDD: write it first, watch it fail on current code).
- Anywhere else `grep -rn '"buy"\|"sell"\|"dividend"\|"deposit"\|"withdrawal"\|"opening_balance"'`
  hits main code (dashboard/holdings/etc.), adopt the enum. Test literals may stay strings where
  they exercise the wire format.

**Order of work:** persistence first (`install -pl wealthview-persistence -am -DskipTests`), then
core, then import, then api. Run each module's suite as you go; gate-check all four. This task is
wide — commit per module is acceptable (`db` type is WRONG here; use `refactor(persistence)`,
`refactor(core)`, `refactor(import)`, `fix(import)` for the drift-fix test+behavior). The
opening_balance-via-generic-CSV change is the one behavior change: it gets the failing-test-first
treatment and its own commit or a clearly-called-out section of the import commit body.

## Task 4: TransactionService cleanup — delete dead createWithHash, extract shared persist step

**Verified.** `createWithHash` (:69-85) has zero production callers (only its own tests). `create`
(:50-66), `createWithHash`, `createWithHashNoRecompute` (:88-101) each duplicate the same 4-step
choreography (~8 lines × 3): tenant-scoped account lookup → `new TransactionEntity` → save →
`splitAdjustmentApplier.adjustNewTransaction`.

**Spec (one commit, `refactor(core)`):** delete `createWithHash` + its tests; extract private
`persistTransaction(UUID tenantId, UUID accountId, TransactionRequest request, @Nullable String
importHash)` used by `create` and `createWithHashNoRecompute`; the intentional differences (audit
event on user-create only; recompute deferred on import) stay visible in the two thin public
methods. Existing tests for the two survivors must pass unchanged. JaCoCo: deleting the dead method
plus its tests keeps coverage neutral; run the core gate check to confirm.

## Task 5: API hygiene — one log sanitizer, enveloped 503, typed responses

Three commits in `wealthview-api`:
1. `refactor(api): use LogSanitizer in ImportController` — delete private `sanitizeForLog`
   (:89-94), static-import `LogSanitizer.sanitize` at the ~6 call sites (:54, :69-70, :84). If a
   test asserts the `_`-replacement behavior, update it to the LogSanitizer contract (strip
   `\r\n\t`, null→"").
2. `fix(api): return standard error envelope from stock-split sync when detection unconfigured` —
   `StockSplitController` (:80-86) currently returns bare 503 with empty body via ObjectProvider
   check; throw `ServiceUnavailableException` instead (handled by
   `GlobalExceptionHandler.handleServiceUnavailable` :145-150 → `{error,message,status}`). TDD:
   controller test first asserting the envelope. Check StockSplitIT for a pinned empty-503
   assertion and update it. While there: `PropertyController` duplicates its
   unavailable-message string verbatim (:184-185 vs :205-206) — extract a constant.
3. `refactor(api): typed records replace Map.of response bodies` — `record MfaRequiredResponse`
   (shared by `AuthController` :69 and `AuthMobileController` :75 — place in the api module near the
   auth DTOs or core auth dto package, following where LoginOutcome DTOs live) and
   `record DeletedCountResponse(int deleted)` for `TenantManagementController` :71. Wire format
   must stay identical (snake_case via global Jackson strategy): pin with controller tests first.
   The two `login` methods should keep/or tighten their `ResponseEntity<?>` — do not break the
   success-path body types.

---

# Phase B — main-code refactors

## Task 6: Spending-plan XOR invariant — entity mutators (Tell-Don't-Ask)

The "at most one active spending plan" invariant (CLAUDE.md-critical) is enforced as paired
`setX(v); setY(null)` at 4 sites: `ScenarioCrudService` :173-183 (incl. the subtle None→clear
spending, PRESERVE guardrail case) and `GuardrailProfileService` :121-123, :157-163, :237-240.

**Spec (`refactor(persistence)` + `refactor(core)` or one commit if small):** on
`ProjectionScenarioEntity` add `activateSpendingProfile(SpendingProfileEntity)` (nulls guardrail),
`activateGuardrailProfile(GuardrailSpendingProfileEntity)` (nulls spending),
`clearSpendingProfile()`, `clearGuardrailProfile()`. Javadoc the invariant on the entity. Migrate
the 4 call sites; the None-preserves-guardrail subtlety must keep its current behavior — pin it
with a service test FIRST if none exists. Deprecate or package-private the raw paired setters if
nothing else uses them (grep first; JPA needs no setters).

## Task 7: Core small-tier — PropertyFinance consolidation, isBank(), effective income amount

Three commits in core (+persistence for the entity helpers):
1. `refactor(core): PropertyFinance owns annual mortgage payment and operating expenses` — add
   `annualMortgagePayment(PropertyEntity)` and `annualOperatingExpenses(PropertyEntity)`; replace
   the 7 escaped sites: payment clump at `PropertyRoiService` :141-143, `PropertyAnalyticsService`
   :92-95 (monthly form `PropertyCashFlowService` :134-135 — adapt or leave if the monthly form
   differs semantically; check first); operating-expense triple at `ScenarioCrudService` :485-487,
   `PropertyRoiService` :150-153, `PropertyAnalyticsService` :82-85, `ProjectionInputBuilder`
   :289-290. Unit-test the two new methods first.
2. `refactor(persistence): AccountEntity.isBank() replaces magic-string checks` — 8 sites:
   `AccountService` :122,134,173,185; `DashboardService` :66; `CombinedPortfolioHistoryService`
   :81; `SnapshotProjectionService` :69; `TheoreticalPortfolioService` :57. NOTE: if Task 3 landed
   an AccountType... it did NOT (account types are a different set: bank/brokerage/etc. —
   transaction types only). Just the boolean accessor; no enum.
3. `refactor(core): effectiveAnnualAmount on scenario income link; drop duplicate fetch` —
   `ScenarioIncomeSourceEntity.effectiveAnnualAmount()` replacing the ternary at
   `ScenarioCrudService` :271-272, :290-291 and `ProjectionInputBuilder` :271-272; pass the
   already-fetched income links from `updateScenario` into its response mapping to remove the
   second `findWithIncomeSourceByScenarioId` query per update (verify with the existing service
   tests; add a `verify(repo, times(1))` if cheap).

## Task 8: Import/persistence small-tier — ImportService overloads, TenantScopedRepository

Two commits:
1. `refactor(core): collapse ImportService importCsv overloads; name parser-bean strings` — delete
   the no-format `importCsv` overload (:72-76; `resolveParser` :86-89 already handles null/blank →
   generic); `ImportController` (:55-57) passes nullable `format` straight through; extract
   `PARSER_SUFFIX` (`"CsvParser"`, :90) and `OFX_PARSER_BEAN` (`"ofxParser"`, :100) constants.
   Optional if trivial: a startup assert that expected bean names exist in the injected map.
2. `refactor(persistence): TenantScopedRepository base interface` — `@NoRepositoryBean interface
   TenantScopedRepository<T> extends JpaRepository<T, UUID>` declaring
   `Optional<T> findByTenant_IdAndId(UUID tenantId, UUID id)` and `List<T> findByTenant_Id(UUID)`;
   extend it from the 7-8 repositories that declare these verbatim (Account, SpendingProfile,
   IncomeSource, PropertyExpense, User, Property, ProjectionScenario — grep to confirm the exact
   set and that each entity exposes the `tenant` association path). Repos keep their other methods.
   Full core+persistence suites after (`install -am` first).

## Task 9: Broker CSV parsers — complete the Template Method

**Verified duplication** in `wealthview-import`: `mapAction` in Fidelity (:65-81) and Schwab
(:95-111) identical for ~15/17 lines (Fidelity checks one sign-dependent string, Schwab a Set);
`extractRow` date-guard prologue repeated 3× (~11-12 lines; Schwab silently skips bad dates,
Fidelity/Vanguard emit `CsvRowError` — this difference is INTENTIONAL and must survive as an
explicit hook); Vanguard bypasses base `addTransaction` because the base hardcodes
`"Quantity"`/`"Symbol"` while Vanguard uses `"Shares"`/`"Net Amount"`.

**Spec (`refactor(import)`, one commit):** widen `AbstractBrokerCsvParser` with hooks:
`getDateColumn()`, `getQuantityColumn()`/`getAmountColumn()` (defaults "Quantity"/"Amount" —
check actual base names), `getActionMap()`, `getSignDependentActions()` (default empty; Fidelity
returns its EFT string, Schwab its Set), and a bad-date policy hook (default: emit CsvRowError;
Schwab overrides to skip silently). Move the shared `mapAction` and row prologue into the base;
subclasses become declarative (constants + hook overrides, no logic bodies). Vanguard adopts the
base `addTransaction` via the column hooks. `FidelityPositionsCsvParser` is OUT of scope
(deliberate divergence). All three parser test classes must pass byte-identically — they are the
pin; do NOT edit them in this task (Task 29 restructures them).

## Task 10: UUID primary-key MappedSuperclass ladder

**Verified:** 35 entities each declare `@Id @GeneratedValue(strategy = GenerationType.UUID) private
UUID id;` + identical getter (~210 lines). `CreatedAtEntity` and `Auditable` base classes exist
(timestamps only). Exceptions that must NOT move: `PriceEntity` (composite `PriceId`),
`SystemConfigEntity` (String key), `MobileAppVersionEntity` (String `platform` PK extending
Auditable — this is why id can't live in Auditable itself).

**Spec (`refactor(persistence)`, one commit):** insert `@MappedSuperclass UuidCreatedAtEntity
extends CreatedAtEntity` and `@MappedSuperclass UuidAuditable extends Auditable`, each adding the
id field + getter (one bare `@GeneratedValue` entity exists — normalize to the UUID strategy,
confirming it generates identically). Repoint all 35 entities (audit counted 24 in the Auditable
subtree incl. `AbstractPropertyCashFlowEntity`, 11 elsewhere incl. `MortalityRateEntity`); delete
their id fields/getters. No schema change, no migration, no query change. Run persistence +
core suites, then `install -am` and the app-module IT suite is NOT required for this task alone —
but run at least one @DataJpaTest-backed module test class that saves an entity from EACH ladder to
prove ID generation still works.

## Task 11: Builders for the two mega-records (GuardrailOptimizationRequest / GuardrailOptimizationInput)

**Verified:** `GuardrailOptimizationRequest` (core/projection/dto, 21 components + a 21-arg
back-compat ctor) built positionally at `GuardrailProfileService.reoptimize` :259-288;
`GuardrailOptimizationInput` (~44 components) filled positionally in `buildOptimizationInput`
:496-566, navigable only via inline comments. History already logged one wire-type near-miss here.

**Spec (`refactor(core)`, one commit):** hand-written static nested `Builder` on each record
(no Lombok): fluent setters, `build()` calls the canonical constructor; a static
`builder()` and, where genuinely useful, `builder(GuardrailOptimizationRequest source)` copy-seed.
Precedent: `SimulationConfig.builder(...)`. Migrate the production construction sites
(`reoptimize`, `buildOptimizationInput`, and any controller/service positional sites grep finds in
MAIN code; tests migrate later in Task 18). Delete the 21-arg back-compat constructor if its
remaining callers are migratable in this commit; otherwise leave and note in the report. Records
keep their canonical constructors (wire compat). PMD `ExcessiveParameterList` suppression on the
back-compat ctor should die with it.

## Task 12: Monte Carlo trial-pass assembly — PoolSimSetup + TrialConfigFactory + shared runner

**Verified triplication:** three near-identical ~17-line `SimulationConfig.builder(...)` chains —
`GuardrailResponseBuilder` (private `PoolSimSetup` + `resolvePoolSetup` + `buildSimConfig`
:218-269; trial loops :75-97, :317-326, :371-379), `SustainabilitySearch.runTrials` (:397-429;
`initialTotalBalance` :477-483), `StochasticMortalityEvaluator` (:60-93). The
`simPools ? pool-balances : whole-portfolio-in-taxable` block appears 4× — two sites carry
"mirrors GuardrailResponseBuilder" comments. Drift exists: `.survivorRegimes(...)` only in the
evaluator; `trackYearBalances` absent there.

**Spec (`refactor(projection)`, 1-2 commits):** promote `PoolSimSetup` to a package-level record
with static factories `resolve(PortfolioSetup, double[] conversionByYear)` and the search variant
`resolve(TaxContext, ...)` (read the call sites for exact shapes), incl. `initialTotal()`. Add a
`TrialConfigFactory` built once per pass (from `OptimizationSetup` + `PoolSimSetup` + conversion
arrays) exposing `configFor(trial, adaptation, trackYearBalances, householdOverride)` — the union
of the three chains' knobs; each caller sets only its genuine deltas. Generalize
`SustainabilitySearch.TrialBatch`/`runTrials` into a package-private `TrialPassRunner` consumed by
all three classes. CRITICAL: this is byte-identical-output refactoring — the existing golden /
characterization / integration tests are the pin; run the full projection suite before and after.
The pre-existing `.survivorRegimes`/`trackYearBalances` asymmetries are BEHAVIOR — preserve them
via the factory parameters (do not "fix" the drift by unifying semantics; report if you believe
one is a latent bug). The three NPathComplexity suppressions should become deletable — delete any
suppression your refactor makes unnecessary.

## Task 13: SearchContext recomposition (28 → ~8 components)

**Verified:** `SustainabilitySearch.SearchContext` (:60-74) is a 28-component flattening of
`OptimizationSetup`; re-flattened field-by-field at `MonteCarloSpendingOptimizer.searchContextFor`
(:336-360) and `JointConversionSearch.evalSearchSpending` (:198-217); only ~7 components genuinely
differ between the sites. `PortfolioReturnPaths` record already exists but the 4 return arrays are
threaded individually.

**Spec (`refactor(projection)`, one commit):** recompose as
`record SearchContext(OptimizationSetup setup, PortfolioReturnPaths paths, int trialCount,
TaxContext taxCtx, double[] conversionByYear, double[] conversionTaxByYear,
boolean gateOnAdaptiveRules, double maxAnnualAdjustmentRate)` — adjust to what the two
construction sites actually override (read them first). Accessors delegate so internal
`SustainabilitySearch` code changes minimally. Both constructors collapse to a few lines.
Package-private; behavior-preserving; projection suite is the pin.

## Task 14: PoolStrategy.executeWithdrawals parameter object (+ PoolConfig builder, IncomeSourceProcessor overloads)

**Verified telescoping:** `PoolStrategy.executeWithdrawals` 4 overloads 6→8→9→10 positional args
(:75-155, 8 BigDecimals adjacent); production callers use the 10-arg form
(`RetirementWithdrawalProcessor` :211-213, `YearFinanceResolver` :234-236); 53 test call sites use
narrower overloads. `PoolConfig` (:372-423) has 17 components + 2 back-compat ctors.
`IncomeSourceProcessor.process` has a 9→10→11-arg chain.

**Spec (`refactor(projection)`, 1-2 commits):** introduce `record WithdrawalCycleInputs(...)` with
static factory `of(need, year, age)` + withers (or small builder) for the six tax-context fields;
make `executeWithdrawals(WithdrawalCycleInputs)` the single abstract method; existing overloads
become thin delegating defaults so the 53 test call sites need ZERO churn. Give `PoolConfig` a
builder mirroring `SimulationConfig.Builder`; migrate its single production construction
(`DeterministicProjectionEngine` :293-302) and delete back-compat ctors only if all callers
migrate cheaply. Apply the same thin-delegate treatment to `IncomeSourceProcessor.process`.
Projection suite is the pin; gate-check (new record + builder are gate-checkable code).

## Task 15: OptimizationContextBuilder — RunFrame + regime factory

**Verified:** the quintet {`computeIncomePipeline` → `OrdinaryTaxTable.computeAll` →
`LtcgTaxTable.computeAll` → `computeDsBracketCeilings` → `computeRentalIncomeDelta`} is assembled
3-4× (main flow; stochastic joint phase :421-435; each survivor regime :464-478). The splice shape
(`pre; if (idx<0) return pre; post; splice`) repeats in 4 methods (:296-311, :361-372, :376-387,
:510-521). The clump (retirementYear, retirementAge, years, retirementYearOffsetFromBase,
essentialFloor, inflationRate, birthYear) recurs in 6 signatures; `buildSurvivorRegime` takes 12
params, `buildStochasticEvalArrays` 13.

**Spec (`refactor(projection)`, 1-2 commits):** (a) `record RunFrame(...)` for the 7-param clump;
(b) single `RegimeArrays buildRegime(sources, filingStatus, householdCtx, frame)` factory returning
the quintet as a record; main flow splices two regime results; stochastic path builds joint + two
survivor regimes from the same factory; (c) one generic `spliceByTransition(pre, post, idx)`
replacing the 4 splice methods. Byte-identical outputs; projection suite pins.

## Task 16: Typed pool/owner vocabulary (kill the stringly-typed dispatch)

**Verified:** `ProjectionAccountInput` (core dto) exposes `String accountType()` / `String owner()`;
~20 `POOL_*` string usages + ~12 `"joint"/"primary"/"spouse"` literals across `PoolStrategy`,
`OptimizationContextBuilder`, `HouseholdMcResolver`, `PoolReturnModel` (:50-63 — silent
`default -> taxable`!), `ConversionSimulator`, `OwnerPool`, `ScenarioCrudService`. Typed
vocabularies already exist (`LotOwner`, `PersonId`, `POOL_*` constants) with hand-written bridges.

**Spec (`refactor(core)` + `refactor(projection)`):** add `enum PoolType {TAXABLE, TRADITIONAL,
ROTH}` (wire value lowercase, `fromString` throwing on unknown) in core projection dto package;
promote/reuse `LotOwner` as the owner vocabulary with `fromString`. `ProjectionAccountInput` keeps
its String record components (wire compat) but gains typed accessors `poolType()` / `ownerType()`.
Consumers switch to the typed accessors; group-bys become `EnumMap`; `PoolReturnModel`'s and
`ConversionSimulator`'s silent defaults become exhaustive enum switches (unknown strings now fail
at the input boundary, where `ProjectionInputBuilder`/`ScenarioCrudService` already validate the
same closed sets — verify that validation exists; if a path allows arbitrary strings through
today, report it rather than silently changing behavior). Task 1 already consolidated
withdrawal-order semantics — coordinate: `drawSequence()` may return `PoolType[]` now; update it
if Task 1 landed String tokens (small follow-up commit in this task is fine). Delete
`PoolStrategy.ownerCategory`/`LotOwner.fromCategory` bridges where the enum flows end-to-end.
Both module suites + gates.

## Task 17: TrialSimulator internals — WithdrawalOutcome record + TrialPools type

**Verified:** `applyTrialWithdrawals` (:1133-1209) returns cash but mutates two single-element
out-arrays allocated by the caller (:585-590) — every sibling helper already returns a record.
The `(double[] pools, TaxableLots lots)` clump threads through 15 signatures (11 in TrialSimulator,
4 in `McPools`); the invariant `lots.totalValue() == pools[JOINT_TAXABLE]` is enforced only by
comments (:414-419) and manual re-pinning (`seedTaxableLots` :658).

**Spec (2 commits, `refactor(projection)`):**
1. `record WithdrawalOutcome(double cashBalance, double realizedGain, double traditionalDrawn)`;
   delete the out-arrays.
2. Small mutable per-trial class `TrialPools` (the `double[5]` + `TaxableLots`; ONE allocation per
   trial) absorbing `McPools`' statics as instance methods (`deductCascade`, `debitPair`,
   `forceRmdStreams`, `applyFirstDeathTransition`) and owning the value-invariant at the seams that
   mutate both. This is the HOT LOOP: after green, do a rough perf sanity — run the heaviest MC
   test class before/after (wall-clock within noise, no new per-year allocations inside the year
   loop). If perf degrades measurably, report DONE_WITH_CONCERNS with numbers.
   The chained assignment at ~:541 is deliberate (commented) — leave it.

---

# Phase C — backend test refactors

## Task 18: GuardrailOptimizationInputBuilder (testutil) + migrate 16 files

**Verified:** 57 raw positional constructions of the ~40-component `GuardrailOptimizationInput`
across 18 test files (MonteCarloSpendingOptimizerTest has 28; OptimizationContextBuilderTest 7;
HouseholdMcResolverTest 3; …). Three files invented partial wrappers (telescoping `buildInput` →
`buildInputFull` → `buildInputWithCashBuffer`; `inputWithDividendYield`/`WithFeeRate`/`WithBirthYear`).

**Spec (`test(projection)`, 1-2 commits):** `GuardrailOptimizationInputBuilder` in
`projection/testutil/` — defaults = the de-facto canonical fixture (retire 2030-01-01, birthYear
1968, endAge 90, 3% inflation, one taxable account, confidence 0.95, seed 42L — CONFIRM against the
actual dominant fixture before hardcoding), `with*` for every varied field. If Task 11 gave the
record a production Builder, the test builder wraps/seeds it rather than reimplementing. Migrate
all behavior tests incl. deleting the three local wrapper families. EXCLUDE (raw constructors are
deliberate pins): `MonteCarloSpendingOptimizerCharacterizationTest`, `StochasticMortalityGoldenTest`,
and any file whose comments say they pin the constructor. Full projection suite green; zero
assertion changes.

## Task 19: Scenario fixtures — ScenarioRequestBuilder, ScenarioMother, ScenarioParamsTest parameterization

**Verified:** `ScenarioCrudServiceTest` has 28 raw `new ScenarioRequest(...)` (~44 components) + 6
near-identical null-padding helpers (:103-156); 46 pure-null lines. `ScenarioParamsTest`: 26/38
tests are the mechanical `from_XPresent_passesThrough`/`from_XNull_staysNullForDefault` family.
126 `new ProjectionScenarioEntity(` across test code — 122 in `GuardrailProfileServiceTest` (45),
`ProjectionInputBuilderTest` (37), `ScenarioCrudServiceTest` (32), `ProjectionServiceTest` (8),
mostly the identical `(tenant, "Plan", LocalDate.of(2055,1,1), 90, 0.03, null)` arrange.

**Spec (`test(core)`, 2-3 commits):**
1. `ScenarioRequestBuilder` in `core/testutil/` (defaults "Plan"/2055-01-01/90/0.03); delete the 6
   helpers; migrate the 28 sites.
2. `ScenarioMother` in `core/testutil/`: `scenario(TenantEntity)`,
   `scenarioWithParams(TenantEntity, String paramsJson)` (+ the 2030 guardrail variant used 44× in
   GuardrailProfileServiceTest); migrate ~110 of the 122 sites (leave any that vary structurally).
3. Collapse the ScenarioParamsTest pass-through family into two `@ParameterizedTest` +
   `@MethodSource` methods parameterized by (fieldName, request-customizer, extractor, sample
   value); keep non-mechanical tests as-is. Suite green throughout; test counts may drop — that's
   fine (parameterized cases must cover every field the deleted tests covered; enumerate in report).

## Task 20: GuardrailProfileServiceTest uses its own helpers

**Verified:** helpers exist at the bottom (`simpleProjectionInput()` :1558, `baseOptimizerResponse()`
:1568, `withSchedule()` :1581, `RequestBuilder` :1595-1649) but earlier tests bypass them: 7 inline
copies of the `simpleProjectionInput()` body (:79,138,189,249,388,445,1485); 7 raw 24-arg
`GuardrailProfileResponse` (:93,150,199,262,398,455,812); 9 raw 20-arg
`GuardrailOptimizationRequest` (:114,169,214,233,277,413,615,626,1503); a ~20-line optimize
happy-path stub preamble repeats in ~35 tests.

**Spec (`test(core)`, one commit):** migrate the bypass sites onto the existing helpers; extract
`stubOptimizeHappyPath()`; keep `RequestBuilder` FILE-LOCAL (no promotion — single consumer; if
Task 11 landed a production Builder, RequestBuilder may delegate to it or be replaced by it —
choose whichever leaves this file smallest). Zero assertion changes.

## Task 21: FlatTaxStubs shared fixture

**Verified:** the flat-20% `FederalTaxCalculator` Mockito block (~28 lines: both computeTax
overloads + the bracket-ceiling table 0.10→45000 … else 600000) duplicated across 6 files
(`RothConversionOptimizerTest` :33-61, `RothConversionOptimizerCharacterizationTest` :59-83,
`RothConversionAuditC4BiasDirectionTest`, `JointConversionSearchGatedObjectiveTest`,
`ConversionSimulatorRmdConservationTest`, `MonteCarloSpendingOptimizerTest` :1647-1685) + a 7th
variant (`MultiPoolDeepTest.flatTaxCalc` :109). Drift: some copies `.setScale(4, HALF_UP)`, others
don't.

**Spec (`test(projection)`, one commit):** `FlatTaxStubs` in `projection/testutil/` — `flat20()`,
`flatRate(String)`, `stubBracketCeilings(calc)`; migrate all 7 sites. CAUTION: characterization/
golden tests depend on the stub's exact arithmetic — pick the variant that keeps every existing
golden value unchanged (if the setScale variants genuinely differ in outputs, keep two explicit
factory methods documenting which tests use which; do NOT move any golden number). Document the
caveat in the class javadoc. Suite green with zero golden changes.

## Task 22: Projection testutil bundle — PoolFixtures, retiredAt66Input, yearOf

**Verified:** 37 `new PoolStrategy.PoolConfig(` across 9 files; 6 private `grouped()`/`pool()`
helper clones; the default config literal recurs everywhere. The 6-line "retired at 66, $1M, 4%"
arrange recurs 87× across 7 engine test files (`LocalDate.now().getYear() - 66`).
`HouseholdTransitionTest.yearOf(rows, year)` (:95-97) should be shared (~200 positional
`.yearlyData().get(n)` lookups elsewhere). `MonteCarloSpendingOptimizerTest` has 2 dead imports
(ParameterizedTest/CsvSource).

**Spec (`test(projection)`, 2-3 commits):** (1) `PoolFixtures` (`singleFilerConfig(order)`,
`grouped(taxable, traditional, roth)`, `multiPool(...)`) — coordinate with Task 14's PoolConfig
builder (fixtures should use it); migrate the 6 clone helpers' call sites; file-specific variants
stay local composed on the primitives. (2) `retiredAt66Input(...)` overloads in
`ProjectionTestFixtures`; migrate the 87 sites mechanically. (3) move `yearOf` (+ optional `atAge`)
into `ProjectionTestFixtures`; adopt where tests index by calendar year OPPORTUNISTICALLY (only
clear wins; do not churn every positional lookup); delete the dead imports. Suite green.

## Task 23: PropertyService test harness + PropertyRequest mother

**Verified:** `PropertyServiceCharacterizationTest` :53-88 duplicates `PropertyServiceTest` :49-84
verbatim (6 `@Mock` + 2 `@Spy` + `@InjectMocks` + setUp). `PropertyRequest` (22 components) has 21
raw null-padded constructions in PropertyServiceTest.

**Spec (`test(core)`, one commit):** abstract `PropertyServiceTestSupport` (precedent:
`DeterministicProjectionEngineTestSupport`) holding the mock harness; both classes extend it.
`propertyRequest()`/`propertyRequestWithLoan()` mother methods (local to the test or testutil —
prefer testutil if the characterization test also uses them). KEEP the characterization test's
`goldenRequest()` full construction (deliberate pin). NOTE: the `@Spy` fields are consumed by
`@InjectMocks` reflectively (a known SpotBugs false positive — do not "fix" them). Suite green.

## Task 24: @WealthViewControllerTest composed annotation

**Verified:** the exact line `@Import({SecurityConfig.class, GlobalExceptionHandler.class,
JwtAuthenticationFilter.class, TestMetricsConfig.class})` appears verbatim in 34 api test files;
32 also declare the placeholder `@MockitoBean JwtTokenProvider` + `@MockitoBean
SessionStateValidator` pair (only AuthControllerTest actually stubs them).

**Spec (`test(api)`, one commit):** `@WealthViewControllerTest` composed annotation in
`api/testutil/`: meta-annotated `@WebMvcTest` (controller class via `@AliasFor`), the `@Import`
set, and type-level `@MockitoBean(types = {JwtTokenProvider.class, SessionStateValidator.class})`
(Spring 6.2 feature — Boot 4.1 OK; verify it compiles/runs on the actual version before the sweep).
Sweep all 34 files; AuthControllerTest keeps its field-level mocks (verify type-level + field-level
don't double-register — if they conflict, AuthControllerTest keeps the raw annotations instead of
the composed one; note it in the report). Api suite green.

## Task 25: Error-envelope assertion helpers

**Verified:** `GlobalExceptionHandlerTest` repeats a 4-line statusCode/status/error/message +
assertCounter block (22 assertCounter calls). Controller tests repeat
`.andExpect(status().isX()).andExpect(jsonPath("$.error").value("Y"))` 29× across 15 files.

**Spec (`test(api)`, one commit):** in the handler test a private
`assertErrorEnvelope(response, HttpStatus, String error, String msg)`; in `ControllerTestUtils` a
custom ResultMatcher `errorEnvelope(HttpStatus)` bundling status + `$.error` + `$.status` (per the
CLAUDE.md envelope); adopt at the 29 sites. Zero assertion-strength loss: the matcher must assert
at least what each replaced pair asserted (where a site also asserted `$.message`, keep that line).

## Task 26: ApiClient facade — anon verbs + IT adoption sweep

**Verified:** `it/testutil/ApiClient` exists (exposed as `api` on `AbstractApiIntegrationTest:63`)
but adopted by only PropertyControllerIT + DashboardControllerIT; 300 raw `restTemplate.exchange`
calls across 43 IT files (~127 are the exact authEntity/authHeaders CRUD incantation; top:
StockSplitIT 21, AppVersionCheckIT 21, BearerTokenValidationIT 18, MfaIT 17). 18 `HttpEntity.EMPTY`
anon calls in 4 files. 39 files static-import `TestDataHelper.MAP_TYPE` to feed raw exchanges.

**Spec (`test(app)`, several commits — sweep by package):** (1) add anon variants
(`getAnon`/`postAnon`/`...ForEntity`) to ApiClient. (2) Mechanically convert authenticated CRUD
call sites class by class (commit per it/* package or logical group). Auth-TRANSPORT tests
(bearer/CSRF/cookie mechanics: BearerTokenValidationIT, CsrfProtectionIT-alikes, RefreshToken*,
SessionManagement*) legitimately stay raw — convert only their plain CRUD setup calls, not the
transport-assertion calls. Run each converted class:
`mvn -f backend/pom.xml verify -pl wealthview-app -B -Dit.test=<Class>` (Failsafe include) — or
batch and run the full app IT suite once per commit group. Full app IT suite green at the end.

## Task 27: IT auth fixtures — HttpFixtures, AuthHelper.mobileLogin, TestDataHelper.as(token)

**Verified:** `jsonHeaders()` defined privately in 14 IT files; `bearerHeaders` in 11; local
`tokenLogin()`/`mobileLogin()` re-implementations in 9 files (token-login choreography ×40);
3 files re-implement Set-Cookie parsing AuthHelper owns. TestDataHelper is admin-token-only;
tenant-2 setups hand-roll entity bodies (TenantIsolationFuzzIT :53-98, TenantIsolationIT,
StockSplitIT :156-166 — has the confession comment); property-create body literal ×4, account
`Map.of` ×15.

**Spec (`test(app)`, 2 commits):** (1) `it/testutil/HttpFixtures` (static `jsonHeaders()`,
`bearerHeaders(token)`, `bearerJsonHeaders(token)`) + `AuthHelper.mobileLogin(...)` returning
`TokenPair(access, refresh)` record; migrate the 14/11/9 file families; raw-body negative tests
(malformed JSON) stay raw. (2) `TestDataHelper.as(token)` returning a token-bound instance
(default stays admin) delegating through ApiClient's `*As` methods; collapse the tenant-2 arrange
blocks. App IT suite green.

## Task 28: Split-package IT helpers + shared stub client

**Verified:** StockSplitIT repeats the 7-line apply-split POST ×8 (:64-262 passim) and the
holdings-quantity BigDecimal-from-map extraction ×13 across the split package. Two near-identical
stub clients: `StockSplitSyncIT.StubSplitClient` (:138-166, queue+failure+reset, synchronized) vs
`StockSplitBackfillIT.StubBackfillClient` (:160-175, subset; :34 comments "same stub pattern").

**Spec (`test(app)`, one commit):** `it/testutil/SplitTestSupport` (`applySplit(token, symbol,
date, num, den)`, `holdingQuantity(accountId)`, `holding(accountId, symbol)`) adopted across
StockSplitIT / LateArrivingSplitIT / StockSplitBackfillIT; one
`it/testutil/QueueingSplitDetectionClient` (superset) + shared `@TestConfiguration` replacing both
stubs (keep the cross-thread-visibility comment). Split-package ITs green.

## Task 29: Import-module test structure — OFX envelope builder + parser action tables

**Verified:** `OfxTransactionParserTest` :17-250 — four ~60-line OFX text-block fixtures differing
only in payload (envelope/signon repeated ×4). Parser tests: header line repeated 19× (Schwab),
17× (Vanguard); ~10 (Schwab) / ~9 (Vanguard) structurally identical "action X → type Y" tests.
`it.each`-style consolidation belongs here as `@ParameterizedTest @CsvSource`. NOTE Schwab vs
Vanguard bad-date behavior differs deliberately (silent skip vs error) — keep those as distinct
named tests, NOT parameterized together.

**Spec (`test(import)`, 2 commits):** (1) `ofxEnvelope(String tranList, String secList)` template
method collapsing the 4 fixtures to their ~12-line payloads. (2) per parser class: a private
`csvWithRow(...)`/`row(action, ...)` builder + one `@CsvSource` table for the action→type family;
keep amount-normalization/null-quantity/bad-date specifics as dedicated tests. If Task 9 landed,
parser behavior is unchanged — these tests remain the pin; import suite green with identical
coverage (JaCoCo import floor 0.71 branch / 80% line must hold — parameterization keeps executions).

---

# Phase D — frontend

## Task 30: Wire optimizerConfig into SpendingOptimizerPage

**Verified:** `frontend/src/utils/optimizerConfig.ts` (OptimizerConfig, defaultOptimizerConfig,
fromProfile, toRequest; 149 lines + 235-line test) has ZERO production imports.
`SpendingOptimizerPage.tsx` re-implements it: 24 `useState` (:165-207; 17 are OptimizerConfig
fields), 44-line hydration effect (:209-252), 24-line request assembly (:283-306). Drift: page
sends `dynSeqBracketRate` (:201,:298); `toRequest()` lacks it.

**Spec (`fix(frontend)` then `refactor(frontend)`):** (1) TDD: add `dynSeqBracketRatePct` to
OptimizerConfig/fromProfile/toRequest — failing test in optimizerConfig.test.ts first, matching
the page's current wire behavior exactly (field name, ×100/÷100 direction, omit-when-blank
semantics — read the page code for truth). (2) Refactor the page onto the module: one
`useState<OptimizerConfig>` (or useReducer), `fromProfile()` in the load effect, `toRequest()` in
handleOptimize; delete the inline duplication (~110 lines). The 573-line page test must pass
UNCHANGED — it pins behavior. `npx vitest run src/utils/optimizerConfig.test.ts
src/pages/SpendingOptimizerPage.test.tsx`, then full `npm run test` + `npm run lint`.

## Task 31: useApiMutation adoption — 36 hand-rolled try/toast handlers

**Verified:** 36 `try { await …; toast.success } catch { toast.error }` blocks across 17 files
(UsersSection 4, InviteCodesSection 4, AccountsListPage 3, StockSplitsSection 3,
ExchangeRatesSection 3, 2 each in ProjectionsPage/ImportPage/AccountDetailPage/TenantsSection/
SystemConfigSection/DashboardSection/TransactionForm, 1 in 5 more). Target idiom:
`useApiMutation(fn, { successMessage, onSuccess })` (see PropertyDetailPage ×7, PricesSection ×6).
Hand-rolled versions swallow the server error body and mostly lack busy-state.

**Spec (`refactor(frontend)`, sweep in 2-4 commits by area):** mechanical conversion preserving
each handler's success-toast message and refetch/onSuccess behavior; error toasts now flow through
`extractErrorMessage` (BEHAVIOR IMPROVEMENT: server detail replaces generic strings — co-located
tests asserting the generic error message must be updated to the new expectation, not deleted;
where a test asserted `toast.error('Failed to X')`, assert the extractErrorMessage output for the
mocked rejection). Where `useApiMutation` provides `loading`, wire it to the button's disabled
state ONLY where the component already had a busy flag; do not add new UI states beyond that.
Run each file's co-located test; full `npm run test` at the end.

## Task 32: Shared-workspace dedup — type re-exports, extractErrorMessage, formatDollarAxis

**Verified:** `frontend/src/types/account.ts` ≡ shared `AccountResponse` (7 fields);
`types/dashboard.ts` trio ≡ shared shapes; precedent exists (`types/common.ts`, `utils/format.ts`
are re-export shims). `mobile/src/auth/AuthContext.tsx:96-105` inline `extractErrorMessage` is a
weaker duplicate of `frontend/src/utils/errorMessage.ts`. `chartFormatters.formatDollarAxis` ≡
shared `formatCompactCurrency` minus a nullish branch.

**Spec (`refactor(frontend)` + `refactor(shared)`... use scope `frontend` with body noting shared/
mobile, or split commits per workspace):** (1) make `types/account.ts`/`types/dashboard.ts`
re-export from `@wealthview/shared` (frontend-only types stay); do NOT convert api modules to the
shared factory style (deliberate divergence). (2) move `extractErrorMessage` to `shared/src`
(tests move too), frontend re-export shim, mobile AuthContext consumes it (delete inline copy).
(3) delete `formatDollarAxis`, alias to shared `formatCompactCurrency`. Run
`npm run test:shared && npm run test:frontend && npm run test:mobile` + `npm run typecheck:all`.

## Task 33: ChartTooltip adoption + SegmentedControl extraction

**Verified:** `ChartTooltip` exists (3 adopters) but 5 renderers hand-roll the null-guard + wrapper
(`FlowsChart` :18, `IncomeStreamsChart` :46, `BalanceChart` :125 — all three defined INSIDE the
component body, recreated every render; `PropertyIncomeChart` :158,:194 with divergent style).
Month-name arrays duplicated (`PropertyIncomeChart` :82, `BalanceChart` :123). Three hand-rolled
blue-pill segmented controls: `SpendingOptimizerPage` :140-160, `PropertyIncomeChart` :271-290,
`BalanceChart` :165-190 (same #1976d2 pattern, ~20 lines each).

**Spec (`refactor(frontend)`, 2 commits):** (1) convert the 5 tooltips to `ChartTooltip
renderContent` (hoists them out of render bodies); adopt the shared `tooltipStyle` — the two
divergent-styled tooltips converge to the standard (visual change is INTENDED consistency; note in
commit body); move month-names to `utils/chartFormatters.ts`. (2) `SegmentedControl<T>({options,
value, onChange})` component + adopt at the 3 sites. Component tests for SegmentedControl (it has
logic); tooltip changes covered by existing chart tests — update any that assert the old inline
markup. Full frontend suite + lint.

## Task 34: Shared vi.mock for the API client — api/__mocks__/client.ts

**Verified:** byte-identical ~21-line `vi.hoisted({get,post,put,del})` + `vi.mock('./client', …)` +
`beforeEach` reset preamble in all 20 `frontend/src/api/*.test.ts` files (~420 lines).

**Spec (`test(frontend)`, one commit):** `frontend/src/api/__mocks__/client.ts` exporting the
default-object of `vi.fn()`s (match the real client's surface exactly); each test switches to
factory-less `vi.mock('./client')` + `vi.mocked(...)`; reset via one `beforeEach(vi.clearAllMocks)`
or vitest config `mockReset: true` (prefer config if it doesn't destabilize other suites — check
`vitest.config`). All 20 files green; no assertion changes.

## Task 35: Shared recharts mock

**Verified:** 14 divergent inline `vi.mock('recharts', …)` blocks (7-20 lines, 159 total), each
stubbing a different subset with different capture attributes.

**Spec (`test(frontend)`, one commit):** single `frontend/src/__mocks__/recharts.tsx` covering the
union of primitives, each emitting `data-testid` + `data-chart-data` (superset of the richest
existing stub); the 14 files switch to factory-less `vi.mock('recharts')`; align the few tests that
asserted stub-specific attributes to the shared stub's richer output. Frontend suite green.

## Task 36: Frontend test builders — testutil/builders.ts

**Verified:** `SpendingOptimizerPage.test.tsx` carries two full `GuardrailProfileResponse` literals
(210 lines, :54,:403); `OptimizerResultsView.test.tsx` :30-48 passes `baseResult as any` with an
eslint-disable (no-`any` violation); a proper typed `makeProfile(overrides)` builder exists PRIVATE
to `optimizerConfig.test.ts` :23; `Scenario` fixtures defined 3 independent ways; 15 files restate
scenario-shaped literals.

**Spec (`test(frontend)`, one commit):** `frontend/src/testutil/builders.ts` with `makeProfile`,
`makeSchedule` (moved from optimizerConfig.test), `makeScenario`, `makeAccount`; migrate the
literals; type `baseResult` properly and DELETE the `as any` + eslint-disable. Frontend suite +
lint green (lint proves the no-any violation is gone).

## Task 37: ScenarioForm.test — labeled-input helper + describe.each field pentads

**Verified:** `ScenarioForm.test.tsx` (1034 lines): 16 clone selector helpers (:91-234, identical
8-line body differing only in label); ~17 clone per-field tests (:389-539+, five-behavior pentad
per params field at ~13 lines each). `it.each` used exactly once across all workspaces.

**Spec (`test(frontend)`, one commit):** one `labeledInput(label)` helper replaces the 16 clones;
`describe.each([...fieldSpecs])` table ({label, paramKey, defaultDisplay, hydrateFraction,
submitPct}) generates the pentads; keep genuinely distinct tests standalone. Coverage-equivalent:
every deleted named test must have a generated counterpart (enumerate mapping in report). Suite
green.

---

# Final gate (controller runs, not a task)

Per-module `mvn -pl <module> -am verify -DskipITs -B -T1` for all six backend modules; full app IT
suite; `npm run test:all` + `npm run typecheck:all` + frontend lint; final whole-branch code review
(subagent, most capable model) over 8585106..HEAD; fix wave; memory update.
