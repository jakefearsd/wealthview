# Bond Tent Cleanup and Quality Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clear every deferred minor left by bond tent plans 3–5, then run a PMD / SpotBugs / coverage pass over the whole codebase and ratchet the floors.

**Architecture:** Cleanup only — no new user-facing feature. The four open owner decisions (already-retired evaluation timeline, Roth-conversion tax in sizing, V085 scope, guardrail "None") are OUT of scope: do not change their behavior.

**Tech Stack:** Java 25 / Spring Boot 4.1 / JUnit 5 / AssertJ / Testcontainers; React 19 / Vitest / RTL.

**Spec:** `docs/superpowers/specs/2026-10-18-bond-tent-design.md` (the tent behavior these items tidy). The item list comes from the owner-facing backlog of plans 3–5.

## Global Constraints

- CLAUDE.md is binding: TDD (failing test first, keep RED evidence), AssertJ only, `method_state_expected` test names, records for DTOs, no wildcard imports, 120-col lines, conventional commits with a body for feat/fix/refactor/db, trailer `Co-Authored-By: <your model name> <noreply@anthropic.com>`.
- Commit on `main`. Never `git push`. Never edit a released migration (V001–V085). New migration = `V086__...sql`.
- Never run a full-reactor `mvn verify` (OOM). Verify per module: `cd backend && mvn -q -T1 -pl <module> -am verify -DskipITs`. App ITs: `cd backend && mvn -q -T1 -pl wealthview-app -am verify -Dsurefire.skip=true -Dit.test=<IT class>`.
- The six deterministic golden scenarios and the `bond-tent-early-retiree` / `bond-tent-gfc-replay` golden VALUES must not move. Any other numeric test pin that moves must be explained in the report (why the new number is right); a pin that moves without explanation is a defect.
- PMD/CPD/SpotBugs/Checkstyle/JaCoCo gates must pass. A new `@SuppressWarnings("PMD.X")` needs an adjacent comment saying why. Never suppress CPD. Never lower a coverage floor.
- Frontend: `npm run test:coverage`, `npm run typecheck`, `npm run lint` all green from `frontend/`. No `any`.
- Do not change behavior covered by the four open owner decisions listed under Architecture.

## Review Focus

- A float-tolerance change in `TrialTent` flips the tent path on/off for a near-empty tent — the result must equal the no-tent result when the tent is ≤ 1e-9.
- A floored accumulation "before" balance must not change runs whose contributions are all non-negative (the existing pins prove it).
- V086 must only touch profiles whose stored results include a reserve; profiles already stale or without a reserve are untouched.
- Frontend `parseBondTent` on malformed JSON must render the form with the tent off, not crash the page.
- Golden harness: a missing golden file must FAIL the test unless the existing regenerate switch is on.

---

### Task 1: Monte Carlo tent engine fixes

**Files (projection module):** `TrialTent.java`, `TrialSimulator.java` (`accumulate`, `applyTentGrowth`), `TrialTentPlan.java` / `TrialConfigFactory.java` (`planMix()` hash path), and their tests (`TrialTentTest`, `TrialSimulatorTentTest`).

- [ ] `TrialTent.isEmpty()` uses an exact `<= 0`, so ~1e-11 residue keeps the tent path on. Compare against a named tolerance constant (`EMPTY_TOLERANCE = 1e-9`). Test: a tent drained to a 1e-11 residue reports empty, and a trial with such a tent equals the same trial with no tent.
- [ ] `accumulate`'s "before" balance is unfloored, so negative contributions distort the build-up replay's pool return. Floor it at 0 for the return calculation. Test: a negative-contribution year no longer produces a return outside the year's real return; existing non-negative pins do not move.
- [ ] `applyTentGrowth` takes 9 parameters. Introduce a small package-private record for the per-year inputs and pass that. Pure refactor: no pin moves.
- [ ] The `planMix()` hash path is unguarded. Find the hash/lookup that assumes the plan mix exists; make a missing plan mix fail with an `IllegalStateException` naming the scenario state, instead of an NPE / silent wrong mix. Test the failure.
- [ ] Commit per logical change (`fix(projection): ...` / `refactor(projection): ...`).

### Task 2: Monte Carlo tent test gaps (tests only, projection + core)

**Files:** `MonteCarloSpendingOptimizerTest`, `MonteCarloSpendingOptimizerTentTest`, `MonteCarloBondTentCharacterizationTest`, `TrialSimulatorTentTest`, `TrialTentTest`, `BondTentSimulatorTest`/`FixedSpendingTentSimulatorTest` (whichever exists), core `GuardrailProfileServiceTest`.

