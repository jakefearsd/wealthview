# Projection Realism v2 — Phase 1 (Allocation-Driven, Real-Terms Return Model) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-S&P-500 Monte Carlo and fixed-`expectedReturn` deterministic engine with one allocation-driven, real-terms return model that both engines share, so projections reflect each account's actual asset mix.

**Architecture:** A new 4-class asset model (`US_STOCK/INTL_STOCK/BOND/CASH`) with DB-seeded real historical returns. A joint block bootstrap samples one calendar-year index sequence per trial, shared across all pools; each pool's per-year real return is its allocation-weighted blend of that year's class returns. The deterministic engine uses the allocation's geometric-mean blend. Everything runs in today's dollars (real terms). Allocation is per account, auto-derived from linked holdings via a curated symbol→class classifier (user-overridable), or user-set for hypothetical accounts.

**Tech Stack:** Java 25, Spring Boot 4.1, Maven multi-module, PostgreSQL 16 (Flyway, Testcontainers), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Java 25 idioms: records for DTOs/value objects, sealed interfaces, pattern matching, `var` only when type is obvious. No wildcard imports.
- Money is `BigDecimal` (Java) / `numeric(19,4)` (PG); rates use `numeric(5,4)` or wider; **never** float/double for money. Bootstrap returns may use `double` (perf) exactly where the existing MC path already does.
- Constructor injection only; no field `@Autowired`. Return `Optional`/empty collection, never null, from public finders.
- Tests: AssertJ `assertThat` only (no JUnit `assertEquals`/`assertTrue`). Unit test names `methodUnderTest_stateOrInput_expectedResult`. Repository/IT tests use Testcontainers PostgreSQL 16 (never H2), `@DataJpaTest` + `@Testcontainers` + `@AutoConfigureTestDatabase(replace = NONE)`, extending the existing `AbstractIntegrationTest`.
- TDD: failing test first, minimal impl, refactor. One logical change per commit; conventional-commit messages with a body for `feat`/`fix`/`refactor`/`db`.
- Module dependency direction is strict: `persistence` (leaf) ← `core` ← `projection`. Entities/repositories go in `persistence`; `AssetClass`, `AssetAllocation`, `CapitalMarketAssumptionsProvider`, `SecurityClassificationService` go in `core`; bootstrap/resolver/engine changes go in `projection`.
- Flyway: schema migrations are `V066`…`V069` (V065 is the current head); immutable once committed; seed data via repeatable `R__` migrations. PG conventions: `uuid` PK `DEFAULT gen_random_uuid()`, `timestamptz` `created_at`/`updated_at`, `snake_case`, explicit constraint names.
- Quality gates (`mvn verify`): PMD, CPD, SpotBugs, Checkstyle, JaCoCo all must pass. Coverage floors: core/projection ≥90% line; never lower a branch floor. Run `mvn verify` locally (ITs included) before considering the phase done.
- Do NOT `git push`. Commit on `main` (no feature branches).

## File structure (Phase 1)

**Create:**
- `wealthview-core/.../projection/dto/AssetClass.java` — the 4-class enum.
- `wealthview-core/.../projection/dto/AssetAllocation.java` — validated weight vector + blend helpers.
- `wealthview-persistence/.../entity/AssetClassReturnEntity.java` — one (year, class, real_return) row.
- `wealthview-persistence/.../repository/AssetClassReturnRepository.java`.
- `wealthview-persistence/.../entity/SecurityAssetClassEntity.java`, `.../entity/SecurityClassOverrideEntity.java`.
- `wealthview-persistence/.../repository/SecurityAssetClassRepository.java`, `.../repository/SecurityClassOverrideRepository.java`.
- `wealthview-core/.../projection/CapitalMarketAssumptionsProvider.java` — loads/caches the real-return matrix + geometric means.
- `wealthview-core/.../projection/SecurityClassificationService.java` — symbol→class + account allocation.
- `wealthview-projection/.../PortfolioReturnResolver.java` — index-sequence × allocation → per-year real returns.
- Migrations: `V066__create_asset_class_returns.sql`, `V067__create_security_asset_class.sql`, `V068__create_security_class_override.sql`, `V069__add_allocation_to_projection_accounts.sql`, `R__seed_asset_class_returns.sql`, `R__seed_security_asset_class.sql`.

**Modify:**
- `wealthview-core/.../projection/dto/ProjectionAccountInput.java` (+ `LinkedAccountInput`, `HypotheticalAccountInput`) — add `allocation`, `expectedReturnOverride`.
- `wealthview-persistence/.../entity/ProjectionAccountEntity.java` — add `allocation` jsonb, make `expectedReturn` nullable.
- `wealthview-core/.../projection/ProjectionInputBuilder.java` — derive/attach allocation.
- `wealthview-projection/.../BlockBootstrapReturnGenerator.java` — expose index-sequence generation.
- `wealthview-projection/.../PortfolioPathGenerator.java` — drop `toNominal`, use resolver.
- `wealthview-projection/.../PoolStrategy.java` — per-pool real return in `applyGrowth`; `PoolConfig`/`create` carry allocations + CMA.
- `wealthview-projection/.../TrialSimulator.java` (+ `SimulationParameters`, `MonteCarloSpendingOptimizer` wiring) — per-pool return sequences.
- `wealthview-projection/.../DeterministicProjectionEngine.java` — default inflation 2.5%; real-terms.
- `wealthview-core/.../projection/dto/*` spending/withdrawal strategies + `wealthview-core/.../projection/tax/FederalTaxCalculator.java`, `SocialSecurityTaxCalculator.java` — real-terms + SS fixes.
- `HistoricalReturns.java` — delete (superseded by the DB-backed provider) once no references remain.

---

