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