Each bullet is one new or strengthened test. If a test exposes a real bug, fix it (TDD) and say so in the report.
- [ ] Restore a no-reserve multi-pool + Roth conversions optimizer test in `MonteCarloSpendingOptimizerTest` (deleted with the cash reserve): asserts the conversions run and the result is reproducible for a fixed seed.
- [ ] The reoptimize test in `GuardrailProfileServiceTest` asserts only `phaseBlendYears`; assert every carried-over request field (including the tent target / bond tent config).
- [ ] Extend the monotone sweep (bigger tent ⇒ success not lower, or whatever invariant the existing sweep pins) to build-up > 0 and to a trad + Roth household, not only build-up 0 taxable-only.
- [ ] The characterization check uses the PV identity; add the assertion against `TrialTentSizer.target` directly.
- [ ] Add a test that the joint search uses its own tent paths (beyond the 5669.67 pin): e.g. two searches differing only in tent paths give different results, or a spy/counter on path construction.
- [ ] Non-pooled path without start balances: a test exercising it with a tent.
- [ ] `trackCoverage` seeds `left = total()`: pin it.
- [ ] Simulator-level test for `years <= 0` (retirement already ended / zero horizon).
- [ ] Legacy double-target `configFor` on an empty plan returns 0.0 (not NaN): pin it.
- [ ] M7: a fixed-spending simulator test that FAILS if a guardrail profile's conversion schedule is not threaded through (non-flat returns + a tax calculator so conversions change the outcome).
- [ ] Commit: `test(projection): ...` / `test(core): ...`.

### Task 3: Deterministic tent, pools and replay cleanups (projection + core)

**Files:** `PoolStrategy.java`, `PoolStrategyAssembler.java`, `TentPools.java`, `BondTentPlanner.java`, `BondTentSizer.java`, `TentSetup` (record), core `CrashReplayPaths.java`, the golden harness test class, `src/test/resources` replay golden, and their tests.

