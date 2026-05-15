# Pre-Release Quality Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring WealthView to release-grade fit and finish — fix quality findings, harden the test suite into a robust refactoring safety net, decompose oversized files, enforce quality gates, and repair evidenced performance hot spots.

**Architecture:** Risk-tiered sequencing. The test safety net is hardened *before* any structural change, so every risky refactor runs against mutation-validated tests. Within each phase, modules are worked in dependency order: persistence → core → import → projection → api → app.

**Tech Stack:** Java 21 / Spring Boot 3.5 / Maven multi-module; PMD, SpotBugs, JaCoCo, Checkstyle, PIT (pitest); React 19 / Vite / Vitest; PostgreSQL 16 / Testcontainers.

**Spec:** `docs/superpowers/specs/2026-05-15-pre-release-quality-pass-design.md`

**Conventions (from CLAUDE.md — binding):**
- Commit directly on `main`. No feature branches, no worktrees.
- Never `git push` — the user pushes manually.
- TDD: a failing test precedes every production change. Use AssertJ, not JUnit asserts.
- Conventional Commits with a body for `feat`/`fix`/`refactor`/`db`.
- Never use H2 — integration tests use Testcontainers PostgreSQL 16.
- Run unit tests: `cd backend && mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection`
- Run integration tests: `cd backend && mvn verify -pl wealthview-app` (needs Docker).

**Baseline metrics (2026-05-15):** JaCoCo line cov — core 90.5%, projection 96.8%, import 90.5%, app 95.5%, persistence 81.8%, api 81.6%. PMD 218 violations. CPD 5 duplication blocks. SpotBugs 7 findings.

---

## PMD violation inventory (218 total, by rule)

| Count | Rule | Disposition |
|---|---|---|
| 36 | CyclomaticComplexity | Phase 3 (decomposition resolves) |
| 34 | FieldDeclarationsShouldBeAtStartOfClass | Phase 1 — Task 3 |
| 16 | CognitiveComplexity | Phase 3 |
| 13 | ExcessiveParameterList | Phase 3 |
| 10 | ControlStatementBraces | Phase 1 — Task 4 |
| 10 | UseVarargs | Phase 1 — Task 5 |
| 8 | ReplaceJavaUtilDate | Phase 1 — Task 5 |
| 8 | NPathComplexity | Phase 3 |
| 7 | AvoidCatchingGenericException | Phase 1 — Task 6 |
| 6 | CouplingBetweenObjects | Phase 3 |
| 6 | GodClass | Phase 3 |
| 6 | ArrayIsStoredDirectly | Phase 1 — Task 6 |
| 5 | UnusedFormalParameter | Phase 1 — Task 6 |
| 5 | OneDeclarationPerLine | Phase 1 — Task 4 |
| 4 | CloseResource | Phase 1 — Task 6 |
| 4 | SimplifyBooleanReturns | Phase 1 — Task 4 |
| 8 (NPath) / 3 AvoidDeeplyNestedIfStmts / 2 TooManyFields / 2 ExcessivePublicCount | structural | Phase 3 |
| ~30 | misc small rules (UselessParentheses, UseDiamondOperator, LambdaCanBeMethodReference, NullAssignment, CompareObjectsWithEquals, UseLocaleWithCaseConversions, etc.) | Phase 1 — Task 4 / 6 |

Structural rules (CyclomaticComplexity, CognitiveComplexity, NPathComplexity, GodClass, ExcessiveParameterList, TooManyFields, ExcessivePublicCount, AvoidDeeplyNestedIfStmts, CouplingBetweenObjects) are **not** fixed by suppression — they are resolved as a side effect of the Phase 3 decompositions. Any genuine residual after Phase 3 is justified in `pmd-ruleset.xml` with a comment.

---

# Phase 0 — Stabilize baseline

### Task 1: Commit the pending working-tree changes

**Files:** working tree only — `wv`, `bin/wv`, `bin/wv-lib/*`, `bin/wv.conf.example`, `.gitignore`, `CLAUDE.md`, `README.md`, `docs/deployment/operations.md`, `.wv-previous-image`, `scripts/test/wv.bats`.

- [ ] **Step 1: Inspect the pending changes**

Run: `git status && git diff --stat HEAD`
Review the `wv` script reorganization (`scripts/wv-lib` → `bin/wv-lib`, new `bin/wv-lib/restart.sh`, new `bin/wv.conf.example`, root `wv` shim).

- [ ] **Step 2: Decide coherence**

If the reorg is internally consistent (the `wv` dispatcher resolves `bin/wv-lib/`, the bats tests reference the new paths, no dangling references to `scripts/wv-lib`), proceed to Step 3. If it is half-finished, STOP — report exactly what is incomplete and ask the user before continuing.

Verify no dangling refs: `grep -rn "scripts/wv-lib" --include="*.sh" --include="*.bats" --include="*.md" . | grep -v docs/superpowers`
Expected: no results (or only intentional historical mentions).

- [ ] **Step 3: Run the wv bats tests**

Run: `bats scripts/test/wv.bats` (or `cd scripts/test && bats wv.bats`)
Expected: all pass. If bats is not installed, note it and skip — do not block.

- [ ] **Step 4: Commit the `wv` reorganization**

