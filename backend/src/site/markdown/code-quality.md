# Code Quality

WealthView uses five automated quality tools, all configured in the parent POM and integrated
into the Maven site via the `<reporting>` section. All tools are set to **warn, not fail** —
quality issues are surfaced in reports without breaking the build.

---

## Tools at a Glance

| Tool | Purpose | Config | Site Report |
|---|---|---|---|
| **JaCoCo 0.8.14** | Line and branch coverage | Parent POM `<build>` | `jacoco/index.html` per module; `jacoco-aggregate/` in wealthview-app |
| **SpotBugs 4.9.4** | Static bug detection | `spotbugs-exclude.xml` | `spotbugs.html` |
| **Checkstyle 3.6.0** | Code style | Google checks + 4-space override | `checkstyle.html` |
| **PMD 3.28.0** | Code quality + CPD | `pmd-ruleset.xml` | `pmd.html`, `cpd.html` |
| **Pitest 1.19.1** | Mutation testing | Parent POM | Separate run; not in site |

---

## Coverage Targets

Coverage is tracked per module. The following are the documented targets:

| Module | Line Coverage Target |
|---|---|
| `wealthview-core` | 90%+ |
| `wealthview-projection` | 90%+ |
| `wealthview-api` | 80%+ |
| `wealthview-import` | 80%+ |
| `wealthview-persistence` | Tested via integration tests |
| `wealthview-app` | IT coverage only |

The `wealthview-app` module generates an **aggregate coverage report** across all modules via
the `jacoco:report-aggregate` goal (bound to `verify`). This gives the truest picture of
overall coverage, since some code paths in `core` are only exercised through the full stack.

Coverage reports are generated during `mvn test`. They appear at
`target/site/jacoco/index.html` in each module.

---

## SpotBugs

SpotBugs runs at **Max effort** with a **Medium threshold** — it reports every potential bug
at medium severity or above. The filter file `backend/spotbugs-exclude.xml` suppresses
known false positives (e.g., non-null field initialisation patterns that SpotBugs cannot
statically verify).

Common categories surfaced:

* **`NP_NULL_ON_SOME_PATH`** — potential null dereferences
* **`EI_EXPOSE_REP`** / **`EI_EXPOSE_REP2`** — mutable object exposure (less relevant since
  most DTOs are Java records)
* **`DM_BOXED_PRIMITIVE_FOR_PARSING`** — unnecessary boxing
* **`SQL_INJECTION_*`** — SQL injection patterns (always investigate these)

View the report: `target/site/spotbugs.html`

---

## Checkstyle

The project uses **Google Java Style** as the base ruleset, with overrides to match the
project's 4-space indentation convention (Google default is 2 spaces):

```
basicOffset=4
caseIndent=4
throwsIndent=8
arrayInitIndent=4
lineWrappingIndentation=8
```

Key checks enforced:

* No wildcard imports
* Max line length 100 characters (configurable)
* Javadoc presence on public methods (warning, not error)
* K&R brace style
* Parameterised SLF4J log calls (no string concatenation)

View the report: `target/site/checkstyle.html`

---

## PMD

PMD runs against a **custom ruleset** (`backend/pmd-ruleset.xml`) tuned for this project.
The ruleset includes:

* **Best practices** — unused variables, unnecessary imports, empty catch blocks
* **Design** — excessive class complexity, god-class detection (some suppressed at class level
  where justified, e.g., `DeterministicProjectionEngine`)
* **Error-prone** — null assignments, close-resource patterns
* **Performance** — unnecessary object creation in loops
* **Naming conventions** — enforced via custom rules matching the project's naming guide

**CPD (Copy-Paste Detection)** runs in the same report set and highlights duplicated code blocks
above the configured token threshold. This is particularly useful for catching duplicated tax
computation logic across the deterministic and MC engines.

View the reports: `target/site/pmd.html`, `target/site/cpd.html`

---

## Pitest (Mutation Testing)

Pitest injects mutations into the bytecode (flipping conditionals, changing arithmetic operators,
removing method calls) and checks that existing tests catch each mutation. A mutation that
**survives** (no test fails) indicates a gap in test coverage that line coverage alone would not
reveal.

Pitest is configured to target the most algorithmically complex packages:

```
com.wealthview.core.projection.*
com.wealthview.core.projection.tax.*
com.wealthview.projection.*
```

Run mutation tests manually (not part of `mvn site`; expensive to run):

```bash
cd backend
mvn -pl wealthview-projection pitest:mutationCoverage
mvn -pl wealthview-core pitest:mutationCoverage
```

Results appear in `target/pit-reports/`.

---

## Dependency Version Reporting

The **Versions Maven Plugin** (versions-maven-plugin 2.18.0) generates three reports:

* **Dependency Updates** — which declared dependencies have newer versions available
* **Plugin Updates** — which build/report plugins have newer versions
* **Property Updates** — which version properties (e.g., `jjwt.version`) could be bumped

View the reports under `target/site/dependency-updates-report.html` etc.

---

## Java 21 Idioms as Quality Enforcement

Beyond tool-based checks, the project's use of Java 21 features provides structural quality
guarantees:

* **Sealed interfaces** for `SpendingPlan`, `WithdrawalStrategy`, `PoolStrategy` — exhaustive
  switch expressions are compiler-checked; no default case is needed, so adding a new subtype
  forces every switch to be updated.

* **Records** for all DTOs — immutability, `equals`/`hashCode`/`toString` are auto-generated
  correctly; no accidental mutable state.

* **`Optional<T>` return types** — explicit at the call site that the result may be absent;
  no silent null propagation.

* **`var`** where the right-hand side makes the type obvious — reduces verbosity without
  hiding types.
