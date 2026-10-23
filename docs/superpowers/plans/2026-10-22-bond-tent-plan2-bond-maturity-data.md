# Bond Tent Plan 2 — Bond Maturity Data — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WealthView holds real annual total returns for short, intermediate and long Treasuries (1954–2025) and can serve all four tent maturities, row-aligned with the existing return matrix, to the engines that plan 3 makes tent-aware.

**Architecture:**
- A stdlib-only Python script derives the three new series from FRED constant-maturity Treasury yields and CPI. It writes them to a new repeatable seed, `R__seed_bond_maturity_returns.sql`, in a new table `bond_maturity_returns` (V083).
- Ultrashort is **not stored**. It is the existing `cash` (3-month T-bill) series, as the spec requires.
- A new tent-only enum `BondMaturity` and a new `BondMaturityReturnsProvider` in `wealthview-core` serve a `MaturityReturnMatrix`. Its rows line up one-to-one with a given `RealReturnMatrix`, so a block-bootstrap row index picks the same historical year for stocks, the portfolio's bonds and every tent sleeve.
- `AssetClass`, `asset_class_returns`, `CapitalMarketAssumptionsProvider` and every engine stay untouched. Nothing reads the new data until plan 3.

**Tech Stack:** Python 3 (stdlib only) for the derivation script; Java 25 / Spring Boot 4.1, JPA, Flyway, PostgreSQL 16, JUnit 5 + AssertJ + Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-18-bond-tent-design.md` (§4.1 bond maturity data, §5 constraints, §7 "left to the builder"). This is plan 2 of 4. Plan 1 (Monte Carlo trials start today) shipped in `107f07b8..22c7dbc1`.
- Plan 3: tent model, engines, optimizer and cash-reserve conversion.
- Plan 4: replays, fixed-spending evaluation and the Bond tent tab.

### Builder's calls this plan makes (spec §4.1 / §7)

1. **Method:** a monthly constant-maturity roll, priced from month-end yields. It was chosen over Damodaran's one-repricing-per-year convention because it behaves like the funds the user names. In a prototype run on 2026-10-22, nominal results were:

   | Series | Prototype | Fund |
   | --- | --- | --- |
   | Short, 2008 | +6.6% | SHY +6.6% |
   | Short, 2022 | −4.0% | SHY −3.9%, VGSH −3.8% |
   | Long, 2013 | −16.1% | TLT −13.4% |
   | Long, 2022 | −30.1% | TLT −31.2% |

   Repricing once a year at a fixed maturity overstated short-bond losses in 2022 (−6.2%). The prototype is a throwaway script in the session scratchpad, not a committed file.
2. **Maturities:**
   - Short = 2-year (VGSH holds 1–3 years).
   - Intermediate = 5-year (VGIT holds 3–10 years, about 5-year duration).
   - Long = 25-year (TLT holds 20+ years, averaging about 25).

   A 20-year bond understated TLT's swings by about 4.5 points a year on average across 2008/2009/2013/2022. A 25-year bond missed by about 2.4.
3. **Coverage 1954–2025.** FRED's constant-maturity yields start in April 1953, so 1954 is the first full calendar year. For the short and intermediate maturities no free, credible pre-1953 source exists; FRED's NBER mirrors hold long-bond and T-bill yields only. A year is only usable when every maturity has a value, so no pre-1954 rows are seeded. The spec's §4.1 fallback therefore applies, and **plans 3 and 4 own it**:
   - `include_depression_years` is disabled while a tent is configured.
   - Replays fall back to long-run averages for missing years.
   - The start-year picker offers 1954 onward only.
4. **A tent-only enum and table**, not new `AssetClass` values. `AssetClass.values()` drives the return matrix's columns, allocation validation and the security-classification CHECK constraints. Adding values there would change behaviour outside the tent.
5. **The derivation script is committed** at `scripts/data/derive_bond_maturity_returns.py`, so next year's refresh is one command.

## Global Constraints

**Data**
- Real annual total returns, CPI-deflated exactly like the existing seed:
  - `real = (1 + nominal) / (1 + cpi) − 1`
  - `cpi(Y) = mean(CPIAUCNS months published for Y) / mean(CPIAUCNS months published for Y−1) − 1`
  - October 2025 CPI was never published (federal shutdown), so 2025 averages the 11 published months. This matches the existing seed, whose 2025 rows imply 2.630% CPI.
- **Stored maturities:**
  - `short`, `intermediate`, `long` for every year 1954–2025: 72 years × 3 = 216 rows.
  - Ultrashort is never stored; it is always the `cash` series of the matrix it is aligned to.
- Values are written with six decimals (`format(x, ".6f")`) into `numeric(9,6)`.
- Never invent or interpolate a missing year. The only interpolation allowed is the documented averaging of two neighbouring constant-maturity points in the same month (the short series before GS2 exists).

**Isolation**
- No change to `AssetClass`, `asset_class_returns`, `R__seed_asset_class_returns.sql`, `CapitalMarketAssumptionsProvider`, `DeterministicProjectionEngine`, `MonteCarloSpendingOptimizer` or any frontend file.
- All six deterministic goldens and every Monte Carlo pin stay byte-identical.
- `MonteCarloSpendingOptimizerCharacterizationTest` passes with no edits.

**Code conventions**
- Records for value types, no wildcard imports, AssertJ only.
- Test names follow `method_state_expected`.
- A PMD suppression needs an adjacent justifying comment. Resolve CPD duplication; never suppress it.

**Build gates**
- Per module, always with `-am`: `cd backend && mvn -q -T1 -pl <module> -am verify -DskipITs`.
- A full-reactor `mvn verify` gets OOM-killed in this sandbox.
- Persistence `*IntegrationTest` classes run under surefire (Testcontainers; Docker is available), so the persistence gate runs them.
- Coverage floors are never lowered.

**Commits**
- Commit directly on `main`. Never push.
- Conventional Commits, with a body for feat/fix/refactor/db.
- End each commit with a `Co-Authored-By:` trailer naming the model that wrote it, as in plan 1.

## Review Focus

1. **A scenario that opts into `include_depression_years` (matrix 1928–2025).** Aligning must fail loudly and name 1928. It must not silently shorten, shift or zero-fill rows. Pinned in Task 2 by `alignedTo_baseYearBeforeSeries_throwsNamingTheYear`.
2. **A future seed refresh that adds 2026 to `asset_class_returns` but not to `bond_maturity_returns`.** The default window becomes 1972–2026, and aligning must fail and name 2026. It must not misalign the last row. Pinned in Task 2 by `alignedTo_baseYearAfterSeries_throwsNamingTheYear`.
3. **Gaps in the source data:**
   - GS20 is blank from 1987-01 to 1993-09.
   - GS2 starts in 1976-06.
   - Daily series start in 1962.
   - October 2025 CPI is missing.

   Every year must still be priced from 12 real months through the documented fallbacks. A month with no yield pair stops the script; it is never skipped. Pinned in Task 1: the script exits non-zero on an unpriced month, and the seed test requires all 72 years.
4. **A caller that mutates the matrix it was handed.** That must not corrupt the provider's cache for the next caller. Pinned in Task 2 by `alignedTo_mutatingAReturnedMatrix_doesNotLeakIntoTheNextCall`.
5. **Someone storing an `ultrashort` row "for completeness."** The schema must reject it, so the cash series stays the single source of ultrashort returns. Pinned in Task 1 by `insert_ultrashortMaturity_violatesCheckConstraint`.

---

### Task 1: Bond maturity return series in the database

**Files:**
- Create: `scripts/data/derive_bond_maturity_returns.py`
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V083__create_bond_maturity_returns.sql`
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/R__seed_bond_maturity_returns.sql` (script output plus the header below)
- Create: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/BondMaturityReturnEntity.java`
- Create: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/BondMaturityReturnRepository.java`
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/BondMaturityReturnsSeedIntegrationTest.java`
- Modify: `docs/reference/data-model.md`. Add an entity section after `AssetClassReturnEntity`, the missing V082 row, a V083 row in the versioned table, and an `R__seed_bond_maturity_returns` row in the repeatable table.