```bash
git add wv bin/ .gitignore scripts/test/wv.bats .wv-previous-image
git rm -r --cached scripts/wv-lib 2>/dev/null || true
git commit -m "chore: relocate wv admin tooling from scripts/ to bin/

Moves the wv dispatcher and its subcommand libraries under bin/ so the
tool can be installed standalone (/usr/local/bin/wv) without the source
tree. Adds bin/wv.conf.example and a restart subcommand. The root ./wv
becomes a thin shim.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 5: Commit the documentation updates**

```bash
git add CLAUDE.md README.md docs/deployment/operations.md
git commit -m "docs: update wv operations docs for bin/ relocation

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 6: Verify clean tree**

Run: `git status --porcelain`
Expected: empty (only the plan/spec docs from this skill remain, which are already committed).

---

### Task 2: Write the baseline metrics report

**Files:**
- Create: `docs/quality/2026-05-15-baseline-metrics.md`

- [ ] **Step 1: Generate fresh reports**

Run: `cd backend && mvn -q clean test pmd:pmd pmd:cpd spotbugs:spotbugs -DskipITs`
Expected: BUILD SUCCESS. Reports land in each module's `target/`.

- [ ] **Step 2: Write the report**

Create `docs/quality/2026-05-15-baseline-metrics.md` documenting, per module: JaCoCo line + branch coverage (from `target/site/jacoco/jacoco.csv`), PMD violation count, CPD blocks, SpotBugs findings. Include the oversized-file inventory (`MonteCarloSpendingOptimizer` 1611, `RothConversionOptimizer` 887, `DeterministicProjectionEngine` 846, `PoolStrategy` 736, `PropertyService` 577, `AuthService` 511). This is the before-snapshot to compare against at the end of Phase 5.

- [ ] **Step 3: Commit**

```bash
git add docs/quality/2026-05-15-baseline-metrics.md
git commit -m "docs: capture pre-release quality baseline metrics

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

# Phase 1 — Safe wins (low risk, behavior-preserving)

### Task 3: Fix the 7 SpotBugs findings

**Files (confirmed + to-be-enumerated):**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/auth/AuthService.java` (DLS_DEAD_LOCAL_STORE — dead store to `transport`, ~line 204)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/auth/mfa/MfaSecretCipher.java` (CT_CONSTRUCTOR_THROW — constructor, ~line 41)
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/split/StockSplitService.java` (DM_DEFAULT_ENCODING — `String.getBytes()`, ~line 278)
- Modify: 4 findings in `wealthview-import` — enumerate in Step 1.

- [ ] **Step 1: Enumerate all findings**

Run: `cd backend && for m in wealthview-*/target/spotbugsXml.xml; do echo "== $m =="; grep -oE "<BugInstance[^>]*type='[A-Z_]+'" "$m"; done`
List every finding with its class and line (from the `<SourceLine>` elements in the XML).

- [ ] **Step 2: Fix DM_DEFAULT_ENCODING / RelianceOnDefaultCharset**

For each `getBytes()` / `new String(byte[])` call, add an explicit charset: `getBytes(StandardCharsets.UTF_8)`. In `StockSplitService.priceUuid`, the UUID is derived from a string — UTF-8 is the correct deterministic choice. Add `import java.nio.charset.StandardCharsets;`.

- [ ] **Step 3: Fix DLS_DEAD_LOCAL_STORE**

For each dead store, remove the unused assignment. If the right-hand side has a side effect, keep the call but drop the assignment; if it is pure, delete the line. Verify the variable is genuinely never read afterward before deleting.

- [ ] **Step 4: Fix CT_CONSTRUCTOR_THROW**

For `MfaSecretCipher` and any other constructor that throws: make the class `final` if not already (a final class cannot be subclassed for a finalizer attack — this is the SpotBugs-sanctioned fix and is behavior-preserving). Confirm `final` does not break any subclass or proxy — `MfaSecretCipher` is a `@Component`; Spring uses CGLIB only for `@Configuration`/`@Transactional` proxying, not plain components, so `final` is safe here. Verify by checking for subclasses: `grep -rn "extends MfaSecretCipher" backend/`.

- [ ] **Step 5: Run affected module tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-import`
Expected: BUILD SUCCESS, all tests pass (no behavior change).

- [ ] **Step 6: Re-run SpotBugs to confirm zero**

Run: `cd backend && mvn -q spotbugs:spotbugs -pl wealthview-core,wealthview-import && for m in wealthview-core wealthview-import; do echo -n "$m: "; grep -oc "<BugInstance" "$m/target/spotbugsXml.xml" || echo 0; done`
Expected: 0 findings in both modules.

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "fix: resolve SpotBugs findings across core and import

Adds explicit UTF-8 charset to byte conversions (DM_DEFAULT_ENCODING),
removes dead local stores (DLS_DEAD_LOCAL_STORE), and makes cipher
classes final to close the constructor-throw finalizer-attack vector
(CT_CONSTRUCTOR_THROW). All behavior-preserving.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: PMD — mechanical layout fixes (FieldDeclarationsShouldBeAtStartOfClass)

**Files:** 34 violations, concentrated in `RothConversionOptimizer.java` (24), `RmdCalculator.java` (6), `DepreciationCalculator.java` (2), + 2 others. Enumerate in Step 1.

- [ ] **Step 1: Enumerate**

Run: `cd backend && python3 -c "import xml.etree.ElementTree as ET,glob;[print(fe.get('name').split('/src/main/java/')[-1], v.get('beginline')) for f in glob.glob('wealthview-*/target/pmd.xml') for fe in ET.parse(f).getroot() for v in fe if v.get('rule')=='FieldDeclarationsShouldBeAtStartOfClass']"`

- [ ] **Step 2: Move field declarations to class top**

For each file, move all field declarations above the constructors and methods, preserving order and grouping (static finals first, then instance fields). This is purely a source-ordering change — no semantic effect.

- [ ] **Step 3: Run module tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-projection`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "style: move field declarations to class top (PMD)

