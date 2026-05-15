# Pre-Release Quality Pass — Design

**Date:** 2026-05-15
**Status:** Approved — ready for implementation planning
**Context:** A full code-quality and test-coverage refactor of the entire WealthView
codebase, run before cutting a release and ahead of the next feature set.

---

## Goal

Bring the codebase to release-grade fit and finish: fix quality findings, harden the
test suite into a robust refactoring safety net, decompose oversized files, and convert
the currently advisory quality tooling into enforced build gates.

Scope is **backend + frontend** (all 6 Maven modules, the React/TS frontend, and the
`shared` workspace).

---

## Baseline metrics (captured 2026-05-15)

Tooling — PMD, SpotBugs, JaCoCo, Checkstyle, PIT — is already configured in `backend/pom.xml`
but only in `<reporting>` / `<pluginManagement>`: advisory-only, nothing fails the build.

**JaCoCo line coverage:** core 90.5% · projection 96.8% · import 90.5% · app 95.5% ·
persistence 81.8% · api 81.6%. Every module already meets the CLAUDE.md line targets, so
raw line coverage is **not** the gap — branch coverage and mutation kill-rate are.

**PMD:** 218 violations — projection 119, core 73, import 13, api 7, persistence 4, app 2.

**CPD (copy-paste):** 5 duplication blocks — core 1, import 1, projection 2.

**SpotBugs:** 7 findings, all low severity — `DM_DEFAULT_ENCODING` ×2,
`DLS_DEAD_LOCAL_STORE` ×2, `CT_CONSTRUCTOR_THROW` ×2 (+1).

**Largest source files (decomposition candidates):**
`MonteCarloSpendingOptimizer` 1611 · `RothConversionOptimizer` 887 ·
`DeterministicProjectionEngine` 846 · `PoolStrategy` 736 · `PropertyService` 577 ·
`AuthService` 511.

**Working tree:** an uncommitted `wv` script reorganization (`scripts/wv-lib` → `bin/wv-lib`)
plus untracked files must be committed (or set aside) so the refactor lands on a clean
baseline with honest, typed TDD commits.

**Assessment:** the codebase is in good shape. This is fit-and-finish work, not a rescue.

---

## Approach

Risk-tiered sequencing: the test safety net is hardened **before** any structural change,
so every risky refactor runs against robust, mutation-validated tests. Within each phase,
modules are worked in dependency order: persistence → core → import → projection → api → app.

### Phase 0 — Stabilize baseline
- Inspect the pending `wv` reorg; if coherent, commit it and the untracked files as
  separate logical commits (`chore` / `docs`). If half-finished, set aside and report.
- Record the baseline metrics above into a tracked report doc.

### Phase 1 — Safe wins (low risk, behavior-preserving)
- Fix the 7 SpotBugs findings (explicit charset on `getBytes`, remove dead stores,
  resolve constructor-throw).
- Clear genuine PMD violations. **Be thoughtful:** where a fix would hurt readability,
  suppress the finding or refine `pmd-ruleset.xml` rather than contorting code.
- Resolve the 5 CPD blocks by extracting shared helpers.
- Checkstyle conformance pass.
- Typed commits: `fix`, `refactor`, `style`.

### Phase 2 — Coverage hardening (the safety net)
- Raise **branch** coverage in api and persistence (lowest modules).
- Extend PIT mutation testing beyond projection to core; run it and kill surviving mutants
  with targeted tests. PIT — not JaCoCo line % — is the robustness metric.
- Add **characterization tests** pinning current behavior for every file slated for
  decomposition in Phase 3.
- Strict TDD: red test first, every time.
- This phase carries most of the effort, by design.

### Phase 3 — Structural decomposition (behavior-preserving)
- Decompose the oversized files into focused collaborators, in this order:
  `MonteCarloSpendingOptimizer`, `RothConversionOptimizer`, `DeterministicProjectionEngine`,
  `PoolStrategy`, `PropertyService`, `AuthService`.
- Characterization tests from Phase 2 must stay green throughout.
- One file per `refactor` commit.

### Phase 4 — Frontend pass
- Establish a baseline: ESLint, `tsc --noEmit`, Vitest coverage, axe.
- Fix lint/type findings; fill test gaps on pages, hooks, and complex logic; light
  component cleanup.
- No structural rework unless a finding demands it.

### Phase 5 — Enforce gates
- Bind PMD, SpotBugs, Checkstyle, JaCoCo into the build lifecycle (not just `<reporting>`).
- Flip to fail-the-build: PMD/SpotBugs `failOnViolation`/`failOnError`; JaCoCo `check`
  with CLAUDE.md line targets plus a branch floor; Checkstyle on `verify`.
- Update CLAUDE.md to document the now-enforced gates.
- Gates are flipped **last** so the build never blocks mid-effort.

### Phase 6 — Performance review and careful repairs
Runs after the quality pass is complete, against the cleaned-up and gate-protected codebase.
- Survey for performance issues: N+1 query patterns, missing indexes, unbounded result
  sets, repeated computation that could be cached, hot paths in the projection engines
  (`MonteCarloSpendingOptimizer` runs many simulations), and frontend render/bundle costs.
- Prior scaling work (batch balance computation, Caffeine caching, HikariCP tuning) is
  already in place — this is an incremental sweep, not a redo.
- Make **careful, measured repairs**: each change backed by a test proving behavior is
  unchanged, and where feasible a before/after measurement justifying it.
- Skip speculative optimization — fix only what evidence shows is slow.

---

## Testing discipline

- TDD Red-Green-Refactor for every production change.
- Characterization tests gate every decomposition in Phase 3.
- `mvn verify` (including Testcontainers integration tests) must be green before each commit.
- PIT mutation testing is the test-robustness check; JaCoCo line % is a floor, not the goal.
- Frontend: Vitest + RTL for pages/hooks/logic; presentational components may be skipped.

## Risk controls

- Structural decomposition happens only after Phase 2 hardens the safety net.
- One logical change per commit — anything is independently revertible.
- Quality gates are enforced last, so the build never blocks during the effort.
- Work proceeds in module dependency order to keep blast radius contained.

## Success criteria

- SpotBugs: 0 findings. PMD: 0 violations (or each remaining one explicitly justified in
  the ruleset). CPD: 0 duplication blocks.
- No source file materially oversized; decomposed units have single, clear responsibilities.
- Branch coverage raised in api/persistence; PIT mutant survivors triaged in core/projection.
- Frontend lint and `tsc` clean; test gaps on pages/hooks closed.
- Quality tooling enforced as build gates; CLAUDE.md updated.
- `mvn verify` and frontend `npm run test` green throughout.
- Performance sweep complete: evidenced hot spots repaired, each repair test-backed.

## Out of scope

- New product features (this precedes the next feature set).
- Mobile workspace native builds (CI-only per existing convention).
- Dependency version bumps beyond what a finding requires.