**Interfaces:**
- Produces:
  - Table `bond_maturity_returns(id uuid, year integer, maturity text, real_return numeric(9,6), created_at, updated_at)`, with `uq_bond_maturity_returns_year_maturity UNIQUE (year, maturity)` and `chk_bond_maturity_returns_maturity CHECK (maturity IN ('short','intermediate','long'))`.
  - `BondMaturityReturnEntity extends UuidAuditable`:
    - Fields `int year`, `String maturity`, `BigDecimal realReturn` (column `real_return`, precision 9, scale 6).
    - A protected no-arg constructor and a public `(int year, String maturity, BigDecimal realReturn)` constructor.
    - Getters `getYear()`, `getMaturity()`, `getRealReturn()`.

    Mirror `AssetClassReturnEntity`.
  - `BondMaturityReturnRepository extends JpaRepository<BondMaturityReturnEntity, UUID>` with `List<BondMaturityReturnEntity> findAllByOrderByYearAscMaturityAsc()`.

- [ ] **Step 1: Write the seed integration test**

`BondMaturityReturnsSeedIntegrationTest extends AbstractIntegrationTest`, autowiring `BondMaturityReturnRepository`. Helpers: group rows into `Map<Integer, Map<String, Double>>`, and compute geometric mean and population standard deviation per maturity over 1954–2025.