Resolves 34 FieldDeclarationsShouldBeAtStartOfClass violations.
Source-ordering only, no behavior change.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: PMD — small style fixes (braces, declarations, parentheses, diamonds)

**Rules:** ControlStatementBraces (10), OneDeclarationPerLine (5), SimplifyBooleanReturns (4), UselessParentheses (2), UseDiamondOperator (2), UnnecessaryFullyQualifiedName (2), CollapsibleIfStatements (2), LambdaCanBeMethodReference (3). ~30 violations.

- [ ] **Step 1: Enumerate**

Run: `cd backend && python3 -c "import xml.etree.ElementTree as ET,glob,collections;c=collections.defaultdict(list);[c[v.get('rule')].append(fe.get('name').split('/src/main/java/')[-1]+':'+v.get('beginline')) for f in glob.glob('wealthview-*/target/pmd.xml') for fe in ET.parse(f).getroot() for v in fe if v.get('rule') in ('ControlStatementBraces','OneDeclarationPerLine','SimplifyBooleanReturns','UselessParentheses','UseDiamondOperator','UnnecessaryFullyQualifiedName','CollapsibleIfStatements','LambdaCanBeMethodReference')];[print(k,vs) for k,vs in c.items()]"`

- [ ] **Step 2: Apply the fixes**

- ControlStatementBraces: add `{ }` to brace-less `if`/`else`/`for`/`while` bodies.
- OneDeclarationPerLine: split `int a, b;` into separate lines.
- SimplifyBooleanReturns: `if (x) return true; return false;` → `return x;`.
- UselessParentheses / UnnecessaryFullyQualifiedName: remove redundant parens / qualifiers (add the import instead).
- CollapsibleIfStatements: merge nested `if`s with `&&`.
- LambdaCanBeMethodReference: convert `x -> foo(x)` to `Foo::foo` where unambiguous.

Each is behavior-preserving. Skip any case where the fix genuinely reduces readability — note it for ruleset suppression in Task 13.

- [ ] **Step 3: Run all unit tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "style: resolve small PMD style violations

Adds control-statement braces, splits multi-declarations, simplifies
boolean returns, removes useless parentheses and redundant qualifiers,
collapses nested ifs, converts lambdas to method references.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: PMD — API modernization (UseVarargs, ReplaceJavaUtilDate, Locale)

**Rules:** UseVarargs (10), ReplaceJavaUtilDate (8 — all in `JwtTokenProvider.java`), UseLocaleWithCaseConversions (2), UseDiamondOperator residual. ~20 violations.

- [ ] **Step 1: Write/extend characterization tests first**

`ReplaceJavaUtilDate` in `JwtTokenProvider` swaps `java.util.Date` for `java.time` — this touches token expiry math, a behavior-sensitive area. Before changing it, ensure `JwtTokenProviderTest` covers: token generation, expiry boundary (token valid just before expiry, invalid just after), and claims round-trip. If any are missing, add them (RED — they pass against current code as characterization tests). Run: `mvn test -pl wealthview-core -Dtest=JwtTokenProviderTest` — Expected: PASS.

- [ ] **Step 2: Apply ReplaceJavaUtilDate**

In `JwtTokenProvider`, replace `new Date()` / `Date` arithmetic with `java.time.Instant`. JJWT 0.13 accepts `Date` via `Date.from(instant)` at the API boundary — convert only at the JJWT call, keep `Instant` everywhere else. Verify expiry semantics unchanged.

- [ ] **Step 3: Apply UseVarargs and Locale fixes**

UseVarargs: change array parameters to varargs where the method is not an override and callers benefit. UseLocaleWithCaseConversions: add `Locale.ROOT` to `toUpperCase()`/`toLowerCase()` calls — `Locale.ROOT` for symbol/identifier normalization (avoids the Turkish-i bug).

