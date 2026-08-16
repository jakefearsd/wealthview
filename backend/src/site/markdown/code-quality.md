# Code Quality

WealthView uses five automated quality tools, all configured in the parent POM (`backend/pom.xml`).
Each is wired up **twice**, and the distinction matters:

* In `<build>` — bound to the **`verify`** phase as an enforcing execution. **All five FAIL the
  build.** New code must pass them before it can be committed or merged.
* In `<reporting>` — the same tools re-declared for `mvn site` with `failOnError` /
  `failOnViolation` set to `false`, so generating the documentation site never breaks on a finding.

A sixth tool, **PIT** (mutation testing), is configured but deliberately **not** a gate.

```bash
cd backend
mvn verify -DskipITs          # runs all five gates without Docker-backed Failsafe ITs
mvn verify                    # gates + Testcontainers integration tests
```

---

## Tools at a Glance

| Tool | Enforcing goal (phase) | Purpose | Config | Site Report |
|---|---|---|---|---|
| **JaCoCo 0.8.15** | `jacoco:check` (`verify`) | Line and branch coverage floors | Per-module `jacoco.*.minimum` properties | `jacoco/index.html` per module |
| **SpotBugs (plugin 4.10.3.0)** | `spotbugs:check` (`verify`) | Static bug detection | `backend/spotbugs-exclude.xml` | `spotbugs.html` |
| **Checkstyle (plugin 3.6.0)** | `checkstyle:check` (`verify`) | Code style | `backend/wealthview_checks.xml` | `checkstyle.html` |
| **PMD (plugin 3.28.0)** | `pmd:check` (`verify`) | Code quality rules | `backend/pmd-ruleset.xml` | `pmd.html` |
| **CPD (same plugin)** | `pmd:cpd-check` (`verify`) | Copy-paste detection | maven-pmd-plugin defaults | `cpd.html` |
| **PIT 1.25.9** | *(advisory — run manually)* | Mutation testing | Parent POM `<configuration>` | Separate run; not in site |

Two analysis engines are pinned independently of their plugin version so they can parse Java 25:

* **Checkstyle core `13.10.0`** — pinned via a `<dependencies>` block on `maven-checkstyle-plugin`.
* **PMD core** stays at the `7.17.0` bundled with maven-pmd-plugin 3.28.0. Forcing a newer PMD core
  was tried and reverted: 7.26.0 surfaced four additional rule violations unrelated to the tooling
  bump. 7.17.0 is already at or above the JDK-25-capable floor.

SpotBugs uses the core bundled with the plugin. PMD is configured with `<targetJdk>25</targetJdk>`.

---

## Coverage Gates

`jacoco:check` reads the unit-test `jacoco.exec` produced by `prepare-agent` and enforces a
`BUNDLE`-level rule with both a `LINE` and a `BRANCH` `COVEREDRATIO` limit. The parent POM defaults
to `jacoco.check.skip=true` with 0.00 thresholds; **each gated module overrides them in its own
`<properties>` block**:

| Module | `jacoco.line.minimum` | `jacoco.branch.minimum` |
|---|---|---|
| `wealthview-core` | 0.90 | 0.83 |
| `wealthview-projection` | 0.90 | 0.84 |
| `wealthview-api` | 0.80 | 0.85 |
| `wealthview-import` | 0.80 | 0.71 |
| `wealthview-persistence` | *not gated* — covered by Testcontainers integration tests |
| `wealthview-app` | *not gated* — covered by the Failsafe `*IT.java` suite |

Line minimums track the targets in `CLAUDE.md`. Branch floors were set just below the levels reached
during the coverage-hardening pass, so the gate locks in gains without flaking. **Raise a floor when
you raise coverage; never lower one to make a build pass.**

The `report` goal is bound to the `test` phase, so coverage HTML is produced by `mvn test` alone and
appears at `target/site/jacoco/index.html` in each module.

---

## SpotBugs

SpotBugs runs at **Max effort** with a **Medium threshold** — it reports every potential bug at
medium severity or above, and `spotbugs:check` fails the `verify` phase on any surviving finding.
The filter file `backend/spotbugs-exclude.xml` suppresses known false positives; every entry carries
a comment explaining why.

Common categories surfaced:

* **`NP_NULL_ON_SOME_PATH`** — potential null dereferences
* **`EI_EXPOSE_REP`** / **`EI_EXPOSE_REP2`** — mutable object exposure (less relevant since most
  DTOs are Java records)
* **`DM_BOXED_PRIMITIVE_FOR_PARSING`** — unnecessary boxing
* **`SQL_INJECTION_*`** — SQL injection patterns (always investigate these)

View the report: `target/site/spotbugs.html`

---

## Checkstyle

The project uses its own ruleset, `backend/wealthview_checks.xml` (Google Java Style as the starting
point, with the project's conventions layered on top). The `Checker` severity is `warning`, and the
plugin sets `violationSeverity=warning` with `failOnViolation=true` — so **a warning fails the
build**.

Indentation overrides matching the project's 4-space convention (Google's default is 2):

```
basicOffset=4
braceAdjustment=0
caseIndent=4
throwsIndent=8
lineWrappingIndentation=8
arrayInitIndent=4
```

Key checks enforced:

* No wildcard imports (`AvoidStarImport`)
* Max line length **120** characters (`LineLength`, with package/import/URL lines exempted)
* One top-level class per file
* K&R brace style
* Javadoc structure checks, including a `SummaryJavadoc` rule that rejects filler summaries such as
  `@return the …` and `This method returns …`

View the report: `target/site/checkstyle.html`

---

## PMD

PMD runs against the **custom ruleset** `backend/pmd-ruleset.xml`, which pulls in five PMD
categories and then excludes the rules that fight this codebase's conventions:

* **`category/java/bestpractices.xml`** — minus `GuardLogStatement`, `AvoidReassigningParameters`,
  `AvoidDuplicateLiterals`, `MissingSerialVersionUID`, `LiteralsFirstInComparisons`
* **`category/java/codestyle.xml`** — minus `UseExplicitTypes`, `LocalVariableCouldBeFinal`,
  `MethodArgumentCouldBeFinal`, `OnlyOneReturn`, `ConfusingTernary`, `AtLeastOneConstructor` and
  other rules that contradict the project's `var` / naming / early-return conventions
* **`category/java/design.xml`** — minus `LawOfDemeter`, `DataClass`, `ExcessiveImports`,
  `TooManyMethods`
* **`category/java/errorprone.xml`** — minus `AvoidLiteralsInIfCondition`, `AssignmentInOperand` and
  similar
* **`category/java/performance.xml`** — minus `AvoidInstantiatingObjectsInLoops`,
  `RedundantFieldInitializer`

The five complexity rules — `CyclomaticComplexity`, `CognitiveComplexity`, `NcssCount`,
`ExcessiveParameterList`, `CouplingBetweenObjects` — are excluded from the bulk import and then
**re-included individually with tuned thresholds**, set just above the post-quality-pass measured
peaks. That locks in no-regression rather than accepting the tools' generic defaults.

**CPD (Copy-Paste Detection)** runs from the same plugin as a separate enforcing execution
(`pmd:cpd-check`) using the plugin's default token threshold — the POM does not override
`minimumTokens`. Duplication is to be resolved, not suppressed.

View the reports: `target/site/pmd.html`, `target/site/cpd.html`

---

## Suppressing a Finding Legitimately

Occasionally a rule is genuinely wrong about a specific piece of code. The rules for handling that:

1. **Prefer fixing the code.** A suppression is a permanent cost paid to avoid a one-time fix.
2. **If you must suppress, keep it narrow** — a `@SuppressWarnings("PMD.RuleName")` on the smallest
   possible element (method, not class; class, not module) with an **adjacent comment explaining
   WHY**. A suppression without a justification is a defect.
3. **SpotBugs** exclusions go in `backend/spotbugs-exclude.xml`, again with a comment.
4. **Never globally disable a rule to dodge a finding.** Excluding a rule from `pmd-ruleset.xml`, or
   raising a complexity threshold, is a documented project-wide decision — it needs a comment in the
   ruleset explaining the rationale, not a quiet edit in a feature commit.
5. **Never lower a JaCoCo floor** to make a build pass.
6. `-Dpmd.rulesets` and `-Dspotbugs.excludeFilterFile` on the command line are **silently ignored** —
   the POM `<configuration>` wins. To experiment with different rules, edit the tracked config file.

---

## PIT (Mutation Testing)

PIT injects mutations into the bytecode (flipping conditionals, changing arithmetic operators,
removing method calls) and checks that existing tests catch each mutation. A mutation that
**survives** (no test fails) indicates a gap that line coverage alone would not reveal.

PIT is **advisory — it is not bound to any phase and does not fail the build.** Use it to find tests
that pass through coverage without actually pinning behaviour.

It targets the most algorithmically complex packages:

```
com.wealthview.core.projection.*
com.wealthview.core.projection.tax.*
com.wealthview.projection.*
com.wealthview.core.account.*
com.wealthview.core.split.*
com.wealthview.core.property.*
com.wealthview.core.auth.*
```

Run it manually (expensive; not part of `mvn site`):

```bash
cd backend
mvn -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection
```

Results appear in `<module>/target/pit-reports/`.

---

## Secret Scanning

Not a Maven gate, but part of the same quality perimeter: `.githooks/pre-commit` runs
`gitleaks protect --staged` against `.gitleaks.toml` on every commit, and the `secret-scan.yml`
workflow re-runs it on release tags. Enable the hook once per clone with
`./scripts/install-hooks.sh` (or `git config core.hooksPath .githooks`).

Never commit a real or real-looking secret. Reference `${VAR}` in YAML, add a `CHANGE_ME` placeholder
to `.env.example`, and keep dev/IT fallbacks as obvious `LOCAL_DEV_*` / `INTEGRATION_TEST_*`
sentinels. If a sentinel trips the scanner, allowlist it in `.gitleaks.toml`.

---

## Dependency Version Reporting

The **Versions Maven Plugin** (versions-maven-plugin 2.21.0) generates three site reports:

* **Dependency Updates** — which declared dependencies have newer versions available
* **Plugin Updates** — which build/report plugins have newer versions
* **Property Updates** — which version properties (e.g., `jjwt.version`) could be bumped

View them under `target/site/dependency-updates-report.html` etc.

---

## Java 25 Idioms as Quality Enforcement

Beyond tool-based checks, the project's use of modern Java provides structural guarantees:

* **Sealed interfaces** — `SpendingPlan`, `ProjectionAccountInput`, `WithdrawalStrategy`,
  `QuoteResult` and `LoginOutcome` in `wealthview-core`; `PoolStrategy`, `WithdrawalOrderStrategy`,
  `IncomeSourceProcessor` and `ConversionSimulator` in `wealthview-projection`. Exhaustive switch
  expressions are compiler-checked, so adding a subtype forces every switch to be updated.

* **Records** for all DTOs — immutability, with `equals`/`hashCode`/`toString` generated correctly;
  no accidental mutable state.

* **`Optional<T>` return types** — explicit at the call site that the result may be absent; no
  silent null propagation.

* **`var`** where the right-hand side makes the type obvious — reduces verbosity without hiding
  types. (PMD's `UseExplicitTypes` is excluded from the ruleset for exactly this reason.)
</content>