```java
@Test void seed_everyYear1954To2025HasShortIntermediateAndLong()   // each year's key set == {short, intermediate, long}
@Test void seed_rowCount_isExactlyThreePerYear()                     // hasSize(216)
@Test void seed_2008_longTreasuriesRallied_shortBarelyMoved() {
    // spec §4.1 sanity shapes, real terms
    assertThat(r(2008, "long")).isGreaterThan(0.15);
    assertThat(r(2008, "intermediate")).isBetween(0.04, r(2008, "long"));
    assertThat(r(2008, "short")).isBetween(0.0, 0.06);
}
@Test void seed_2022_allMaturitiesFell_longestFellMost() {
    assertThat(r(2022, "long")).isLessThan(-0.25);
    assertThat(r(2022, "intermediate")).isBetween(r(2022, "long"), -0.08);
    assertThat(r(2022, "short")).isBetween(r(2022, "intermediate"), 0.0);
    assertThat(r(2022, "short")).isGreaterThan(r(2022, "long") / 2);  // short lost under half of long's real loss
}
@Test void seed_volatility_risesWithMaturity()                      // stdev short < intermediate < long, 1954-2025
@Test void seed_perMaturityGeometricMean_isWithinHistoricalSanityBand()
    // real geo mean 1954-2025: short in [0.0, 0.025], intermediate in [0.0, 0.03], long in [0.0, 0.04]
@Test void seed_spotValues_matchThePlanMethod() {
    // Algorithm-fidelity pins from the plan-writing prototype (2026-10-22). They prove the plan's method was
    // followed exactly; the shape tests above are the independent sanity checks.
    assertThat(r(1954, "intermediate")).isCloseTo(0.024519, within(0.000002));
    assertThat(r(2008, "long")).isCloseTo(0.261409, within(0.000002));
    assertThat(r(2022, "short")).isCloseTo(-0.111088, within(0.000002));
    assertThat(r(2022, "long")).isCloseTo(-0.352644, within(0.000002));
    assertThat(r(2025, "intermediate")).isCloseTo(0.042347, within(0.000002));
}
@Test void insert_ultrashortMaturity_violatesCheckConstraint()
    // repository.saveAndFlush(new BondMaturityReturnEntity(1900, "ultrashort", ...)) throws DataIntegrityViolationException
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -T1 -pl wealthview-persistence -am test-compile`
Expected: compilation FAILS because `BondMaturityReturnRepository` and `BondMaturityReturnEntity` do not exist.

- [ ] **Step 3: Write V083, the entity and the repository**