- [ ] **Step 4: Run tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-projection`
Expected: BUILD SUCCESS, `JwtTokenProviderTest` green.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor: modernize date handling and API signatures (PMD)

Replaces java.util.Date with java.time.Instant in JwtTokenProvider
(converting only at the JJWT boundary), converts array params to
varargs, and adds Locale.ROOT to case conversions. Token expiry
semantics verified unchanged by JwtTokenProviderTest.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: PMD — correctness-adjacent fixes (exceptions, resources, arrays)

**Rules:** AvoidCatchingGenericException (7), CloseResource (4), ArrayIsStoredDirectly (6), UnusedFormalParameter (5), NullAssignment (3), CompareObjectsWithEquals (1), ReturnEmptyCollectionRatherThanNull (1), UnusedLocalVariable (1). ~28 violations.

- [ ] **Step 1: Enumerate and triage**

Run the enumeration python snippet (as in Task 5) filtered to these rules. For each, decide: real fix vs. justified suppression.

- [ ] **Step 2: Fix CompareObjectsWithEquals and ReturnEmptyCollectionRatherThanNull first (potential bugs)**

`CompareObjectsWithEquals` (`==` on objects) — if this is comparing values it is a latent bug; write a failing test exposing the wrong comparison if behavior is observably wrong, then fix with `.equals()`. If it is an intentional identity check, suppress with a comment. `ReturnEmptyCollectionRatherThanNull` — change `return null` to `return List.of()` / `Collections.emptyList()`; add a test asserting the caller gets an empty collection.

- [ ] **Step 3: Fix AvoidCatchingGenericException**

Replace `catch (Exception e)` with the specific checked/unchecked types actually thrown. Per CLAUDE.md, controllers must not catch — let exceptions reach the `@RestControllerAdvice`. If a broad catch is genuinely required (e.g., a scheduled job that must not die), keep it but log at WARN and suppress the rule with a comment explaining why.

- [ ] **Step 4: Fix CloseResource**

Wrap unclosed `AutoCloseable` resources in try-with-resources. `TenantFilterActivator` has 2 — verify the Hibernate `Session`/`Filter` lifecycle; if the resource is framework-managed, suppress with a comment, otherwise use try-with-resources.

- [ ] **Step 5: Fix ArrayIsStoredDirectly, UnusedFormalParameter, NullAssignment, UnusedLocalVariable**

ArrayIsStoredDirectly: defensive-copy arrays stored in fields (`Arrays.copyOf`) — or, if the holder is an internal record/value type, suppress per the existing project convention. UnusedFormalParameter: remove the parameter if it is not part of an interface contract; if it is, suppress. NullAssignment: remove redundant `= null` initializers. UnusedLocalVariable: delete.

- [ ] **Step 6: Run full unit suite**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection,wealthview-persistence`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "fix: resolve correctness-adjacent PMD findings

Narrows generic exception catches to specific types, wraps resources
in try-with-resources, defensive-copies stored arrays, replaces
null-returning finders with empty collections, and removes unused
parameters and variables.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: Resolve the 5 CPD duplication blocks

**Files:** to enumerate — core (1), import (1), projection (2), + 1.

- [ ] **Step 1: Enumerate**

Run: `cd backend && mvn -q pmd:cpd && for m in wealthview-*/target/cpd.xml; do echo "== $m =="; cat "$m"; done`
Identify each duplicated block: the two locations and the duplicated lines.

- [ ] **Step 2: For each block, extract a shared helper**

If the duplication is within one module, extract a private method or a package-private helper class. If across modules, place the helper in `wealthview-core` (respecting the dependency direction — never have a lower module depend upward). Do not extract if the "duplication" is coincidental (e.g., two unrelated DTOs with similar field lists) — note it for CPD suppression instead.

- [ ] **Step 3: Run tests for affected modules**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-import,wealthview-projection`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Re-run CPD**

Run: `cd backend && mvn -q pmd:cpd && grep -l duplication wealthview-*/target/cpd.xml 2>/dev/null | xargs -r grep -c '<duplication'`
Expected: 0 blocks (or only intentional, suppressed ones).

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor: eliminate copy-paste duplication (CPD)

Extracts shared helpers for the 5 duplicated blocks flagged by CPD.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: Checkstyle conformance pass

- [ ] **Step 1: Generate the Checkstyle report**

Run: `cd backend && mvn -q checkstyle:checkstyle && for m in wealthview-*/target/checkstyle-result.xml; do echo -n "$m: "; grep -oc '<error' "$m" || echo 0; done`

- [ ] **Step 2: Fix violations**

Fix import ordering (java.* / jakarta.* / org.* / com.*, static last — per CLAUDE.md), trailing whitespace, line length >120, brace placement. These are mechanical. The Google ruleset is already configured with 4-space indentation overrides.

- [ ] **Step 3: Run tests + re-check**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection,wealthview-persistence,wealthview-app && mvn -q checkstyle:checkstyle`
Expected: BUILD SUCCESS; Checkstyle errors reduced to 0 (or documented residuals).

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "style: resolve Checkstyle conformance violations

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

# Phase 2 — Coverage hardening (the safety net)

### Task 10: Raise branch coverage in wealthview-api

**Files:**
- Test: `backend/wealthview-api/src/test/java/com/wealthview/api/...` (extend existing controller tests)

- [ ] **Step 1: Identify branch gaps**

Run: `cd backend && mvn -q test -pl wealthview-api && open wealthview-api/target/site/jacoco/index.html` (or read `jacoco.csv`). Note classes/methods with branch coverage well below line coverage — typically error-path branches (400/401/403/404).

- [ ] **Step 2: For each gap, write a failing test (RED)**

Add `@WebMvcTest` MockMvc tests for the uncovered branches: invalid request bodies (400), missing/invalid auth (401), wrong tenant (403), missing entity (404). Each test asserts HTTP status + the standard error envelope `{error, message, status}`.

- [ ] **Step 3: Run to confirm they pass against current code**

Run: `cd backend && mvn test -pl wealthview-api`
Expected: PASS — these are characterization tests pinning existing behavior. If a test fails, the controller has a real bug — fix it (write the assertion of correct behavior first).

- [ ] **Step 4: Verify branch coverage improved**

Run: `cd backend && mvn -q test -pl wealthview-api` and check `jacoco.csv` — api branch coverage should be materially higher.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-api/
git commit -m "test(api): cover error-path branches in controllers

Adds MockMvc tests for 400/401/403/404 paths, raising branch coverage
and pinning the error-envelope contract ahead of the refactor pass.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 11: Raise branch coverage in wealthview-persistence