## Task 1: `AssetClass` enum

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/AssetClass.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/AssetClassTest.java`

**Interfaces:**
- Produces: `enum AssetClass { US_STOCK, INTL_STOCK, BOND, CASH }` with `String key()` and `static AssetClass fromKey(String)`. Keys: `us_stock`, `intl_stock`, `bond`, `cash`.

- [ ] **Step 1: Write the failing test**
```java
package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetClassTest {

    @Test
    void fromKey_validKey_returnsEnum() {
        assertThat(AssetClass.fromKey("intl_stock")).isEqualTo(AssetClass.INTL_STOCK);
    }

    @Test
    void key_forEachConstant_roundTrips() {
        for (AssetClass ac : AssetClass.values()) {
            assertThat(AssetClass.fromKey(ac.key())).isEqualTo(ac);
        }
    }

    @Test
    void fromKey_unknownKey_throws() {
        assertThatThrownBy(() -> AssetClass.fromKey("crypto"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=AssetClassTest`
Expected: FAIL — `AssetClass` does not exist / does not compile.

- [ ] **Step 3: Write minimal implementation**
```java
package com.wealthview.core.projection.dto;

public enum AssetClass {
    US_STOCK("us_stock"),
    INTL_STOCK("intl_stock"),
    BOND("bond"),
    CASH("cash");

    private final String key;

    AssetClass(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static AssetClass fromKey(String key) {
        for (AssetClass ac : values()) {
            if (ac.key.equals(key)) {
                return ac;
            }
        }
        throw new IllegalArgumentException("Unknown asset class key: " + key);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=AssetClassTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/AssetClass.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/AssetClassTest.java
git commit -m "feat(core): add AssetClass enum for allocation model"
```

---

## Task 2: `AssetAllocation` value object

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/AssetAllocation.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/AssetAllocationTest.java`

**Interfaces:**
- Consumes: `AssetClass`.
- Produces:
  - `record AssetAllocation(Map<AssetClass, BigDecimal> weights)` — compact ctor rejects null/empty and negative weights, normalizes so weights sum to 1 (scale 6, HALF_UP).
  - `double blend(Map<AssetClass, Double> perClassReturn)` — Σ wᵢ·rᵢ (missing class ⇒ 0 contribution).
  - `static AssetAllocation of(AssetClass, double, ...)` convenience via a `Map` factory `fromDoubles(Map<AssetClass, Double>)`.
  - `static AssetAllocation ALL_US` = 100% `US_STOCK` (used as the classifier default and back-compat fallback marker).

- [ ] **Step 1: Write the failing test**
```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AssetAllocationTest {

    @Test
    void ctor_unnormalizedWeights_normalizesToSumOne() {
        var alloc = new AssetAllocation(Map.of(
                AssetClass.US_STOCK, new BigDecimal("3"),
                AssetClass.BOND, new BigDecimal("1")));

        assertThat(alloc.weights().get(AssetClass.US_STOCK)).isEqualByComparingTo("0.75");
        assertThat(alloc.weights().get(AssetClass.BOND)).isEqualByComparingTo("0.25");
    }

    @Test
    void ctor_negativeWeight_throws() {
        assertThatThrownBy(() -> new AssetAllocation(Map.of(
                AssetClass.US_STOCK, new BigDecimal("-0.1"),
                AssetClass.BOND, new BigDecimal("1.1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ctor_emptyWeights_throws() {
        assertThatThrownBy(() -> new AssetAllocation(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blend_weightedReturns_computesDotProduct() {
        var alloc = AssetAllocation.fromDoubles(Map.of(
                AssetClass.US_STOCK, 0.6, AssetClass.BOND, 0.4));

        double r = alloc.blend(Map.of(AssetClass.US_STOCK, 0.10, AssetClass.BOND, 0.02));

        assertThat(r).isEqualTo(0.068, within(1e-9));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=AssetAllocationTest`
Expected: FAIL — `AssetAllocation` does not exist.

- [ ] **Step 3: Write minimal implementation**
```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

public record AssetAllocation(Map<AssetClass, BigDecimal> weights) {

    private static final int SCALE = 6;

    public AssetAllocation {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("AssetAllocation requires at least one weight");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (var e : weights.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw new IllegalArgumentException("Negative or null weight for " + e.getKey());
            }
            sum = sum.add(e.getValue());
        }
        if (sum.signum() == 0) {
            throw new IllegalArgumentException("AssetAllocation weights sum to zero");
        }
        var normalized = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
        for (var e : weights.entrySet()) {
            normalized.put(e.getKey(), e.getValue().divide(sum, SCALE, RoundingMode.HALF_UP));
        }
        weights = Map.copyOf(normalized);
    }

    public double blend(Map<AssetClass, Double> perClassReturn) {
        double total = 0.0;
        for (var e : weights.entrySet()) {
            Double r = perClassReturn.get(e.getKey());
            if (r != null) {
                total += e.getValue().doubleValue() * r;
            }
        }
        return total;
    }

    public static AssetAllocation fromDoubles(Map<AssetClass, Double> weights) {
        var bd = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
        weights.forEach((k, v) -> bd.put(k, BigDecimal.valueOf(v)));
        return new AssetAllocation(bd);
    }

    public static final AssetAllocation ALL_US =
            fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=AssetAllocationTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/AssetAllocation.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/AssetAllocationTest.java
git commit -m "feat(core): add AssetAllocation value object with normalization and blend"
```

---

## Task 3: `asset_class_returns` table + entity + repository

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V066__create_asset_class_returns.sql`
- Create: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/AssetClassReturnEntity.java`
- Create: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/AssetClassReturnRepository.java`
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/AssetClassReturnRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `AssetClassReturnRepository extends JpaRepository<AssetClassReturnEntity, UUID>` with `List<AssetClassReturnEntity> findAllByOrderByYearAscAssetClassAsc()`. Entity getters: `getYear():int`, `getAssetClass():String`, `getRealReturn():BigDecimal`.

- [ ] **Step 1: Write the migration**
```sql
-- V066: real historical annual returns per asset class, for the joint block bootstrap.
CREATE TABLE IF NOT EXISTS asset_class_returns (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    year         integer NOT NULL,
    asset_class  text NOT NULL,
    real_return  numeric(9,6) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_asset_class_returns_year_class UNIQUE (year, asset_class),
    CONSTRAINT chk_asset_class_returns_class
        CHECK (asset_class IN ('us_stock', 'intl_stock', 'bond', 'cash'))
);
```

- [ ] **Step 2: Write the entity**
```java
package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "asset_class_returns")
public class AssetClassReturnEntity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    @Column(name = "real_return", nullable = false, precision = 9, scale = 6)
    private BigDecimal realReturn;

    protected AssetClassReturnEntity() {
    }

    public AssetClassReturnEntity(int year, String assetClass, BigDecimal realReturn) {
        this.year = year;
        this.assetClass = assetClass;
        this.realReturn = realReturn;
    }

    public UUID getId() {
        return id;
    }

    public int getYear() {
        return year;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public BigDecimal getRealReturn() {
        return realReturn;
    }
}
```
> Note: `Auditable` supplies `created_at`/`updated_at` (confirm by opening `Auditable.java`; follow the same base class the other entities use).

- [ ] **Step 3: Write the repository**
```java
package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.AssetClassReturnEntity;

public interface AssetClassReturnRepository extends JpaRepository<AssetClassReturnEntity, UUID> {

    List<AssetClassReturnEntity> findAllByOrderByYearAscAssetClassAsc();
}
```

- [ ] **Step 4: Write the failing integration test**
```java
package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import static org.assertj.core.api.Assertions.assertThat;

class AssetClassReturnRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AssetClassReturnRepository repository;

    @Test
    void findAllByOrderByYearAscAssetClassAsc_returnsSortedRows() {
        repository.save(new AssetClassReturnEntity(1973, "bond", new BigDecimal("0.010000")));
        repository.save(new AssetClassReturnEntity(1972, "us_stock", new BigDecimal("0.150000")));

        var rows = repository.findAllByOrderByYearAscAssetClassAsc();

        assertThat(rows).extracting(AssetClassReturnEntity::getYear).containsExactly(1972, 1973);
    }
}
```
> Confirm the correct base class name/package for `AbstractIntegrationTest` (memory: `AbstractApiIntegrationTest` lives in the app module; the persistence module has its own `@DataJpaTest` base — open an existing `*RepositoryIntegrationTest` in `wealthview-persistence` and mirror its base class/annotations exactly).

- [ ] **Step 5: Run the test to verify it fails, then passes**

Run: `cd backend && mvn -q -pl wealthview-persistence verify -Dtest=AssetClassReturnRepositoryIntegrationTest`
Expected: FAIL before entity/repo exist (compile) → PASS once created and the Testcontainers PG runs V066.

- [ ] **Step 6: Commit**
```bash
git add backend/wealthview-persistence/src/main/resources/db/migration/V066__create_asset_class_returns.sql \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/AssetClassReturnEntity.java \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/AssetClassReturnRepository.java \
        backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/AssetClassReturnRepositoryIntegrationTest.java
git commit -m "db(persistence): add asset_class_returns table, entity, repository"
```

---

## Task 4: Curate + seed the real-return dataset

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/R__seed_asset_class_returns.sql`
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/AssetClassReturnsSeedIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 repository/table.
- Produces: a fully-populated `asset_class_returns` covering every year in `[FIRST_YEAR, LAST_YEAR]` for all four classes (no gaps), with `real_return` as CPI-adjusted decimal (e.g. `0.1541` = +15.41%).

**Data sourcing (do this before writing the SQL):** assemble real (CPI-adjusted) annual total returns for `1972–2025`:
- `us_stock` — S&P 500 total return deflated by CPI (Shiller `ie_data` dataset). The existing `sp500-real-annual-returns.csv` already holds these values for the overlapping years — reuse them for `1972–2025`.
- `bond` — 10-year US Treasury total return deflated by CPI (Shiller `ie_data` GS10 total-return column, or Damodaran's "Returns on … Treasury Bonds" table).
- `intl_stock` — MSCI EAFE gross/net total return in USD, deflated by US CPI (MSCI end-of-year index series).
- `cash` — 3-month US Treasury bill return deflated by CPI (Damodaran "T.Bill" series / FRED `TB3MS`).
Record the exact source + retrieval date in a comment block at the top of the SQL file. Values must be plain decimals at 6dp.

- [ ] **Step 1: Write the repeatable seed migration**

Structure (header + one idempotent load). Because `R__` re-runs whenever its checksum changes, make it a truncate-and-reload so edits re-seed cleanly:
```sql
-- R__seed_asset_class_returns.sql
-- Real (CPI-adjusted) annual total returns per asset class, 1972-2025.
-- Sources (retrieved 2026-07-09):
--   us_stock : Shiller ie_data (S&P 500 TR / CPI)
--   bond     : Shiller ie_data (10y UST TR / CPI)
--   intl_stock: MSCI EAFE (USD TR) / US CPI
--   cash     : Damodaran 3m T-Bill / US CPI
TRUNCATE TABLE asset_class_returns;
INSERT INTO asset_class_returns (year, asset_class, real_return) VALUES
  (1972, 'us_stock',   0.1550),
  (1972, 'intl_stock', 0.3450),
  (1972, 'bond',       0.0210),
  (1972, 'cash',       0.0060),
  -- … one row per (year, class) through 2025 …
  (2025, 'us_stock',   0.0187),
  (2025, 'intl_stock', 0.0400),
  (2025, 'bond',       0.0150),
  (2025, 'cash',       0.0130);
```
> The numeric literals above are illustrative placeholders for the row *shape only*. Replace every value with the curated figure from the cited source before running. The Step-2 test enforces completeness and sanity so wrong/missing values fail the build.

- [ ] **Step 2: Write the seed-validation integration test**
```java
package com.wealthview.persistence.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import static org.assertj.core.api.Assertions.assertThat;

class AssetClassReturnsSeedIntegrationTest extends AbstractIntegrationTest {

    private static final int FIRST_YEAR = 1972;
    private static final int LAST_YEAR = 2025;
    private static final List<String> CLASSES = List.of("us_stock", "intl_stock", "bond", "cash");

    @Autowired
    private AssetClassReturnRepository repository;

    @Test
    void seed_everyYearHasAllFourClasses() {
        var byYear = repository.findAllByOrderByYearAscAssetClassAsc().stream()
                .collect(Collectors.groupingBy(AssetClassReturnEntity::getYear,
                        Collectors.mapping(AssetClassReturnEntity::getAssetClass, Collectors.toSet())));

        for (int y = FIRST_YEAR; y <= LAST_YEAR; y++) {
            assertThat(byYear.get(y))
                    .as("year %d must have all four classes", y)
                    .containsExactlyInAnyOrderElementsOf(CLASSES);
        }
    }

    @Test
    void seed_perClassGeometricMean_isWithinHistoricalSanityBand() {
        var rows = repository.findAllByOrderByYearAscAssetClassAsc();
        Map<String, List<AssetClassReturnEntity>> byClass = rows.stream()
                .collect(Collectors.groupingBy(AssetClassReturnEntity::getAssetClass));

        // Real geometric means, generous bands from long-run literature.
        assertGeoMeanBetween(byClass.get("us_stock"), 0.04, 0.09);
        assertGeoMeanBetween(byClass.get("intl_stock"), 0.02, 0.08);
        assertGeoMeanBetween(byClass.get("bond"), 0.005, 0.045);
        assertGeoMeanBetween(byClass.get("cash"), -0.01, 0.025);
    }

    private static void assertGeoMeanBetween(List<AssetClassReturnEntity> rows, double lo, double hi) {
        double product = 1.0;
        for (var r : rows) {
            product *= (1.0 + r.getRealReturn().doubleValue());
        }
        double geoMean = Math.pow(product, 1.0 / rows.size()) - 1.0;
        assertThat(geoMean).isBetween(lo, hi);
    }
}
```

- [ ] **Step 3: Run — confirm the shape test fails until data is complete, then passes**

Run: `cd backend && mvn -q -pl wealthview-persistence verify -Dtest=AssetClassReturnsSeedIntegrationTest`
Expected: FAIL until all 54×4 rows are present and sane; PASS once curated data is loaded.

- [ ] **Step 4: Commit**
```bash
git add backend/wealthview-persistence/src/main/resources/db/migration/R__seed_asset_class_returns.sql \
        backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/AssetClassReturnsSeedIntegrationTest.java
git commit -m "db(persistence): seed curated 1972-2025 real returns for four asset classes"
```

---

## Task 5: `CapitalMarketAssumptionsProvider`

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/CapitalMarketAssumptionsProvider.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/CapitalMarketAssumptionsProviderTest.java`

**Interfaces:**
- Consumes: `AssetClassReturnRepository`, `AssetClass`.
- Produces: `CapitalMarketAssumptionsProvider` (Spring `@Component`, constructor injection) with:
  - `RealReturnMatrix matrix()` — cached; `record RealReturnMatrix(int[] years, AssetClass[] classes, double[][] realReturns)` where `realReturns[yearIdx][classIdx]`.
  - `Map<AssetClass, Double> geometricMeans()` — cached per-class real geometric mean.
  - Throws `IllegalStateException` if any year is missing a class (incomplete seed) or the table is empty.

- [ ] **Step 1: Write the failing test** (mock the repository)
```java
package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import com.wealthview.persistence.repository.AssetClassReturnRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapitalMarketAssumptionsProviderTest {

    private static AssetClassReturnEntity row(int y, String c, String r) {
        return new AssetClassReturnEntity(y, c, new BigDecimal(r));
    }

    @Test
    void matrix_completeData_buildsAlignedGrid() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01"),
                row(1973, "us_stock", "-0.05"), row(1973, "intl_stock", "-0.03"),
                row(1973, "bond", "0.03"), row(1973, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        var m = provider.matrix();

        assertThat(m.years()).containsExactly(1972, 1973);
        int us = java.util.Arrays.asList(m.classes()).indexOf(AssetClass.US_STOCK);
        assertThat(m.realReturns()[0][us]).isEqualTo(0.10, within(1e-9));
    }

    @Test
    void geometricMeans_computesPerClassCompoundMean() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01"),
                row(1973, "us_stock", "0.10"), row(1973, "intl_stock", "0.08"),
                row(1973, "bond", "0.02"), row(1973, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThat(provider.geometricMeans().get(AssetClass.US_STOCK)).isEqualTo(0.10, within(1e-9));
    }

    @Test
    void matrix_yearMissingAClass_throws() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "bond", "0.02")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThatThrownBy(provider::matrix).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=CapitalMarketAssumptionsProviderTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**
```java
package com.wealthview.core.projection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import com.wealthview.persistence.repository.AssetClassReturnRepository;

@Component
public class CapitalMarketAssumptionsProvider {

    public record RealReturnMatrix(int[] years, AssetClass[] classes, double[][] realReturns) {}

    private static final AssetClass[] CLASS_ORDER = AssetClass.values();

    private final AssetClassReturnRepository repository;
    private final AtomicReference<RealReturnMatrix> cachedMatrix = new AtomicReference<>();
    private final AtomicReference<Map<AssetClass, Double>> cachedGeoMeans = new AtomicReference<>();

    public CapitalMarketAssumptionsProvider(AssetClassReturnRepository repository) {
        this.repository = repository;
    }

    public RealReturnMatrix matrix() {
        var existing = cachedMatrix.get();
        if (existing != null) {
            return existing;
        }
        var built = buildMatrix();
        cachedMatrix.set(built);
        return built;
    }

    public Map<AssetClass, Double> geometricMeans() {
        var existing = cachedGeoMeans.get();
        if (existing != null) {
            return existing;
        }
        var built = buildGeoMeans(matrix());
        cachedGeoMeans.set(built);
        return built;
    }

    void clearCache() {
        cachedMatrix.set(null);
        cachedGeoMeans.set(null);
    }

    private RealReturnMatrix buildMatrix() {
        var rows = repository.findAllByOrderByYearAscAssetClassAsc();
        if (rows.isEmpty()) {
            throw new IllegalStateException("asset_class_returns is empty; seed data missing");
        }
        var byYear = new TreeMap<Integer, EnumMap<AssetClass, Double>>();
        for (AssetClassReturnEntity r : rows) {
            byYear.computeIfAbsent(r.getYear(), k -> new EnumMap<>(AssetClass.class))
                    .put(AssetClass.fromKey(r.getAssetClass()), r.getRealReturn().doubleValue());
        }
        var years = new ArrayList<Integer>();
        var grid = new ArrayList<double[]>();
        for (var e : byYear.entrySet()) {
            if (e.getValue().size() != CLASS_ORDER.length) {
                throw new IllegalStateException("Year " + e.getKey() + " missing an asset class");
            }
            double[] row = new double[CLASS_ORDER.length];
            for (int i = 0; i < CLASS_ORDER.length; i++) {
                row[i] = e.getValue().get(CLASS_ORDER[i]);
            }
            years.add(e.getKey());
            grid.add(row);
        }
        return new RealReturnMatrix(years.stream().mapToInt(Integer::intValue).toArray(),
                CLASS_ORDER.clone(), grid.toArray(new double[0][]));
    }

    private static Map<AssetClass, Double> buildGeoMeans(RealReturnMatrix m) {
        var means = new EnumMap<AssetClass, Double>(AssetClass.class);
        for (int c = 0; c < m.classes().length; c++) {
            double product = 1.0;
            for (double[] yearRow : m.realReturns()) {
                product *= (1.0 + yearRow[c]);
            }
            means.put(m.classes()[c], Math.pow(product, 1.0 / m.realReturns().length) - 1.0);
        }
        return Map.copyOf(means);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=CapitalMarketAssumptionsProviderTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/CapitalMarketAssumptionsProvider.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/CapitalMarketAssumptionsProviderTest.java
git commit -m "feat(core): add CapitalMarketAssumptionsProvider (aligned real-return matrix + geo means)"
```

---

## Task 6: `BlockBootstrapReturnGenerator` → index sequences

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/BlockBootstrapReturnGenerator.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/BlockBootstrapReturnGeneratorTest.java` (extend existing)

**Interfaces:**
- Consumes: nothing new.
- Produces: new method `int[] generateIndexSequence(int years, int historicalSize)` — samples row indices with the same 5-year expected-block logic. Keep the existing `generateReturnSequence(int)` working (it can delegate: `series[idx]`), so Task 6 does not break current callers before Task 15 rewires them.

- [ ] **Step 1: Write the failing test**
```java
// add to BlockBootstrapReturnGeneratorTest
@Test
void generateIndexSequence_seeded_isReproducibleAndInRange() {
    var gen1 = new BlockBootstrapReturnGenerator(new double[]{0.1, -0.2, 0.05}, 5.0, new java.util.Random(7L));
    var gen2 = new BlockBootstrapReturnGenerator(new double[]{0.1, -0.2, 0.05}, 5.0, new java.util.Random(7L));

    int[] a = gen1.generateIndexSequence(30, 3);
    int[] b = gen2.generateIndexSequence(30, 3);

    assertThat(a).containsExactly(b);
    assertThat(a).allSatisfy(i -> assertThat(i).isBetween(0, 2));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=BlockBootstrapReturnGeneratorTest#generateIndexSequence_seeded_isReproducibleAndInRange`
Expected: FAIL — method not defined.

- [ ] **Step 3: Add the method (refactor the existing loop to share logic)**
```java
public int[] generateIndexSequence(int years, int historicalSize) {
    int[] indices = new int[years];
    double blockTerminationProbability = 1.0 / expectedBlockLength;
    int currentIndex = rng.nextInt(historicalSize);
    for (int y = 0; y < years; y++) {
        if (y > 0 && rng.nextDouble() < blockTerminationProbability) {
            currentIndex = rng.nextInt(historicalSize);
        }
        indices[y] = currentIndex;
        currentIndex = (currentIndex + 1) % historicalSize;
    }
    return indices;
}
```
And keep `generateReturnSequence` behavior identical by delegating:
```java
public double[] generateReturnSequence(int years) {
    int[] idx = generateIndexSequence(years, historicalReturns.length);
    double[] sequence = new double[years];
    for (int y = 0; y < years; y++) {
        sequence[y] = historicalReturns[idx[y]];
    }
    return sequence;
}
```

- [ ] **Step 4: Run the full generator test class to verify no regression + new pass**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=BlockBootstrapReturnGeneratorTest`
Expected: PASS (existing behavior tests unchanged + new test passes).

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/BlockBootstrapReturnGenerator.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/BlockBootstrapReturnGeneratorTest.java
git commit -m "refactor(projection): expose block-bootstrap index sequences for joint sampling"
```

---

## Task 7: `PortfolioReturnResolver`

**Files:**
- Create: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PortfolioReturnResolver.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/PortfolioReturnResolverTest.java`

**Interfaces:**
- Consumes: `CapitalMarketAssumptionsProvider.RealReturnMatrix`, `AssetAllocation`, `AssetClass`.
- Produces: `PortfolioReturnResolver` (stateless):
  - `static double[] resolveReal(int[] indexSequence, AssetAllocation allocation, RealReturnMatrix matrix)` — per-year real return for a pool = Σ wᵢ·matrix[idx[y]][classIdx].
  - `static double[] fixed(int years, double realReturn)` — override path: constant real return every year.

- [ ] **Step 1: Write the failing test**
```java
package com.wealthview.projection;

import java.util.Map;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PortfolioReturnResolverTest {

    private static final AssetClass[] ORDER = AssetClass.values();

    @Test
    void resolveReal_blendsAllocationAgainstSampledYear() {
        // classes order = US, INTL, BOND, CASH
        double[][] grid = {
                {0.10, 0.05, 0.02, 0.01},   // year index 0
                {-0.20, -0.10, 0.04, 0.01}, // year index 1
        };
        var matrix = new RealReturnMatrix(new int[]{1972, 1973}, ORDER, grid);
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 0.5, AssetClass.BOND, 0.5));

        double[] r = PortfolioReturnResolver.resolveReal(new int[]{0, 1}, alloc, matrix);

        assertThat(r[0]).isEqualTo(0.06, within(1e-9));   // .5*.10 + .5*.02
        assertThat(r[1]).isEqualTo(-0.08, within(1e-9));  // .5*-.20 + .5*.04
    }

    @Test
    void fixed_returnsConstantSeries() {
        assertThat(PortfolioReturnResolver.fixed(3, 0.04)).containsExactly(0.04, 0.04, 0.04);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=PortfolioReturnResolverTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**
```java
package com.wealthview.projection;

import java.util.Arrays;
import java.util.EnumMap;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;

final class PortfolioReturnResolver {

    private PortfolioReturnResolver() {
    }

    static double[] resolveReal(int[] indexSequence, AssetAllocation allocation, RealReturnMatrix matrix) {
        var classIdx = new EnumMap<AssetClass, Integer>(AssetClass.class);
        for (int i = 0; i < matrix.classes().length; i++) {
            classIdx.put(matrix.classes()[i], i);
        }
        double[] weightByClassIdx = new double[matrix.classes().length];
        allocation.weights().forEach((cls, w) -> {
            Integer i = classIdx.get(cls);
            if (i != null) {
                weightByClassIdx[i] = w.doubleValue();
            }
        });
        double[] out = new double[indexSequence.length];
        for (int y = 0; y < indexSequence.length; y++) {
            double[] yearRow = matrix.realReturns()[indexSequence[y]];
            double r = 0.0;
            for (int c = 0; c < yearRow.length; c++) {
                r += weightByClassIdx[c] * yearRow[c];
            }
            out[y] = r;
        }
        return out;
    }

    static double[] fixed(int years, double realReturn) {
        double[] out = new double[years];
        Arrays.fill(out, realReturn);
        return out;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=PortfolioReturnResolverTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/PortfolioReturnResolver.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/PortfolioReturnResolverTest.java
git commit -m "feat(projection): add PortfolioReturnResolver (allocation blend + override)"
```

---

## Task 8: Classifier tables + entities + repositories

**Files:**
- Create: `V067__create_security_asset_class.sql`, `V068__create_security_class_override.sql` (migration dir)
- Create: `SecurityAssetClassEntity.java`, `SecurityClassOverrideEntity.java` (persistence/entity)
- Create: `SecurityAssetClassRepository.java`, `SecurityClassOverrideRepository.java` (persistence/repository)
- Test: `SecurityClassificationRepositoriesIntegrationTest.java` (persistence/test)

**Interfaces:**
- Produces:
  - `SecurityAssetClassRepository extends JpaRepository<SecurityAssetClassEntity, UUID>` with `Optional<SecurityAssetClassEntity> findBySymbol(String symbol)`.
  - `SecurityClassOverrideRepository extends JpaRepository<SecurityClassOverrideEntity, UUID>` with `Optional<SecurityClassOverrideEntity> findByTenantIdAndSymbol(UUID tenantId, String symbol)`.
  - Entity getters: `getSymbol():String`, `getAssetClass():String`; override adds `getTenantId():UUID`.

- [ ] **Step 1: Write both migrations**
```sql
-- V067__create_security_asset_class.sql : global symbol -> asset class seed map.
CREATE TABLE IF NOT EXISTS security_asset_class (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol      text NOT NULL,
    asset_class text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_security_asset_class_symbol UNIQUE (symbol),
    CONSTRAINT chk_security_asset_class_class
        CHECK (asset_class IN ('us_stock', 'intl_stock', 'bond', 'cash'))
);
```
```sql
-- V068__create_security_class_override.sql : per-tenant reclassification.
CREATE TABLE IF NOT EXISTS security_class_override (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL,
    symbol      text NOT NULL,
    asset_class text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_security_class_override_tenant_symbol UNIQUE (tenant_id, symbol),
    CONSTRAINT chk_security_class_override_class
        CHECK (asset_class IN ('us_stock', 'intl_stock', 'bond', 'cash'))
);
```

- [ ] **Step 2: Write the two entities and two repositories**

Mirror `AssetClassReturnEntity` for fields/annotations. `SecurityAssetClassEntity(symbol, assetClass)`; `SecurityClassOverrideEntity(tenantId, symbol, assetClass)` with a `@Column(name="tenant_id")` `UUID tenantId`. Repositories as specified in Interfaces.

```java
// SecurityAssetClassRepository.java
package com.wealthview.persistence.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.wealthview.persistence.entity.SecurityAssetClassEntity;

public interface SecurityAssetClassRepository extends JpaRepository<SecurityAssetClassEntity, UUID> {
    Optional<SecurityAssetClassEntity> findBySymbol(String symbol);
}
```
```java
// SecurityClassOverrideRepository.java
package com.wealthview.persistence.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.wealthview.persistence.entity.SecurityClassOverrideEntity;

public interface SecurityClassOverrideRepository extends JpaRepository<SecurityClassOverrideEntity, UUID> {
    Optional<SecurityClassOverrideEntity> findByTenantIdAndSymbol(UUID tenantId, String symbol);
}
```
> `SecurityClassOverrideEntity` stores `tenant_id` as a plain `UUID` column (not a `@ManyToOne`) to keep the classifier lookup cheap and tenant-scoped, matching the tenant-id-in-query pattern the codebase uses for tenant isolation.

- [ ] **Step 3: Write the failing integration test**
```java
package com.wealthview.persistence.repository;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.SecurityAssetClassEntity;
import com.wealthview.persistence.entity.SecurityClassOverrideEntity;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityClassificationRepositoriesIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SecurityAssetClassRepository seedRepo;
    @Autowired private SecurityClassOverrideRepository overrideRepo;

    @Test
    void findBySymbol_returnsSeededClass() {
        seedRepo.save(new SecurityAssetClassEntity("BND", "bond"));
        assertThat(seedRepo.findBySymbol("BND")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("bond");
    }

    @Test
    void findByTenantIdAndSymbol_returnsOverride() {
        var tenant = UUID.randomUUID();
        overrideRepo.save(new SecurityClassOverrideEntity(tenant, "XYZ", "intl_stock"));
        assertThat(overrideRepo.findByTenantIdAndSymbol(tenant, "XYZ")).get()
                .extracting(SecurityClassOverrideEntity::getAssetClass).isEqualTo("intl_stock");
    }
}
```

- [ ] **Step 4: Run to verify fail → pass**

Run: `cd backend && mvn -q -pl wealthview-persistence verify -Dtest=SecurityClassificationRepositoriesIntegrationTest`
Expected: FAIL (compile) → PASS after entities/repos/migrations exist.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-persistence/src/main/resources/db/migration/V067__create_security_asset_class.sql \
        backend/wealthview-persistence/src/main/resources/db/migration/V068__create_security_class_override.sql \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/SecurityAssetClassEntity.java \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/SecurityClassOverrideEntity.java \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/SecurityAssetClassRepository.java \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/SecurityClassOverrideRepository.java \
        backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/SecurityClassificationRepositoriesIntegrationTest.java
git commit -m "db(persistence): add security_asset_class + security_class_override tables"
```

---

## Task 9: Seed the symbol→class map

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/R__seed_security_asset_class.sql`
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/SecurityAssetClassSeedIntegrationTest.java`

**Interfaces:**
- Consumes: Task 8 repository.
- Produces: seed rows for the price-seeded tickers (memory: AAPL, AMZN, BND, FXAIX, GOOG, MSFT, NVDA, SCHD, VOO, VTI, VUG, VXUS, SPAXX) plus common ETFs.

- [ ] **Step 1: Write the repeatable seed**
```sql
-- R__seed_security_asset_class.sql : curated symbol -> asset class map.
TRUNCATE TABLE security_asset_class;
INSERT INTO security_asset_class (symbol, asset_class) VALUES
  ('VOO','us_stock'), ('VTI','us_stock'), ('VUG','us_stock'), ('FXAIX','us_stock'),
  ('SCHD','us_stock'), ('AAPL','us_stock'), ('AMZN','us_stock'), ('GOOG','us_stock'),
  ('MSFT','us_stock'), ('NVDA','us_stock'),
  ('VXUS','intl_stock'), ('VEA','intl_stock'), ('VWO','intl_stock'), ('EFA','intl_stock'),
  ('BND','bond'), ('AGG','bond'), ('BNDX','bond'), ('VCIT','bond'), ('TLT','bond'),
  ('SPAXX','cash'), ('VMFXX','cash'), ('SGOV','cash'), ('BIL','cash');
```

- [ ] **Step 2: Write the seed test**
```java
package com.wealthview.persistence.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.SecurityAssetClassEntity;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityAssetClassSeedIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SecurityAssetClassRepository repo;

    @Test
    void seed_classifiesKnownTickers() {
        assertThat(repo.findBySymbol("BND")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("bond");
        assertThat(repo.findBySymbol("VXUS")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("intl_stock");
        assertThat(repo.findBySymbol("SPAXX")).get()
                .extracting(SecurityAssetClassEntity::getAssetClass).isEqualTo("cash");
    }
}
```

- [ ] **Step 3: Run fail → pass**

Run: `cd backend && mvn -q -pl wealthview-persistence verify -Dtest=SecurityAssetClassSeedIntegrationTest`
Expected: PASS once seed loaded.

- [ ] **Step 4: Commit**
```bash
git add backend/wealthview-persistence/src/main/resources/db/migration/R__seed_security_asset_class.sql \
        backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/SecurityAssetClassSeedIntegrationTest.java
git commit -m "db(persistence): seed symbol-to-asset-class classifier map"
```

---

## Task 10: `SecurityClassificationService`

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/SecurityClassificationService.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/SecurityClassificationServiceTest.java`

**Interfaces:**
- Consumes: `SecurityAssetClassRepository`, `SecurityClassOverrideRepository`, `HoldingRepository` (find holdings by account), `PriceRepository` (latest price per symbol), `AssetClass`, `AssetAllocation`.
- Produces:
  - `AssetClass classify(UUID tenantId, String symbol)` — override → seed → default `US_STOCK`.
  - `record AllocationResult(AssetAllocation allocation, Set<String> unclassifiedSymbols)`.
  - `AllocationResult deriveAllocation(UUID tenantId, UUID accountId)` — value-weighted class mix of the account's current holdings; unknown symbols default to `US_STOCK` and are collected into `unclassifiedSymbols`. Empty holdings ⇒ `AssetAllocation.ALL_US` with empty set.
- **Tenant isolation:** every query filters by `tenantId` from the caller (security context upstream), never a request param.

- [ ] **Step 1: Write the failing test** (mock repositories; verify override precedence, unknown flagging, value weighting)
```java
package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.SecurityAssetClassEntity;
import com.wealthview.persistence.entity.SecurityClassOverrideEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.SecurityAssetClassRepository;
import com.wealthview.persistence.repository.SecurityClassOverrideRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityClassificationServiceTest {

    private final SecurityAssetClassRepository seedRepo = mock(SecurityAssetClassRepository.class);
    private final SecurityClassOverrideRepository overrideRepo = mock(SecurityClassOverrideRepository.class);
    private final HoldingRepository holdingRepo = mock(HoldingRepository.class);
    private final PriceRepository priceRepo = mock(PriceRepository.class);
    private final SecurityClassificationService service =
            new SecurityClassificationService(seedRepo, overrideRepo, holdingRepo, priceRepo);

    @Test
    void classify_overrideBeatsSeed() {
        var tenant = UUID.randomUUID();
        when(overrideRepo.findByTenantIdAndSymbol(tenant, "BND"))
                .thenReturn(Optional.of(new SecurityClassOverrideEntity(tenant, "BND", "us_stock")));
        assertThat(service.classify(tenant, "BND")).isEqualTo(AssetClass.US_STOCK);
    }

    @Test
    void classify_unknownSymbol_defaultsToUsStock() {
        var tenant = UUID.randomUUID();
        when(overrideRepo.findByTenantIdAndSymbol(eq(tenant), any())).thenReturn(Optional.empty());
        when(seedRepo.findBySymbol("ZZZZ")).thenReturn(Optional.empty());
        assertThat(service.classify(tenant, "ZZZZ")).isEqualTo(AssetClass.US_STOCK);
    }

    @Test
    void deriveAllocation_valueWeightsHoldingsAndFlagsUnknown() {
        var tenant = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        // $6000 BND (bond), $4000 ZZZZ (unknown -> us_stock, flagged)
        when(holdingRepo.findByAccountId(accountId)).thenReturn(List.of(
                holding("BND", "60"), holding("ZZZZ", "40")));
        when(priceRepo.findLatestPrice("BND")).thenReturn(Optional.of(new BigDecimal("100")));
        when(priceRepo.findLatestPrice("ZZZZ")).thenReturn(Optional.of(new BigDecimal("100")));
        when(overrideRepo.findByTenantIdAndSymbol(eq(tenant), any())).thenReturn(Optional.empty());
        when(seedRepo.findBySymbol("BND"))
                .thenReturn(Optional.of(new SecurityAssetClassEntity("BND", "bond")));
        when(seedRepo.findBySymbol("ZZZZ")).thenReturn(Optional.empty());

        var result = service.deriveAllocation(tenant, accountId);

        assertThat(result.allocation().weights().get(AssetClass.BOND)).isEqualByComparingTo("0.6");
        assertThat(result.allocation().weights().get(AssetClass.US_STOCK)).isEqualByComparingTo("0.4");
        assertThat(result.unclassifiedSymbols()).containsExactly("ZZZZ");
    }

    private static HoldingEntity holding(String symbol, String qty) {
        var h = mock(HoldingEntity.class);
        when(h.getSymbol()).thenReturn(symbol);
        when(h.getQuantity()).thenReturn(new BigDecimal(qty));
        return h;
    }
}
```
> Confirm the real signatures `HoldingRepository.findByAccountId(UUID)` and `PriceRepository.findLatestPrice(String)` (open both repositories; if the price accessor differs, adapt the call and the mock — the service needs "current unit price for a symbol"). If a holding `isMoneyMarket()`, classify it as `CASH` regardless of the map (money-market funds are cash); add that branch and a test.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=SecurityClassificationServiceTest`
Expected: FAIL — service does not exist.

- [ ] **Step 3: Write minimal implementation**
```java
package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.SecurityAssetClassRepository;
import com.wealthview.persistence.repository.SecurityClassOverrideRepository;

@Service
public class SecurityClassificationService {

    public record AllocationResult(AssetAllocation allocation, Set<String> unclassifiedSymbols) {}

    private final SecurityAssetClassRepository seedRepo;
    private final SecurityClassOverrideRepository overrideRepo;
    private final HoldingRepository holdingRepo;
    private final PriceRepository priceRepo;

    public SecurityClassificationService(SecurityAssetClassRepository seedRepo,
                                         SecurityClassOverrideRepository overrideRepo,
                                         HoldingRepository holdingRepo,
                                         PriceRepository priceRepo) {
        this.seedRepo = seedRepo;
        this.overrideRepo = overrideRepo;
        this.holdingRepo = holdingRepo;
        this.priceRepo = priceRepo;
    }

    public AssetClass classify(UUID tenantId, String symbol) {
        var override = overrideRepo.findByTenantIdAndSymbol(tenantId, symbol);
        if (override.isPresent()) {
            return AssetClass.fromKey(override.get().getAssetClass());
        }
        var seed = seedRepo.findBySymbol(symbol);
        return seed.map(e -> AssetClass.fromKey(e.getAssetClass())).orElse(AssetClass.US_STOCK);
    }

    public AllocationResult deriveAllocation(UUID tenantId, UUID accountId) {
        var holdings = holdingRepo.findByAccountId(accountId);
        var weightByClass = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
        var unclassified = new LinkedHashSet<String>();
        BigDecimal total = BigDecimal.ZERO;

        for (HoldingEntity h : holdings) {
            BigDecimal price = priceRepo.findLatestPrice(h.getSymbol()).orElse(BigDecimal.ZERO);
            BigDecimal value = h.getQuantity().multiply(price);
            if (value.signum() <= 0) {
                continue;
            }
            AssetClass cls = h.isMoneyMarket() ? AssetClass.CASH : classify(tenantId, h.getSymbol());
            if (!h.isMoneyMarket() && seedRepo.findBySymbol(h.getSymbol()).isEmpty()
                    && overrideRepo.findByTenantIdAndSymbol(tenantId, h.getSymbol()).isEmpty()) {
                unclassified.add(h.getSymbol());
            }
            weightByClass.merge(cls, value, BigDecimal::add);
            total = total.add(value);
        }

        if (total.signum() == 0) {
            return new AllocationResult(AssetAllocation.ALL_US, Set.of());
        }
        return new AllocationResult(new AssetAllocation(weightByClass), unclassified);
    }
}
```
> `AssetAllocation`'s constructor normalizes the raw dollar sums to weights — no need to divide here.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=SecurityClassificationServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/SecurityClassificationService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/SecurityClassificationServiceTest.java
git commit -m "feat(core): add SecurityClassificationService (symbol->class, holdings->allocation)"
```

---

## Task 11: `projection_accounts` schema — allocation + nullable expected_return

**Files:**
- Create: `backend/wealthview-persistence/src/main/resources/db/migration/V069__add_allocation_to_projection_accounts.sql`
- Modify: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/ProjectionAccountEntity.java`
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/ProjectionAccountAllocationIntegrationTest.java`

**Interfaces:**
- Produces: `ProjectionAccountEntity` gains `Map<AssetClass-key-string, BigDecimal> allocation` stored as `jsonb` (nullable) via `@JdbcTypeCode(SqlTypes.JSON)`, and `expected_return` becomes nullable (the optional override). Getter/setter `getAllocation()/setAllocation()` returning `Map<String, BigDecimal>` (keyed by asset-class key string — the persistence module must not depend on core's `AssetClass`).

- [ ] **Step 1: Write the migration**
```sql
-- V069: per-account asset allocation (jsonb) + make expected_return an optional override.
ALTER TABLE projection_accounts ADD COLUMN IF NOT EXISTS allocation jsonb;
ALTER TABLE projection_accounts ALTER COLUMN expected_return DROP NOT NULL;
COMMENT ON COLUMN projection_accounts.allocation IS
  'Asset-class weights {us_stock,intl_stock,bond,cash}; null => derive from holdings (linked) or default.';
COMMENT ON COLUMN projection_accounts.expected_return IS
  'Optional nominal expected-return override; null => derive from allocation.';
```

- [ ] **Step 2: Write the failing integration test**
```java
package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectionAccountAllocationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ProjectionAccountRepository repo; // confirm exact repo name

    @Test
    void allocationJsonb_roundTrips() {
        var entity = newHypotheticalAccount(); // helper builds a valid scenario+account per existing fixtures
        entity.setAllocation(Map.of("us_stock", new BigDecimal("0.6"), "bond", new BigDecimal("0.4")));
        entity.setExpectedReturn(null);
        var saved = repo.saveAndFlush(entity);

        var reloaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAllocation()).containsEntry("us_stock", new BigDecimal("0.6"));
        assertThat(reloaded.getExpectedReturn()).isNull();
    }
}
```
> Use the existing projection persistence test fixtures to build a valid `ProjectionScenarioEntity` + account (grep `wealthview-persistence` tests for how scenarios are constructed). Confirm the repository class name.

- [ ] **Step 3: Modify the entity**

Add the field + accessors and drop the `nullable=false` on `expected_return`:
```java
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
// ...
    @Column(name = "expected_return", precision = 5, scale = 4)
    private BigDecimal expectedReturn;   // now nullable: optional override

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allocation")
    private Map<String, BigDecimal> allocation;   // asset-class-key -> weight; null => derive

    public Map<String, BigDecimal> getAllocation() {
        return allocation;
    }

    public void setAllocation(Map<String, BigDecimal> allocation) {
        this.allocation = allocation;
    }
```
> Remove the field initializer `= new BigDecimal("0.0700")` (default now lives at the input-building layer / is treated as "no override"). Keep the existing constructors compiling; they still accept an `expectedReturn` arg (may be null).

- [ ] **Step 4: Run fail → pass**

Run: `cd backend && mvn -q -pl wealthview-persistence verify -Dtest=ProjectionAccountAllocationIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-persistence/src/main/resources/db/migration/V069__add_allocation_to_projection_accounts.sql \
        backend/wealthview-persistence/src/main/java/com/wealthview/persistence/entity/ProjectionAccountEntity.java \
        backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/ProjectionAccountAllocationIntegrationTest.java
git commit -m "db(persistence): add per-account allocation jsonb, make expected_return nullable override"
```

---

## Task 12: Projection input DTOs — allocation + optional override

**Files:**
- Modify: `ProjectionAccountInput.java`, `LinkedAccountInput.java`, `HypotheticalAccountInput.java` (core/projection/dto)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/ProjectionAccountInputTest.java`

**Interfaces:**
- Produces: `ProjectionAccountInput` gains `AssetAllocation allocation()` and `Optional<BigDecimal> expectedReturnOverride()`. `expectedReturn()` is **removed** (callers migrate to override/derived). Subtype records updated accordingly.

- [ ] **Step 1: Write the failing test**
```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectionAccountInputTest {

    @Test
    void hypothetical_carriesAllocationAndOptionalOverride() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 1.0));
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), new BigDecimal("100"),
                alloc, Optional.of(new BigDecimal("0.07")), "taxable");

        assertThat(acct.allocation()).isEqualTo(alloc);
        assertThat(acct.expectedReturnOverride()).contains(new BigDecimal("0.07"));
    }

    @Test
    void linked_defaultsToEmptyOverride() {
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.BOND, 1.0));
        ProjectionAccountInput acct = new LinkedAccountInput(
                java.util.UUID.randomUUID(), new BigDecimal("1000"), new BigDecimal("0"),
                alloc, Optional.empty(), "traditional");

        assertThat(acct.expectedReturnOverride()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=ProjectionAccountInputTest`
Expected: FAIL — records don't have these components.

- [ ] **Step 3: Update the sealed interface + records**
```java
// ProjectionAccountInput.java
public sealed interface ProjectionAccountInput
        permits LinkedAccountInput, HypotheticalAccountInput {
    BigDecimal initialBalance();
    BigDecimal annualContribution();
    AssetAllocation allocation();
    Optional<BigDecimal> expectedReturnOverride();
    String accountType();
}
```
```java
// HypotheticalAccountInput.java
public record HypotheticalAccountInput(
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        AssetAllocation allocation,
        Optional<BigDecimal> expectedReturnOverride,
        String accountType
) implements ProjectionAccountInput {}
```
```java
// LinkedAccountInput.java
public record LinkedAccountInput(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        AssetAllocation allocation,
        Optional<BigDecimal> expectedReturnOverride,
        String accountType
) implements ProjectionAccountInput {}
```

- [ ] **Step 4: Fix compile fallout in call sites (they still reference `expectedReturn()`)**

Compile the module to surface every broken caller, then fix each (`PoolStrategy` weighted-return, `ProjectionInputBuilder`, test fixtures). These are addressed concretely in Tasks 13–14; for now update only what's needed to compile the DTO test.
Run: `cd backend && mvn -q -pl wealthview-core test-compile`
Expected: compile errors listing exact call sites — note them for Tasks 13–14.

- [ ] **Step 5: Run the DTO test to verify it passes** (once core compiles)

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=ProjectionAccountInputTest`
Expected: PASS.

- [ ] **Step 6: Commit**
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionAccountInput.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/LinkedAccountInput.java \
        backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/HypotheticalAccountInput.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/ProjectionAccountInputTest.java
git commit -m "feat(core): projection account input carries AssetAllocation + optional return override"
```

---

## Task 13: Wire allocation into `ProjectionInputBuilder`

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionInputBuilder.java` (constructor + `toAccountInput`, lines ~151-165)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionInputBuilderTest.java` (extend/create)

**Interfaces:**
- Consumes: `SecurityClassificationService`, updated `ProjectionAccountInput` records, `ProjectionAccountEntity.getAllocation()`.
- Produces: `toAccountInput` sets `allocation` (stored entity allocation → parse to `AssetAllocation`; else linked → `deriveAllocation`; else `AssetAllocation.ALL_US` default for hypothetical) and `expectedReturnOverride` (= `Optional.ofNullable(entity.getExpectedReturn())`). Unclassified symbols bubble up (stored on the builder result or logged; full response wiring is Task 18).

- [ ] **Step 1: Write the failing test** (mock `SecurityClassificationService` + collaborators)
```java
// ProjectionInputBuilderTest: linked account with no stored allocation derives from holdings
@Test
void toAccountInput_linkedNoStoredAllocation_derivesFromHoldings() {
    // arrange: entity has linkedAccount, allocation=null, expected_return=null
    // when classificationService.deriveAllocation(tenant, linkedId) -> (BOND 100%, {})
    // act: build input
    // assert: the resulting LinkedAccountInput.allocation() weight BOND == 1.0
    //         and expectedReturnOverride() is empty
}
```
> Flesh this out against the real builder fixtures (grep existing `ProjectionInputBuilder` tests). Include a second test: `entity.expectedReturn != null` ⇒ `expectedReturnOverride` present; and a third: hypothetical with stored `allocation` jsonb ⇒ parsed allocation used.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=ProjectionInputBuilderTest`
Expected: FAIL.

- [ ] **Step 3: Implement the wiring**

Add `SecurityClassificationService` to the constructor. Add a helper `parseAllocation(Map<String,BigDecimal>)` → `AssetAllocation` (map keys via `AssetClass.fromKey`), and rewrite `toAccountInput`:
```java
private ProjectionAccountInput toAccountInput(ProjectionAccountEntity entity, UUID tenantId) {
    Optional<BigDecimal> override = Optional.ofNullable(entity.getExpectedReturn());
    if (entity.getLinkedAccount() != null) {
        var nativeBalance = accountService.computeBalance(entity.getLinkedAccount(), tenantId);
        var liveBalance = exchangeRateService.convertToUsd(
                nativeBalance, entity.getLinkedAccount().getCurrency(), tenantId);
        AssetAllocation allocation = entity.getAllocation() != null
                ? parseAllocation(entity.getAllocation())
                : classificationService.deriveAllocation(tenantId, entity.getLinkedAccount().getId())
                        .allocation();
        return new LinkedAccountInput(entity.getLinkedAccount().getId(), liveBalance,
                entity.getAnnualContribution(), allocation, override, entity.getAccountType());
    }
    AssetAllocation allocation = entity.getAllocation() != null
            ? parseAllocation(entity.getAllocation())
            : AssetAllocation.ALL_US;
    return new HypotheticalAccountInput(entity.getInitialBalance(),
            entity.getAnnualContribution(), allocation, override, entity.getAccountType());
}

private static AssetAllocation parseAllocation(Map<String, BigDecimal> raw) {
    var weights = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
    raw.forEach((k, v) -> weights.put(AssetClass.fromKey(k), v));
    return new AssetAllocation(weights);
}
```
> **Back-compat note:** existing accounts have `expected_return` non-null and `allocation` null → `override` present, so the deterministic path uses the override and the MC uses a fixed real return (Task 14/15) — i.e. their behavior stays deterministic-equivalent, no longer silently 100% S&P 500.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=ProjectionInputBuilderTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/ProjectionInputBuilder.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/projection/ProjectionInputBuilderTest.java
git commit -m "feat(core): derive/attach per-account allocation in ProjectionInputBuilder"
```

---

## Task 14: Deterministic engine — per-pool allocation-derived real returns

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java` (`PoolConfig`, `create`, `computeWeightedReturn`, `SinglePool`, `MultiPool` growth)
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java` (`buildPoolStrategy` passes CMA + inflation)
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/PoolStrategyReturnTest.java`

**Interfaces:**
- Consumes: `CapitalMarketAssumptionsProvider.geometricMeans()`, `AssetAllocation`, `PortfolioReturnResolver` (override→real), updated `ProjectionAccountInput`.
- Produces: a helper `static BigDecimal realReturnFor(ProjectionAccountInput acct, Map<AssetClass,Double> geoMeans, BigDecimal inflationRate)`:
  - override present → `((1+override_nominal)/(1+inflation)) - 1` (nominal→real);
  - else → `Σ wᵢ·geoMeanᵢ` (already real).
  `MultiPool` grows each pool with its own type-weighted real return; `SinglePool` uses its accounts' blended real return. `getWeightedReturn()` retained (returns the pool's aggregate real return) for DTO/back-compat.

- [ ] **Step 1: Write the failing test**
```java
package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import static org.assertj.core.api.Assertions.assertThat;

class PoolStrategyReturnTest {

    private static final Map<AssetClass, Double> GEO = Map.of(
            AssetClass.US_STOCK, 0.07, AssetClass.INTL_STOCK, 0.06,
            AssetClass.BOND, 0.02, AssetClass.CASH, 0.005);

    @Test
    void realReturnFor_allocation_blendsGeometricMeans() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 0.5, AssetClass.BOND, 0.5)),
                Optional.empty(), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, new BigDecimal("0.025"));

        assertThat(r.doubleValue()).isCloseTo(0.045, org.assertj.core.data.Offset.offset(1e-6)); // .5*.07+.5*.02
    }

    @Test
    void realReturnFor_override_convertsNominalToReal() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.of(new BigDecimal("0.07")), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, new BigDecimal("0.025"));

        // (1.07/1.025)-1 = 0.043902...
        assertThat(r.doubleValue()).isCloseTo(0.0439024, org.assertj.core.data.Offset.offset(1e-6));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=PoolStrategyReturnTest`
Expected: FAIL — `realReturnFor` undefined.

- [ ] **Step 3: Implement**

- Add `Map<AssetClass,Double> geoMeans` and `BigDecimal inflationRate` to `PoolConfig`; thread from `DeterministicProjectionEngine.buildPoolStrategy` (inject `CapitalMarketAssumptionsProvider`).
- Add `PoolStrategy.realReturnFor(...)` per the Interfaces contract.
- Replace `computeWeightedReturn` (which read `account.expectedReturn()`, now removed) with a version that averages `realReturnFor(acct, geoMeans, inflation)` weighted by `initialBalance`. For `MultiPool`, compute a per-type weighted real return (group accounts by type; each pool grows with its own).
- `SinglePool.applyGrowth` / `MultiPool.applyGrowth` unchanged in shape — they already multiply by a stored per-pool `weightedReturn`; now that value is real and per-pool.

```java
static BigDecimal realReturnFor(ProjectionAccountInput acct,
                                Map<AssetClass, Double> geoMeans, BigDecimal inflationRate) {
    if (acct.expectedReturnOverride().isPresent()) {
        BigDecimal nominal = acct.expectedReturnOverride().get();
        return BigDecimal.ONE.add(nominal)
                .divide(BigDecimal.ONE.add(inflationRate), SCALE + 4, ROUNDING)
                .subtract(BigDecimal.ONE);
    }
    double blended = 0.0;
    for (var e : acct.allocation().weights().entrySet()) {
        Double g = geoMeans.get(e.getKey());
        if (g != null) {
            blended += e.getValue().doubleValue() * g;
        }
    }
    return BigDecimal.valueOf(blended).setScale(SCALE + 4, ROUNDING);
}
```

- [ ] **Step 4: Run to verify pass + no projection-engine regressions**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=PoolStrategyReturnTest`
Then: `cd backend && mvn -q -pl wealthview-projection test -Dtest=DeterministicProjectionEngineBasicsTest`
Expected: new test PASS. Basics test may need fixture updates (accounts now need an allocation) — update fixtures in `DeterministicProjectionEngineTestSupport`/`ProjectionTestFixtures` to supply `AssetAllocation.ALL_US` + `Optional.empty()` (or an override) so behavior is comparable.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/PoolStrategyReturnTest.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTestSupport.java
git commit -m "feat(projection): deterministic engine uses per-pool allocation-derived real returns"
```

---

## Task 15: Monte Carlo — per-pool returns from the shared index sequence

**Files:**
- Modify: `PortfolioPathGenerator.java`, `TrialSimulator.java` (`SimulationConfig`), `MonteCarloSpendingOptimizer.java` + `OptimizationContextBuilder.java` (wire CMA + per-pool allocations), `SimulationParameters.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialSimulatorReturnTest.java`

**Interfaces:**
- Consumes: `CapitalMarketAssumptionsProvider.matrix()`, `BlockBootstrapReturnGenerator.generateIndexSequence`, `PortfolioReturnResolver`, per-pool `AssetAllocation` (taxable/traditional/roth) + optional overrides.
- Produces: `SimulationConfig` carries per-pool real-return **sequences** `double[] taxableReturns, traditionalReturns, rothReturns` (already real). `TrialSimulator.simulateTrial` grows each pool by its own year's return: `pools[i] *= (1 + returns_i[y])`. Drop the single `nominalReturns` array and the `toNominal` conversion.

- [ ] **Step 1: Write the failing test** (feed distinct per-pool return sequences; assert per-pool growth)
```java
// TrialSimulatorReturnTest
@Test
void simulateTrial_perPoolReturns_growEachPoolIndependently() {
    // 1 year, no withdrawals/income; taxable +10%, traditional +2%, roth +8%
    // initial: taxable=100, traditional=100, roth=100
    // expect finalBalance = 110 + 102 + 108 = 320
    // (build SimulationConfig with the three per-pool return arrays; income/floors/discretionary = 0)
    // assertThat(result.finalBalance()).isEqualTo(320.0, within(1e-6));
}
```
> Flesh out using the existing `TrialSimulator` test patterns; construct `SimulationConfig` with the new per-pool return arrays and zeroed spend/income.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=TrialSimulatorReturnTest`
Expected: FAIL (config shape / growth not per-pool).

- [ ] **Step 3: Implement**
- `SimulationConfig`: replace any single-return notion with `double[] taxableReturns, traditionalReturns, rothReturns` (real). `cashReturnRate` stays (real cash sleeve).
- `TrialSimulator.simulateTrial`: replace `double growthFactor = 1 + nominalReturns[y]; pools[0..2]*=growthFactor;` with per-pool factors; keep the down-year cash-reserve logic keyed on the *taxable* (or portfolio-weighted) pool return sign — use the traditional+taxable+roth weighted portfolio return for the `nominalReturn < 0` branch decision to preserve the bucket behavior. Document the choice.
- `PortfolioPathGenerator.generatePaths`/`generateNominalReturns`: draw ONE `int[] indexSequence` per trial via `generateIndexSequence`, then produce each pool's real return array via `PortfolioReturnResolver.resolveReal(indexSequence, poolAllocation, matrix)`; override pools use `PortfolioReturnResolver.fixed`. Remove `toNominal`.
- `OptimizationContextBuilder`/`MonteCarloSpendingOptimizer`: obtain per-pool allocations from the scenario accounts (group by type; blend within a type by balance) and pass the CMA matrix; seed handling stays (Phase 2 changes seeding source).

- [ ] **Step 4: Run to verify pass + MC characterization**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=TrialSimulatorReturnTest`
Then: `cd backend && mvn -q -pl wealthview-projection test -Dtest=MonteCarloSpendingOptimizerCharacterizationTest`
Expected: new test PASS; the characterization test **will change** (returns model changed) — update its expected values deliberately and note why in the commit body.

- [ ] **Step 5: Commit**
```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/PortfolioPathGenerator.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/TrialSimulator.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/SimulationParameters.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/OptimizationContextBuilder.java \
        backend/wealthview-projection/src/main/java/com/wealthview/projection/MonteCarloSpendingOptimizer.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/TrialSimulatorReturnTest.java \
        backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerCharacterizationTest.java
git commit -m "feat(projection): MC grows each pool from shared joint-bootstrap index sequence (real terms)"
```

---

## Task 16: Real-terms refactor — inflation default, spending, income

**Files:**
- Modify: `DeterministicProjectionEngine.java` (default inflation 2.5%), `GuardrailProfileService.java` (default inflation 2.5%)
- Modify: spending/withdrawal strategies in `wealthview-core/.../projection/strategy/` + `TierBasedSpendingPlan.java`; income math in `wealthview-projection/.../IncomeYearMath.java`
- Test: update `DeterministicProjectionEngineTaxTest` / add `RealTermsSpendingTest`

**Interfaces:**
- Produces: everything is today's-dollars. `inflationRate` default `0.025`. Spending is constant-real (drop `(1+inflation)^n` escalators). Income: real factor `(1+source)^n / (1+inflation)^n`.

- [ ] **Step 1: Write failing tests** capturing the new semantics:
```java
// RealTermsSpendingTest (projection): with inflation=2.5% and a fixed 4% withdrawal,
// year-2 real withdrawal equals year-1 (no nominal escalation), i.e. constant real.
// And a no-COLA income source (source inflation 0) has real value in year n = amount/(1.025)^ (n-1).
```
> Write concrete assertions against `FixedPercentageWithdrawal` and `IncomeYearMath.nominalAmount` (rename mentally to "realAmount"): a fixed-nominal source deflates; a COLA source (source rate == scenario inflation) stays constant real.

- [ ] **Step 2: Run to verify fail**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=RealTermsSpendingTest`
Expected: FAIL.

- [ ] **Step 3: Implement**
- `DeterministicProjectionEngine.resolveProjectionParams`: `BigDecimal inflationRate = input.inflationRate() != null ? input.inflationRate() : new BigDecimal("0.025");`
- `GuardrailProfileService`: default inflation `0.025` where it currently defaults to ZERO.
- `FixedPercentageWithdrawal`: drop `previousWithdrawal * (1+inflation)`; return `previousWithdrawal` (constant real) after year 1.
- `TierBasedSpendingPlan.computeInflationFactor`: return `1.0` for the real-terms base case (essential/discretionary constant real); keep an explicit per-tier *real* escalation input only if a tier defines one (default 0).
- `IncomeYearMath.nominalAmount`: convert to real: `real = amount * (1+source)^ (n-1) / (1+inflation)^ (n-1)`. Thread scenario `inflationRate` into the call (add a parameter).
> These are behavior changes by design (Tier-1 #4). Golden files change in Task 19.

- [ ] **Step 4: Run to verify pass**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=RealTermsSpendingTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add -A backend/wealthview-projection backend/wealthview-core
git commit -m "refactor(projection): run projection in real terms (today's dollars), default inflation 2.5%"
```

---

## Task 17: Real-terms taxes + Social Security fixes

**Files:**
- Modify: `wealthview-core/.../projection/tax/FederalTaxCalculator.java` (drop year-fallback indexing use in the projection path; brackets constant-real), `SocialSecurityTaxCalculator.java` (deflate thresholds + tier-1 fix), `CombinedTaxCalculator.java` (deflate SALT cap)
- Test: `SocialSecurityTaxCalculatorTest.java` (extend), `FederalTaxRealTermsTest.java`

**Interfaces:**
- Produces:
  - `SocialSecurityTaxCalculator.computeTaxableAmount(ssBenefit, otherIncome, filingStatus, int yearsFromBase, BigDecimal inflationRate)` — thresholds deflated by `(1+inflation)^yearsFromBase`; tier-1 component = `min(0.5*benefits, 0.5*(tier2-tier1))`.
  - Tax charged uses base-year seeded brackets/standard deduction as-is (constant real) — remove reliance on inflated fallbacks in the projection call path.

- [ ] **Step 1: Write failing tests**
```java
// SocialSecurityTaxCalculatorTest additions:
@Test
void computeTaxableAmount_lowBenefitHighOtherIncome_usesMinOfHalfBenefits() {
    var calc = new SocialSecurityTaxCalculator();
    // MFJ, benefit 8000, other income 45000 (provisional 49000 > 44000 tier2)
    // correct: tier1 = min(0.5*8000=4000, 6000)=4000; tier2 = 0.85*(49000-44000)=4250
    //          taxable = min(8250, 0.85*8000=6800) => 6800  (cap binds here)
    // Use benefit 8000, other income 40000 (provisional 44000 == tier2 boundary path)
    // to exercise the corrected tier-1 min without the cap masking it.
    BigDecimal taxable = calc.computeTaxableAmount(
            new BigDecimal("8000"), new BigDecimal("40001"),
            "married_filing_jointly", 0, new BigDecimal("0.025"));
    // provisional = 40001 + 4000 = 44001 -> both tiers:
    // tier1 = min(4000, 6000)=4000; tier2 = 0.85*(44001-44000)=0.85 -> 4000.85; cap 6800 -> 4000.85
    assertThat(taxable).isEqualByComparingTo(new BigDecimal("4000.8500"));
}

@Test
void computeTaxableAmount_futureYear_deflatesThresholds() {
    var calc = new SocialSecurityTaxCalculator();
    // 20 years out, thresholds shrink in real terms -> more SS taxable than year 0 for same real income
    BigDecimal y0 = calc.computeTaxableAmount(new BigDecimal("30000"), new BigDecimal("30000"),
            "married_filing_jointly", 0, new BigDecimal("0.025"));
    BigDecimal y20 = calc.computeTaxableAmount(new BigDecimal("30000"), new BigDecimal("30000"),
            "married_filing_jointly", 20, new BigDecimal("0.025"));
    assertThat(y20).isGreaterThan(y0);
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=SocialSecurityTaxCalculatorTest`
Expected: FAIL (signature + behavior).

- [ ] **Step 3: Implement**
- `SocialSecurityTaxCalculator`: add `yearsFromBase` + `inflationRate` params; compute `deflator = 1/(1+inflation)^yearsFromBase`; multiply the four thresholds by `deflator`; in the both-tiers branch set `tier1Amount = min(0.5*benefits, 0.5*(tier2-tier1))`. Keep a legacy 3-arg overload delegating with `yearsFromBase=0, inflation=0` if any non-projection caller exists.
- `CombinedTaxCalculator`: deflate `SALT_CAP` by the same factor (thread `yearsFromBase`/inflation; if that's a large change, at minimum stop indexing and document — SALT is minor).
- Projection tax path: ensure `computeTax`/`computeTaxWithDeduction` are called with the base seed year so brackets are constant-real (no `bracketInflationRate`, no future-year fallback inflation). Verify the Roth-optimizer ceiling call also uses base-year (constant real) so the ceiling-vs-charge inconsistency is gone.

- [ ] **Step 4: Run to verify pass**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=SocialSecurityTaxCalculatorTest`
Expected: PASS. Update callers of `computeTaxableAmount` (`IncomeSourceProcessor`) to pass `yearsFromBase` + inflation.

- [ ] **Step 5: Commit**
```bash
git add -A backend/wealthview-core
git commit -m "fix(core): real-terms taxes (constant-real brackets, deflated SS/SALT thresholds, SS tier-1 fix)"
```

---

## Task 18: Surface unclassified symbols in the projection response

**Files:**
- Modify: `ProjectionResultResponse.java` (add `List<String> unclassifiedSymbols` or a per-account warning), `ProjectionInputBuilder`/service to collect them, controller mapping in `wealthview-api`
- Test: response/controller test asserting flagged symbols appear

**Interfaces:**
- Produces: the projection response includes `unclassifiedSymbols` (distinct, across accounts) so the UI (Phase 3) can prompt reclassification.

- [ ] **Step 1: Write the failing test** — a scenario with an unknown-symbol holding yields a non-empty `unclassifiedSymbols` in the response DTO.

- [ ] **Step 2: Run to verify fail.** `cd backend && mvn -q -pl wealthview-core test -Dtest=...`

- [ ] **Step 3: Implement** — thread `SecurityClassificationService.AllocationResult.unclassifiedSymbols()` from `ProjectionInputBuilder` into the input/result; add the field to `ProjectionResultResponse` (record component; update its factory + all constructors; keep JSON snake_case). Map through the controller.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit**
```bash
git commit -am "feat(api): surface unclassified holding symbols in projection response"
```

---

## Task 19: Full verify — golden files, back-compat, gates

**Files:**
- Modify: `ProjectionGoldenFileTest` fixtures/golden JSON; add `ExistingScenarioBackCompatIntegrationTest`
- Test: whole backend `mvn verify`

**Interfaces:**
- Consumes: all prior tasks.

- [ ] **Step 1: Regenerate golden files deliberately**

Run the golden-file test, inspect diffs (values shift due to real-terms + allocation returns), confirm each change is explained by the new model, then update the golden JSON. Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=ProjectionGoldenFileTest`

- [ ] **Step 2: Write a back-compat integration test**

An existing-style scenario (accounts with `expected_return` set, `allocation` null) runs end-to-end; assert the deterministic `finalBalance` equals the pre-change deterministic result within a small tolerance (override path reproduces the old fixed-return behavior in real terms — compute the expected real-terms number explicitly).

- [ ] **Step 3: Run the whole backend verify (all gates + ITs)**

Run: `cd backend && mvn clean verify`
Expected: PASS — PMD/CPD/SpotBugs/Checkstyle clean, JaCoCo floors met (core/projection ≥90% line; branch floors not lowered), all unit + Testcontainers ITs green. Fix any coverage gaps with meaningful tests (not coverage-only).

- [ ] **Step 4: Delete dead code**

Remove `HistoricalReturns.java` and `sp500-real-annual-returns.csv` once no references remain (`grep -rn HistoricalReturns backend/`); re-run `mvn -q -pl wealthview-projection test`.

- [ ] **Step 5: Commit**
```bash
git add -A backend
git commit -m "test(projection): regenerate golden files + back-compat test for realism v2 phase 1"
```

---

## Self-review notes (author)

- **Spec coverage:** Tier‑1 #1 → Tasks 1–15, 18; #4 (inflation/real-terms + tax indexing) → Tasks 16–17. Tier‑1 #2/#3 (success/confidence/reproducibility/tax-aware reporting) are **Phase 2** (separate plan) — intentionally out of this document.
- **Deferred to Phase 2 plan:** essentials-funded success flag in `TrialSimulator`, `SustainabilitySearch` objective change, confidence preset recalibration + `targetSuccessProbability`, seed = hash(scenario inputs), and tax-aware `GuardrailResponseBuilder` reporting. Note: Task 15 leaves current seeding as-is; Phase 2 replaces it.
- **Type consistency:** `AssetClass.fromKey`/`.key()`, `AssetAllocation.fromDoubles`/`.weights()`/`.blend()`, `CapitalMarketAssumptionsProvider.matrix()/.geometricMeans()` + `RealReturnMatrix`, `PortfolioReturnResolver.resolveReal/.fixed`, `PoolStrategy.realReturnFor`, `SecurityClassificationService.deriveAllocation/.classify` + `AllocationResult` — used consistently across tasks.
- **Verify-before-real-signatures:** Tasks flag the handful of accessors to confirm against current code before coding (`AbstractIntegrationTest` base in persistence; `HoldingRepository.findByAccountId`; `PriceRepository` latest-price accessor; `ProjectionAccountRepository` name; `Auditable` timestamp base).