V083 is `CREATE TABLE IF NOT EXISTS bond_maturity_returns`, following V066. Its top comment says it holds real annual returns for the bond tent's short, intermediate and long Treasury sleeves, and that ultrashort reuses `asset_class_returns.cash`.

- [ ] **Step 4: Write `scripts/data/derive_bond_maturity_returns.py`**

The script is stdlib only and runs as `python3 -I scripts/data/derive_bond_maturity_returns.py [--data-dir DIR]`.

**Inputs**
- It downloads `https://fred.stlouisfed.org/graph/fredgraph.csv?id=<ID>` for:
  - Monthly averages: `GS1 GS2 GS3 GS5 GS10 GS20 GS30`.
  - Daily series: `DGS1 DGS2 DGS3 DGS5 DGS10 DGS20 DGS30`.
  - CPI: `CPIAUCNS`.
- When `--data-dir` holds those CSVs, it reads them from there instead of downloading.
- A blank or `.` value is missing.
- A daily series' month-end value is the last non-missing observation in that month.

**Output**
- The SQL `VALUES` rows for 1954–2025, in the existing seed's style: `  (1954, 'short', 0.017375),`, one row per year and maturity, ordered by year then short/intermediate/long. The final row ends with `;`.
- A summary on stderr:
  - Which source and series priced each maturity, as year ranges.
  - Which years averaged fewer than 12 CPI months.
  - The cross-check result.