**Files:**
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/...`

- [ ] **Step 1: Identify gaps**

Run: `cd backend && mvn -q test -pl wealthview-persistence` and read `jacoco.csv`. Persistence tests should cover custom `@Query` methods and method-name finders with non-trivial logic (per CLAUDE.md — not auto-generated CRUD).

- [ ] **Step 2: Write failing integration tests (RED)**

Use `@DataJpaTest` + `@Testcontainers` + `@AutoConfigureTestDatabase(replace = NONE)`. Test custom queries: tenant-scoped finders returning empty vs. populated, multi-tenant isolation (a query for tenant A never returns tenant B rows), ordering, and pagination edge cases.

- [ ] **Step 3: Run (needs Docker)**

Run: `cd backend && mvn test -pl wealthview-persistence`
Expected: PASS. New tests pin existing query behavior.

- [ ] **Step 4: Verify and commit**

Check `jacoco.csv` branch coverage improved, then:
```bash
git add backend/wealthview-persistence/
git commit -m "test(persistence): cover custom queries and tenant isolation

Adds Testcontainers @DataJpaTest coverage for custom @Query methods
and verifies multi-tenant row isolation.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 12: Extend PIT mutation testing to core and run it

**Files:**
- Modify: `backend/pom.xml` (pitest `targetClasses`)

- [ ] **Step 1: Broaden the PIT target classes**

In `backend/pom.xml`, the pitest plugin currently targets `com.wealthview.core.projection.*`, `com.wealthview.core.projection.tax.*`, `com.wealthview.projection.*`. Add the high-value core packages: `com.wealthview.core.account.*`, `com.wealthview.core.split.*`, `com.wealthview.core.property.*`, `com.wealthview.core.auth.*`. Add `<targetTests>` for the matching test packages.

- [ ] **Step 2: Run PIT on core and projection**

Run: `cd backend && mvn -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection`
Expected: completes; HTML report in `target/pit-reports/`. Note the mutation score and the count of SURVIVED mutants.

- [ ] **Step 3: Triage survivors**

For each surviving mutant, decide: (a) a missing test — the mutation changes real behavior no test catches; (b) equivalent mutant — no observable behavior change, ignore; (c) trivial — not worth a test. Record the triage in `docs/quality/2026-05-15-baseline-metrics.md`.

- [ ] **Step 4: Commit the config change**

```bash
git add backend/pom.xml docs/quality/
git commit -m "chore: extend PIT mutation coverage to core service packages

Broadens pitest targetClasses to account, split, property, and auth
packages so mutation testing validates test robustness across the
business logic, not just the projection engines.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 13: Kill surviving mutants in core and projection

**Files:**
- Test: various `*Test.java` in `wealthview-core` and `wealthview-projection`

- [ ] **Step 1: Pick the first survivor from the triage list (category (a) — missing test)**

- [ ] **Step 2: Write a failing test (RED)**

Write a test that fails when the mutation is applied — i.e., it asserts the specific behavior the mutant breaks (a boundary condition, an off-by-one, a flipped comparison, a return-value detail). Run it against current code: it should PASS (the real code is correct); the point is it would now KILL the mutant.

- [ ] **Step 3: Repeat for every category-(a) survivor**

Work through the triage list. Group related tests into one commit per class-under-test.

- [ ] **Step 4: Re-run PIT to confirm**

Run: `cd backend && mvn -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection`
Expected: mutation score materially up; remaining survivors are only categories (b)/(c).

- [ ] **Step 5: Commit (one per class-under-test)**

```bash
git add backend/
git commit -m "test(core): kill surviving mutants in <ClassName>

Adds boundary and comparison-detail assertions that PIT mutation
testing showed were uncovered, hardening the suite as a refactor
safety net.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 14: Characterization tests for the 6 decomposition targets

**Files:**
- Test: new/extended tests for `MonteCarloSpendingOptimizer`, `RothConversionOptimizer`, `DeterministicProjectionEngine`, `PoolStrategy`, `PropertyService`, `AuthService`.

- [ ] **Step 1: For each target file, assess current test coverage**

Run JaCoCo per module and review each target's existing test. Per CLAUDE.md, the projection engine uses `@ParameterizedTest` with known input/output fixtures.

- [ ] **Step 2: Write golden-master / characterization tests**

For each of the 6 files, ensure there is a test that exercises its full public API with realistic fixtures and asserts the exact outputs (projection year-by-year values, optimizer results, holdings computations, ROI numbers, auth results). These become the contract the Phase 3 decomposition must not break. For the optimizers, pin a deterministic seed so Monte Carlo output is reproducible — if no seed hook exists, add one (small, test-only constructor or config) as the first sub-step, TDD.

- [ ] **Step 3: Run all tests**

Run: `cd backend && mvn verify -pl wealthview-app` (full suite incl. ITs — needs Docker)
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit (one per target file)**

```bash
git add backend/
git commit -m "test(projection): characterization tests for <ClassName>

Pins the full public-API behavior of <ClassName> with deterministic
fixtures ahead of the Phase 3 decomposition.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

# Phase 3 — Structural decomposition (behavior-preserving)

> **Gate:** Do not start Phase 3 until Task 14 is complete and `mvn verify` is green. Every task below must keep the Phase 2 characterization tests passing unchanged.

### Task 15: Decompose MonteCarloSpendingOptimizer (1611 lines)

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/MonteCarloSpendingOptimizer.java`
- Create: focused collaborator classes in `com/wealthview/projection/` (e.g., a simulation-runner, a result-aggregator/percentile-calculator, a guardrail-search component) — exact split determined by reading the file.