- [ ] `PoolStrategy` create groups accounts twice — group once and reuse.
- [ ] `TentPools` stores the unused `PoolReturns.household` — remove it.
- [ ] Type-wide `@SuppressWarnings("PMD.ExcessivePublicCount")` on `PoolStrategy`: make members package-private where every caller is in-package; if the count then passes the threshold drop the suppression, otherwise keep it with an updated comment that states the remaining count and why.
- [ ] `BondTentPlanner.runFor` ignores the passed buildUp's target — use it (or drop the parameter if every caller passes the same value); a test pins whichever is chosen.
- [ ] `BondTentSizer` validates inputs (negative coverage, null/empty mix, negative yields → `IllegalArgumentException` with a clear message). Tests per rule.
- [ ] `TentSetup` compact constructor makes defensive copies (`List.copyOf` / `Map.copyOf`). Test that mutating the source does not change the record.
- [ ] `CrashReplayPaths.buildSlice` throws an unclear exception when the series share no year — throw `IllegalStateException("... no common year ...")`; the slice cache's check-then-put race → `computeIfAbsent` (or `ConcurrentHashMap.computeIfAbsent`). Test the message.
- [ ] Untested: `useSleeveReturns` without a tent setup, and a negative `topUpTent` cap — add tests (fix if they misbehave).
- [ ] Missing reservation tests: a Roth-held reservation, a multi-owner (spouse) reservation, per-owner `forceRmd` with spent sleeves, and pass-one isolation (pass one's state does not leak into pass two of `BondTentPlanner`).
- [ ] Golden harness: a missing golden file currently passes. Make it fail with a message naming the file unless the harness's regenerate switch is set.
- [ ] The replay golden's keys are camelCase because the test uses a default mapper; switch the golden test to the app's snake_case naming and regenerate the file's KEYS only (values identical — prove with a diff in the report).
- [ ] Commits per logical change.

### Task 4: Core / API validation and tests

**Files:** core `ScenarioCrudService` (or wherever bond tent validation runs on save), `GuardrailProfileService`, `BondTentAnalysisService`, api `ProjectionController`/`BondTentController` tests.

- [ ] An unknown `spendingProfileId` still saves with a tent. Saving a scenario whose `spending_profile_id` does not exist in the tenant must be rejected the same way other unknown references are rejected in this service (find and match the existing pattern; likely `EntityNotFoundException` → 404). Test first.
- [ ] Controller test: `@Valid` bean validation on the sizing request body (an invalid body → 400 with the standard envelope).
- [ ] Analysis service tests: the plan gate for a guardrail-only scenario (no spending profile), and replay offset −4 (outside −3..+3) → 400 with the existing message style.
- [ ] Commits: `fix(core): ...`, `test(api): ...`.

### Task 5: Migration V086 and integration tests (persistence + app)

**Files:** `backend/wealthview-persistence/src/main/resources/db/migration/V086__mark_cash_reserve_guardrail_profiles_stale.sql`, `CashReserveConversionIT`, a bond tent scenario IT.

- [ ] V086: `UPDATE guardrail_spending_profiles SET is_stale = true WHERE is_stale = false AND cash_reserve_years > 0;` with a header comment (why: V085 converts only active-plan scenarios; profiles it skipped still hold results computed with a reserve). Idempotent by construction.
- [ ] IT for V086 (Flyway-migrated container): a reserve profile becomes stale, a no-reserve profile does not.
- [ ] IT: saving a scenario with explicit `"bond_tent": null` clears / stores no tent (whatever the service contract is — assert it).
- [ ] IT: end-to-end optimize of a converted scenario (reserve → tent) succeeds and returns a `bond_tent_target`.
- [ ] Rewrite the muddled `migration_defaultCashReserveYears_isZero` IT so its name and assertions say one thing (new rows default `cash_reserve_years` to 0).
- [ ] Run the touched ITs with `-am`. Commits: `db(persistence): ...`, `test(app): ...`.

### Task 6: Frontend cleanups

**Files:** `components/scenario/{ConvertedTentNotice,ScenarioTentSection,parseBondTent}.ts(x)`, `components/ScenarioForm.tsx`, `components/bondTent/{BondTentTab,CrashReplayChart,TentSimulationStrip}.tsx`, the optimizer disclaimer (`OptimizerResultsView`), and co-located tests.

- [ ] The converted-from-cash-reserve notice sits inside collapsed Advanced Settings — render it where the user sees it without expanding (above the section, or force the section open when the notice shows). Test.
- [ ] `parseBondTent` runs `JSON.parse` on every render and throws on malformed JSON — memoize the parse (`useMemo` on the raw string) and return "no tent" on malformed JSON. Test malformed input renders.
- [ ] Notices get `role="status"`. Test by role.
- [ ] "Why it differs from the rule of thumb" shows for the plan mix only — show it for every mix (each mix's own `rule_of_thumb` and reason). Test with two mixes.
- [ ] Mark pre-1954 fallback years on the replay chart (a shaded/labelled "Long-run averages" region for years before the first history year, mirroring the existing 'History ends' marker). Use the replay response's data to know the first history year; if the response does not carry it, derive it from the existing fields and say how in the report. Test.
- [ ] Retired users: when the replay anchor is today (retirement in the past), label offset 0 "Today" instead of "At retirement" and the chart line "Today <year>" instead of "Retire <year>". Labels only — the timeline is an open owner decision. Test.
- [ ] Assert the 'Crash hits' label in the tab test.
- [ ] Clear the start-year validation error as soon as the input becomes valid (it currently lingers until the next replay response). Test.
- [ ] Deduplicate the disclaimer styling shared with `OptimizerResultsView` (one shared component or style constant).
- [ ] Commits: `fix(frontend): ...` / `refactor(frontend): ...` / `test(frontend): ...`.

### Task 7: Docs

- [ ] `docs/reference/api-reference.md`: rewrite the bond tent simulation paragraph so it reads plainly (what it runs, what it returns, the retirement-date 400).
- [ ] `CHANGELOG.md`: add an `[Unreleased]` section listing the user-visible fixes from Tasks 4–6 and V086.
- [ ] Commit: `docs: ...`.

### Task 8: Static analysis pass (whole backend + frontend lint)

- [ ] Run SpotBugs with `includeTests=true` at effort Max, threshold Low, and PMD (project ruleset) + CPD on main AND test sources, module by module. Remember the POM wins over CLI `-Dpmd.rulesets` / `-Dspotbugs.excludeFilterFile`; to vary config, temporarily swap the tracked config file and restore it before committing.
- [ ] Triage against the verified false-positive list (URF on `@Spy` fields; RV/NP inside `assertThatThrownBy`; PMD LiteralsFirstInComparisons on provably non-null receivers; canonical `readLine` AssignmentInOperand; record AvoidFieldNameMatchingMethodName; JPA ImmutableField; bulk VA_FORMAT_STRING_USES_NEWLINE / NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE test noise; DMI_RANDOM_USED_ONLY_ONCE in SustainabilitySearchTest).
- [ ] Fix every actionable finding (dead stores, unwritten fields, uncalled private methods, real nullness bugs). Write a findings table (finding, file:line, action) to the report.
- [ ] `npm run lint` across workspaces with zero warnings; fix any.
- [ ] Commits: `refactor(<module>): ...` / `test(<module>): ...`.

### Task 9: Coverage pass and floor ratchet

- [ ] Backend: per module, `mvn -q -T1 -pl <module> -am verify -DskipITs`, then read `<module>/target/site/jacoco/jacoco.csv`. Rank classes by `BRANCH_MISSED`, not line %.
- [ ] For the top gaps (prioritise money math and the bond tent code), add tests that verify real behavior — never coverage-only tests. If a test finds a bug, fix it TDD.
- [ ] Raise each module's JaCoCo line/branch floor to the new level rounded DOWN to two decimals (never lower).
- [ ] Frontend: `npm run test:coverage`; cover the weakest files touched by bond tent work; raise the four floors in `frontend/vite.config.ts` to the new levels rounded down to an integer.
- [ ] Report a before/after table per module.
- [ ] Commits: `test(<module>): ...`, `chore: ratchet coverage floors`.