**Method** (exact; this is the plan's decision):

```python
MATURITY_YEARS = {"short": 2, "intermediate": 5, "long": 25}
# Per month, the first (source, series) whose yields exist at BOTH the previous month-end and this
# month-end wins. Loop sources OUTERMOST: try every series in daily month-end (DGS*) before any
# series in monthly average (GS*). A tuple means "average the members" (both months, same members),
# e.g. the 2-year point as the midpoint of the 1- and 3-year CMT.
YIELD_SERIES = {"short": [("GS2",), ("GS1", "GS3")],
                "intermediate": [("GS5",)],
                "long": [("GS20",), ("GS30",)]}

def par_price(coupon, years, y):          # semiannual coupons; yields as decimals
    if y == 0:
        return coupon * years + 1.0
    v = (1 + y / 2) ** (-2 * years)
    return coupon / y * (1 - v) + v

# month return: buy a new par bond at last month-end's yield y0, hold one month, sell at this
# month-end's yield y1 with 1/12 year less to maturity, plus one month of coupon.
r_month = par_price(y0, M - 1/12, y1) - 1 + y0 / 12
nominal(Y) = prod(1 + r_month for Jan..Dec of Y) - 1     # exit non-zero, naming the month, if any month has no pair
```

**Cross-check** (stderr, and the script exits non-zero if it fails)
- Apply the same method to `("GS10",)` with `M = 10` and deflate to real.
- Compare with the `bond` rows parsed from `backend/wealthview-persistence/src/main/resources/db/migration/R__seed_asset_class_returns.sql`, 1972–2025.
- Required: Pearson correlation ≥ 0.98 and mean absolute difference ≤ 0.015.
- Prototype result: correlation 0.989, mean absolute difference 0.0100.

- [ ] **Step 5: Generate the seed**

Run the script. Wrap its rows in `R__seed_bond_maturity_returns.sql`: `TRUNCATE TABLE bond_maturity_returns;` then `INSERT INTO bond_maturity_returns (year, maturity, real_return) VALUES` and the rows.

The header comment follows `R__seed_asset_class_returns.sql`'s style. It must state:
- The FRED series IDs and URLs, and the retrieval date.
- The method, as in Step 4.
- The maturities and the funds they represent.
- The source splices, as printed by the script.
- The CPI method, including the 11-month 2025 average.
- That ultrashort is `asset_class_returns.cash`.
- The cross-check figures.
- The fund spot checks: SHY 2008/2022, VGSH 2022, TLT 2008/2009/2013/2022. Use nominal script output against published fund returns, and record the actual figures.
- The pre-1954 finding:
  - FRED CMT starts April 1953.
  - LTGOVTBD covers long bonds from 1925, but no free, credible short- or intermediate-maturity source was found before 1953.
  - A year needs every maturity, so none are seeded; spec §4.1's fallback applies.
- Why the long series is 25-year (Builder's call 2).
- The regeneration command.

Do not hand-edit values.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && mvn -q -T1 -pl wealthview-persistence -am verify -DskipITs`
Expected: BUILD SUCCESS. All `BondMaturityReturnsSeedIntegrationTest` tests and the existing persistence tests pass, with every gate green.

If `seed_spotValues_matchThePlanMethod` fails, the script deviates from Step 4. Fix the script, not the pins.

- [ ] **Step 7: Update `docs/reference/data-model.md`**

- An entity section in the `AssetClassReturnEntity` format: fields, the two constraints, and lifecycle "Seeded by `R__seed_bond_maturity_returns` (1954–2025; truncate-and-reload). Read-only at runtime. Ultrashort is not stored: it is `asset_class_returns.cash`."
- Versioned rows:
  - `| V082 | Flag every guardrail profile stale (Monte Carlo trials now start today) |`
  - `| V083 | Create bond_maturity_returns (short/intermediate/long Treasury real returns) |`
- Repeatable row: `| R__seed_bond_maturity_returns | Real annual returns for 2-, 5- and 25-year Treasuries, 1954–2025 (FRED CMT yields, CPI-U) |`

- [ ] **Step 8: Commit**

```bash
git add scripts/data/derive_bond_maturity_returns.py \
  backend/wealthview-persistence/src/main/resources/db/migration/V083__create_bond_maturity_returns.sql \
  backend/wealthview-persistence/src/main/resources/db/migration/R__seed_bond_maturity_returns.sql \
  backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/BondMaturityReturnEntity.java \
  backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/BondMaturityReturnRepository.java \
  backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/BondMaturityReturnsSeedIntegrationTest.java \
  docs/reference/data-model.md
git commit -F <message file>   # db(persistence): add short/intermediate/long Treasury real returns, 1954-2025
```

The body states the method, the coverage, the pre-1954 finding and the cross-check result.

---

### Task 2: `BondMaturity` and `BondMaturityReturnsProvider`

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/BondMaturity.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/BondMaturityReturnsProvider.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/BondMaturityReturnsProviderTest.java`

**Interfaces:**
- Consumes: Task 1's `BondMaturityReturnRepository.findAllByOrderByYearAscMaturityAsc()` and `BondMaturityReturnEntity`. Also the existing `CapitalMarketAssumptionsProvider.RealReturnMatrix(int[] years, AssetClass[] classes, double[][] realReturns)`, whose `classes` include `AssetClass.CASH`.
- Produces (plan 3 builds on exactly these):
  - `public enum BondMaturity`, in declaration order `ULTRASHORT("ultrashort")`, `SHORT("short")`, `INTERMEDIATE("intermediate")`, `LONG("long")`:
    - `String key()`.
    - `static BondMaturity fromKey(String)`, which throws `IllegalArgumentException("Unknown bond maturity key: " + key)`.
    - Javadoc says the declaration order is shortest-first, and the tent's draw order (spec §4.3 rule 3) relies on it.
  - `@Component public class BondMaturityReturnsProvider`, constructed as `BondMaturityReturnsProvider(BondMaturityReturnRepository repository)`:
    - `public record MaturityReturnMatrix(int[] years, BondMaturity[] maturities, double[][] realReturns)`. It is nested and documented like `RealReturnMatrix`, but each call returns freshly built arrays.
    - `public MaturityReturnMatrix alignedTo(CapitalMarketAssumptionsProvider.RealReturnMatrix base)`:
      - Row `i` is `base.years()[i]`, and columns follow `BondMaturity.values()`.
      - `ULTRASHORT` is base's `CASH` value for that row; `SHORT`/`INTERMEDIATE`/`LONG` come from the table.
      - It throws `IllegalStateException` naming the first base year with no stored row, e.g. `"No bond maturity returns for 1928; the series cover 1954-2025"`.
    - `public int firstYear()` and `public int lastYear()`: the bounds of the stored series.
    - `public static Map<BondMaturity, Double> geometricMeansOf(MaturityReturnMatrix m)`: per-column compound mean, `(Π(1 + r))^(1/n) − 1`.
    - `void clearCache()` (package-private).
    - Loading happens once, cached in an `AtomicReference` like `CapitalMarketAssumptionsProvider`. Validation:
      - Empty table: `IllegalStateException("bond_maturity_returns is empty; seed data missing")`.
      - A year lacking a stored maturity: `IllegalStateException("Year <y> missing a bond maturity")`.
      - An unknown key: `fromKey`'s exception.

- [ ] **Step 1: Write the failing tests**

Fixture helpers:
- `row(int y, String m, String r)` builds a `BondMaturityReturnEntity`.
- `base(int... years)` builds a `RealReturnMatrix` with `AssetClass.values()` columns, where each row's `CASH` value is `0.001 * (year - 1950)` and the other classes are `0.0`.
- A mocked repository returning short/intermediate/long rows for 1972 and 1973.

```java
@Test void alignedTo_baseWithinSeries_rowsFollowBaseYearsAndUltrashortIsCash()
    // years == base years; maturities == BondMaturity.values(); [0][ULTRASHORT] == base CASH 1972 value (0.022);
    // [1][LONG] == the stored 1973 long value
@Test void alignedTo_baseYearBeforeSeries_throwsNamingTheYear()        // base(1971, 1972) -> ISE message contains "1971"
@Test void alignedTo_baseYearAfterSeries_throwsNamingTheYear()         // base(1973, 1974) -> ISE message contains "1974"
@Test void alignedTo_yearMissingAStoredMaturity_throws()               // 1973 lacks "long" -> ISE message contains "1973"
@Test void alignedTo_emptyTable_throws()
@Test void alignedTo_mutatingAReturnedMatrix_doesNotLeakIntoTheNextCall()
    // set first.realReturns()[0][SHORT] = 99.0; a second alignedTo(...) returns the stored value
@Test void alignedTo_calledTwice_readsTheRepositoryOnce()             // verify(repo, times(1)).findAllByOrderByYearAscMaturityAsc()
@Test void firstYearAndLastYear_reportStoredSeriesBounds()              // 1972, 1973
@Test void geometricMeansOf_computesPerMaturityCompoundMean()
    // two rows of 0.10 and -0.05 in one column -> sqrt(1.10 * 0.95) - 1, within 1e-12; ULTRASHORT == compound mean of the cash values
@Test void fromKey_unknownKey_throws()                                  // BondMaturity.fromKey("ultra") -> IllegalArgumentException
@Test void values_areDeclaredShortestFirst()                            // containsExactly(ULTRASHORT, SHORT, INTERMEDIATE, LONG)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am test-compile`
Expected: compilation FAILS because `BondMaturity` and `BondMaturityReturnsProvider` do not exist.

- [ ] **Step 3: Implement `BondMaturity` and `BondMaturityReturnsProvider`**

Implement the signatures in the Interfaces block. The geometric-mean loop must not copy `CapitalMarketAssumptionsProvider.buildGeoMeans` token for token. If CPD flags the pair, extract a shared package-private static helper rather than suppressing.

- [ ] **Step 4: Run the gates**

Run: `cd backend && mvn -q -T1 -pl wealthview-core -am verify -DskipITs`
Expected: BUILD SUCCESS, with every gate green, including JaCoCo core 90% line / 0.83 branch.

Run: `cd backend && mvn -q -T1 -pl wealthview-projection -am verify -DskipITs`
Expected: BUILD SUCCESS with no golden or Monte Carlo pin edited. This is the isolation check.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/BondMaturity.java \
  backend/wealthview-core/src/main/java/com/wealthview/core/projection/BondMaturityReturnsProvider.java \
  backend/wealthview-core/src/test/java/com/wealthview/core/projection/BondMaturityReturnsProviderTest.java
git commit -F <message file>   # feat(core): serve tent bond maturities aligned to the return matrix
```

The body says that nothing consumes the provider until plan 3, and why ultrashort is the cash column.