- [ ] **Step 1: Read the file and map responsibilities**

Identify the distinct responsibilities (e.g., single-path simulation, batch/parallel orchestration, percentile aggregation, guardrail spending search, parameter assembly). The PMD findings on this file (10 CyclomaticComplexity, 7 CognitiveComplexity, 8 UseVarargs, 5 ExcessiveParameterList, 4 UnusedFormalParameter, 3 NPath) map directly to these seams.

- [ ] **Step 2: Extract one collaborator at a time**

For each responsibility: create the new class, move the methods, inject it into `MonteCarloSpendingOptimizer`. Use a parameter object (a `record`) to resolve the `ExcessiveParameterList` findings. After EACH extraction, run the characterization tests.

Run after each: `cd backend && mvn test -pl wealthview-projection -Dtest=MonteCarloSpendingOptimizerTest`
Expected: PASS — behavior unchanged.

- [ ] **Step 3: Run the full projection + app suite**

Run: `cd backend && mvn verify -pl wealthview-app`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Re-run PMD on the file**

Run: `cd backend && mvn -q pmd:pmd -pl wealthview-projection` — confirm complexity violations on this file are resolved.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-projection/
git commit -m "refactor(projection): decompose MonteCarloSpendingOptimizer

Splits the 1611-line optimizer into focused collaborators
(<list them>), each with a single responsibility. Parameter objects
replace long argument lists. Behavior verified unchanged by the
characterization suite.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 16: Decompose RothConversionOptimizer (887 lines)

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/RothConversionOptimizer.java`
- Create: focused collaborators in `com/wealthview/projection/`.

- [ ] **Step 1: Map responsibilities** — joint spending-conversion optimization, target-balance search, bracket-fill logic, rental-loss integration. The 24 FieldDeclarationsShouldBeAtStartOfClass + 5 ArrayIsStoredDirectly + 5 ControlStatementBraces findings here are mostly resolved in Phase 1; this task targets the structural size.
- [ ] **Step 2: Extract collaborators one at a time**, running `RothConversionOptimizerTest` after each.
- [ ] **Step 3: Run** `cd backend && mvn verify -pl wealthview-app` — Expected: BUILD SUCCESS.
- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-projection/
git commit -m "refactor(projection): decompose RothConversionOptimizer

Splits the 887-line optimizer into focused collaborators. Behavior
verified unchanged by the characterization suite.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 17: Decompose DeterministicProjectionEngine (846 lines)

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java`
- Create: collaborators in `com/wealthview/projection/`.

- [ ] **Step 1: Map responsibilities** — year-by-year iteration, contribution/growth/inflation application, withdrawal strategy, spending-plan resolution. 6 CyclomaticComplexity + 2 NPath + 2 CognitiveComplexity findings mark the seams.
- [ ] **Step 2: Extract one at a time**, running `DeterministicProjectionEngineTest` after each.
- [ ] **Step 3: Run** `cd backend && mvn verify -pl wealthview-app` — Expected: BUILD SUCCESS.
- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-projection/
git commit -m "refactor(projection): decompose DeterministicProjectionEngine

Splits the 846-line engine into focused collaborators. Behavior
verified unchanged by the characterization suite.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 18: Decompose PoolStrategy (736 lines)

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`
- Create: collaborators in `com/wealthview/projection/`.

- [ ] **Step 1: Map responsibilities.** 4 ExcessiveParameterList + 3 NullAssignment findings here. Consider whether per-pool strategies should be a sealed interface with per-type implementations (CLAUDE.md favors sealed interfaces for known type hierarchies).
- [ ] **Step 2: Extract one at a time**, running the pool/projection tests after each.
- [ ] **Step 3: Run** `cd backend && mvn verify -pl wealthview-app` — Expected: BUILD SUCCESS.
- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-projection/
git commit -m "refactor(projection): decompose PoolStrategy

Splits the 736-line class into focused per-responsibility units;
parameter objects replace long argument lists. Behavior verified
unchanged by the characterization suite.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 19: Decompose PropertyService (577 lines)

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java`
- Create: collaborators in `com/wealthview/core/property/`.

- [ ] **Step 1: Map responsibilities** — CRUD orchestration, expense handling, ROI/hold-vs-sell analysis, depreciation coordination. The GodClass finding applies here. Extract the ROI-analysis logic into a `PropertyRoiService` / analysis component; keep `PropertyService` as the CRUD + orchestration boundary. Preserve `@Transactional` boundaries and tenant filtering.
- [ ] **Step 2: Extract one at a time**, running `PropertyServiceTest` (+ ROI tests) after each.
- [ ] **Step 3: Run** `cd backend && mvn verify -pl wealthview-app` — Expected: BUILD SUCCESS.
- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-core/
git commit -m "refactor(core): decompose PropertyService

Extracts ROI/hold-vs-sell analysis into a dedicated component,
leaving PropertyService as the CRUD and orchestration boundary.
Transactional boundaries and tenant filtering preserved. Behavior
verified unchanged by the characterization suite.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 20: Decompose AuthService (511 lines)

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/auth/AuthService.java`
- Create: collaborators in `com/wealthview/core/auth/`.

- [ ] **Step 1: Map responsibilities** — login, MFA challenge/completion, token issue/refresh, registration/invite. 3 CyclomaticComplexity findings. Extract MFA flow into an `MfaChallengeService` and token lifecycle into a `TokenService` if not already separate; keep `AuthService` as the authentication orchestrator. Preserve security-context tenant sourcing.
- [ ] **Step 2: Extract one at a time**, running `AuthServiceTest` after each.
- [ ] **Step 3: Run** `cd backend && mvn verify -pl wealthview-app` (auth integration tests are critical) — Expected: BUILD SUCCESS.
- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-core/
git commit -m "refactor(core): decompose AuthService

Extracts MFA-challenge and token-lifecycle responsibilities into
dedicated collaborators, leaving AuthService as the authentication
orchestrator. Behavior verified unchanged by unit and integration
tests.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

# Phase 4 — Frontend pass

### Task 21: Establish the frontend baseline

**Files:**
- Create: `docs/quality/2026-05-15-frontend-baseline.md`

- [ ] **Step 1: Run the toolchain**

Run from repo root:
```bash
npm install
npm run lint --workspace frontend
npm run typecheck --workspace frontend || (cd frontend && npx tsc --noEmit)
npx vitest run --coverage --workspace frontend || (cd frontend && npx vitest run --coverage)
```
Capture: ESLint error/warning count, any `tsc` errors, Vitest coverage %, test pass/fail.

- [ ] **Step 2: Run the shared workspace tests**

Run: `npm run test:shared`
Expected: PASS. Note coverage.

- [ ] **Step 3: Write the baseline doc and commit**

Record all numbers in `docs/quality/2026-05-15-frontend-baseline.md`.
```bash
git add docs/quality/2026-05-15-frontend-baseline.md
git commit -m "docs: capture frontend quality baseline

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 22: Fix frontend lint and type findings

**Files:** various under `frontend/src/` and `shared/`.

- [ ] **Step 1: Fix all `tsc` errors first**

Type errors are correctness issues. Fix each — no `any` (CLAUDE.md forbids it); define interfaces for API responses. Run `cd frontend && npx tsc --noEmit` until clean.

- [ ] **Step 2: Fix ESLint errors, then warnings**

Run `npm run lint --workspace frontend`. Fix errors first, then warnings. Do not blanket-disable rules; suppress inline only with a justifying comment.

- [ ] **Step 3: Run frontend + shared tests**

Run: `npm run test:frontend && npm run test:shared`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/ shared/
git commit -m "fix(frontend): resolve TypeScript and ESLint findings

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 23: Close frontend test gaps

**Files:** new `*.test.tsx` / `*.test.ts` co-located with pages, hooks, and complex utilities.

- [ ] **Step 1: Identify untested pages, hooks, and logic**

From the Task 21 coverage report, list page components, custom hooks, and complex utilities with no or thin tests. Per CLAUDE.md: pages, hooks, and complex logic MUST be tested; simple presentational components MAY be skipped.

- [ ] **Step 2: For each gap, write tests (Vitest + React Testing Library)**

Test rendering, user interactions, hook state transitions, and API-driven states (loading/error/success). Co-locate the test file. For hooks that call the API, mock the Axios client.

- [ ] **Step 3: Run tests + coverage**

Run: `npm run test:frontend` and re-check coverage — materially higher on pages/hooks.

- [ ] **Step 4: Commit (group by area)**

```bash
git add frontend/
git commit -m "test(frontend): cover untested pages, hooks, and logic

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

# Phase 5 — Enforce quality gates

### Task 24: Bind PMD, SpotBugs, Checkstyle into the build lifecycle as gates

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/pmd-ruleset.xml` (add justified suppressions for any residuals)

- [ ] **Step 1: Verify a clean slate**

Run: `cd backend && mvn -q clean test pmd:pmd pmd:cpd spotbugs:spotbugs checkstyle:checkstyle -DskipITs`
Confirm: PMD, CPD, SpotBugs, Checkstyle all report 0 (or only documented, suppressed residuals). If anything remains, fix it or add a commented suppression to the ruleset BEFORE enabling the gate.

- [ ] **Step 2: Add the plugins to `<build><plugins>` with executions**

In `backend/pom.xml`, move PMD, SpotBugs, and Checkstyle out of advisory-only mode: add `<executions>` binding `pmd:check` + `pmd:cpd-check`, `spotbugs:check`, and `checkstyle:check` to the `verify` phase. Set `failOnViolation`/`failOnError`/`failsOnError` to `true`. Keep the `<reporting>` entries for HTML reports.

- [ ] **Step 3: Verify the gates fail-closed**

Temporarily introduce a deliberate violation (e.g., an empty `if` block), run `cd backend && mvn verify -DskipITs`, confirm the build FAILS, then revert the violation.

- [ ] **Step 4: Confirm a clean build passes**

Run: `cd backend && mvn clean verify -DskipITs`
Expected: BUILD SUCCESS with all gates active.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/pmd-ruleset.xml
git commit -m "chore: enforce PMD, SpotBugs, and Checkstyle as build gates

Binds pmd:check, pmd:cpd-check, spotbugs:check, and checkstyle:check
to the verify phase with failOnViolation enabled. The codebase is
clean as of this commit; residual suppressions are documented in
pmd-ruleset.xml.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 25: Add JaCoCo coverage-check gates

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Read current per-module coverage**

After all of Phase 2, re-run `cd backend && mvn -q clean test` and read each module's `jacoco.csv` for current line + branch coverage.

- [ ] **Step 2: Add a `jacoco:check` execution**

Add a `check` execution to the JaCoCo plugin, bound to the `verify` phase, with per-module `<rules>`. Set line-coverage minimums to the CLAUDE.md targets (core 0.90, projection 0.90, api 0.80, import 0.80) and a branch-coverage floor set just below each module's *current* measured branch coverage (so the gate locks in the gains without being flaky). Use the module-specific override pattern so each module gets its own threshold.

- [ ] **Step 3: Verify**

Run: `cd backend && mvn clean verify -DskipITs`
Expected: BUILD SUCCESS — coverage meets the gates. If a module is just under, the gate threshold was set too high; lower it to the measured value (do not weaken below the CLAUDE.md line targets).

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml
git commit -m "chore: enforce JaCoCo coverage thresholds per module

Adds jacoco:check on the verify phase with per-module line targets
(core/projection 90%, api/import 80%) and branch-coverage floors set
to the levels reached during the coverage-hardening pass.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 26: Update CLAUDE.md to document the enforced gates

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the Testing and Build sections**

Document that PMD, SpotBugs, Checkstyle, and JaCoCo now fail the build on `mvn verify`; state the per-module coverage thresholds; note that new code must pass the gates and that PMD-rule suppressions require a justifying comment in `pmd-ruleset.xml`. Add the PIT mutation-testing command to the Build & Run section.

- [ ] **Step 2: Verify and commit**

Run: `cd backend && mvn clean verify -DskipITs` — Expected: BUILD SUCCESS.
```bash
git add CLAUDE.md
git commit -m "docs: document enforced quality gates in CLAUDE.md

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

# Phase 6 — Performance review and careful repairs

### Task 27: Survey for performance issues

**Files:**
- Create: `docs/quality/2026-05-15-performance-findings.md`

- [ ] **Step 1: Hunt N+1 query patterns**

Enable Hibernate SQL logging in a test profile and run the integration suite, or inspect services that loop over entities issuing per-row queries. Prior work added batch balance computation and Caffeine caching — confirm no regressions and look for *new* N+1s in the split, property, and projection paths. Record each suspect with file:line.

- [ ] **Step 2: Review indexes vs. query patterns**

Cross-check the most-used repository finders against the indexes in the Flyway migrations. Flag tenant-scoped or symbol/date-range queries that scan without a supporting index.

- [ ] **Step 3: Hunt repeated computation and unbounded results**

Look for: results that should be paginated but are not, repeated computation inside loops that could be hoisted or memoized, the projection/optimizer hot paths (`MonteCarloSpendingOptimizer` runs many simulations — check allocation in the inner loop).

- [ ] **Step 4: Frontend performance scan**

Check the Vite production bundle size (`npm run build --workspace frontend`), look for missing `useMemo`/`useCallback` on expensive renders, and large unmemoized list renders.

- [ ] **Step 5: Write the findings doc and commit**

Record every finding in `docs/quality/2026-05-15-performance-findings.md` with: location, evidence (why it is slow), estimated impact, and proposed fix. Mark each as confirmed (evidenced) or speculative.
```bash
git add docs/quality/2026-05-15-performance-findings.md
git commit -m "docs: performance review findings

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 28: Make careful, evidenced performance repairs

**Files:** determined by Task 27 findings.

- [ ] **Step 1: Select only confirmed (evidenced) findings**

Skip every speculative finding — fix only what the survey proved is slow. YAGNI.

- [ ] **Step 2: For each finding, write a test that pins behavior (RED/characterization)**

Before changing anything, ensure a test pins the current correct output. For an N+1 fix, the behavior must be identical — the test guards that. Where feasible, capture a before measurement (query count via a Hibernate statistics assertion, or a timing in a comment).

- [ ] **Step 3: Apply the minimal fix**

N+1 → `JOIN FETCH` or a batch query (never `FetchType.EAGER` — CLAUDE.md). Missing index → a new Flyway migration `V055__...` (and onward), following the migration naming/type rules. Repeated computation → hoist or memoize. Each fix is one commit.

- [ ] **Step 4: Verify behavior unchanged and improvement real**

Run: `cd backend && mvn verify -pl wealthview-app` (or the frontend suite) — Expected: BUILD SUCCESS. Confirm the after measurement beats the before.

- [ ] **Step 5: Commit (one per fix)**

```bash
git add backend/   # or frontend/
git commit -m "perf(<scope>): <specific fix>

<What was slow, the evidence, and the measured improvement.>

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 6: Final full verification**

Run: `cd backend && mvn clean verify` (full suite incl. ITs) and `npm run test:all`.
Expected: BUILD SUCCESS everywhere, all gates green.

---

## Final acceptance

- [ ] SpotBugs: 0 findings. PMD: 0 violations (or each residual justified in `pmd-ruleset.xml`). CPD: 0 blocks.
- [ ] No source file materially oversized; the 6 targets decomposed into single-responsibility units.
- [ ] api/persistence branch coverage raised; PIT survivors in core/projection triaged and category-(a) survivors killed.
- [ ] Frontend lint + `tsc` clean; page/hook/logic test gaps closed.
- [ ] PMD, SpotBugs, Checkstyle, JaCoCo enforced as `verify`-phase gates; CLAUDE.md updated.
- [ ] Performance findings documented; evidenced hot spots repaired with test-backed, measured fixes.
- [ ] `cd backend && mvn clean verify` and `npm run test:all` both green.
- [ ] All work committed on `main`; nothing pushed (the user pushes manually).
