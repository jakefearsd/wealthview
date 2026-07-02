# Stock Split Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make stock splits correct for transactions that arrive after a split is already applied (adjust-on-insert invariant), and fix reverse/odd-ratio rounding drift (exact multiply-then-divide).

**Architecture:** Extract a pure `SplitMath` helper used by both the existing `StockSplitService` and a new `SplitAdjustmentApplier`. The applier runs inside every transaction-creation path so any newly stored transaction is split-adjusted at insert time, writing reversible `stock_split_adjustment` rows. No schema, no frontend, no detection changes.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Data JPA, JUnit 5, Mockito, AssertJ, Testcontainers (PostgreSQL 16).

**Design doc:** `docs/superpowers/specs/2026-07-02-stock-split-correctness-design.md`

## Global Constraints

- Java 21+ idioms: records for DTOs, `var` when type is obvious, constructor injection only (no field `@Autowired`).
- TDD: write the failing test first, watch it fail, then implement. No production code without a failing test.
- Assertions use AssertJ (`assertThat(...)`), never JUnit `assertEquals`/`assertTrue`.
- Monetary/quantity values use `BigDecimal`; quantities stored at scale 4, adjustment-row `old_value`/`new_value` stored at scale 8, `RoundingMode.HALF_UP`.
- Integration/repository tests use Testcontainers PostgreSQL — never H2.
- Coverage floors (never lower): `wealthview-core` line ≥ 90%, branch ≥ 0.83.
- Conventional commits, scope `core`; body mandatory for `feat`/`fix`/`refactor`. Do NOT `git push`.
- No wildcard imports; imports ordered java.*, jakarta.*, org.*, com.*, static last.
- Existing `StockSplitService` behavior for forward splits must be preserved (regression-guarded by existing tests).

---

### Task 1: `SplitMath` exact ratio helper

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/split/SplitMath.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/split/SplitMathTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `static BigDecimal SplitMath.adjustShares(BigDecimal quantity, int numerator, int denominator)` — returns `quantity × numerator ÷ denominator`, scale 4, HALF_UP.
  - `static BigDecimal SplitMath.adjustPrice(BigDecimal closePrice, int numerator, int denominator)` — returns `closePrice × denominator ÷ numerator`, scale 4, HALF_UP.

- [ ] **Step 1: Write the failing test**

```java
package com.wealthview.core.split;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SplitMathTest {

    @Test
    void adjustShares_forwardSplit_multipliesQuantity() {
        assertThat(SplitMath.adjustShares(new BigDecimal("100"), 4, 1))
                .isEqualByComparingTo("400.0000");
    }

    @Test
    void adjustShares_reverseSplit_keepsFractionalShares() {
        assertThat(SplitMath.adjustShares(new BigDecimal("155"), 1, 10))
                .isEqualByComparingTo("15.5000");
    }

    @Test
    void adjustShares_oddRatio_isExactNoDrift() {
        // 300 shares through a 1:3 reverse split is exactly 100 — the old
        // pre-divided scale-8 ratio drifted here.
        assertThat(SplitMath.adjustShares(new BigDecimal("300"), 1, 3))
                .isEqualByComparingTo("100.0000");
    }

    @Test
    void adjustPrice_forwardSplit_dividesPrice() {
        assertThat(SplitMath.adjustPrice(new BigDecimal("400"), 4, 1))
                .isEqualByComparingTo("100.0000");
    }

    @Test
    void adjustPrice_reverseSplit_multipliesPrice() {
        assertThat(SplitMath.adjustPrice(new BigDecimal("5"), 1, 10))
                .isEqualByComparingTo("50.0000");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=SplitMathTest`
Expected: FAIL — `SplitMath` does not exist (compilation failure).

- [ ] **Step 3: Write minimal implementation**

```java
package com.wealthview.core.split;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact split-ratio arithmetic. Multiplies then divides in a single step so
 * reverse and odd ratios (1:3, 1:6, 1:7) do not accumulate the rounding error
 * a pre-divided ratio bakes in.
 */
public final class SplitMath {

    private static final int QUANTITY_SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private SplitMath() {
    }

    /** New share quantity after a {@code numerator:denominator} split. */
    public static BigDecimal adjustShares(BigDecimal quantity, int numerator, int denominator) {
        return quantity.multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), QUANTITY_SCALE, ROUNDING);
    }

    /** New per-share close price after a {@code numerator:denominator} split. */
    public static BigDecimal adjustPrice(BigDecimal closePrice, int numerator, int denominator) {
        return closePrice.multiply(BigDecimal.valueOf(denominator))
                .divide(BigDecimal.valueOf(numerator), QUANTITY_SCALE, ROUNDING);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=SplitMathTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/split/SplitMath.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/split/SplitMathTest.java
git commit -m "feat(core): add SplitMath exact ratio helper

Multiply-then-divide in a single step so reverse/odd split ratios
(1:3, 1:6) do not accumulate the rounding drift a pre-divided scale-8
ratio bakes in. Foundation for both the precision fix and the
adjust-on-insert invariant."
```

---

### Task 2: Refactor `StockSplitService` onto `SplitMath` (precision fix)

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/split/StockSplitService.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/split/StockSplitServiceTest.java`

**Interfaces:**
- Consumes: `SplitMath.adjustShares`, `SplitMath.adjustPrice` (Task 1).
- Produces: no signature change to `StockSplitService`; internal math now exact.

- [ ] **Step 1: Write the failing test**

Add to `StockSplitServiceTest` (mirror the arrange/act/assert style already in that file; the existing test already sets up mocked repositories and a `symbol`/txn fixture — reuse that setup). This test pins the reverse-split precision behavior:

```java
    @Test
    void applySplit_reverseSplitOddRatio_adjustsQuantityExactly() {
        var txn = txnWithQuantity(new BigDecimal("300"));   // helper already used in this test class
        when(stockSplitRepository.findBySymbolAndEffectiveDate("ABC", EFFECTIVE_DATE))
                .thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("ABC")).thenReturn(List.of(TENANT_ID));
        when(transactionRepository.findBySymbolAndDateOnOrBefore("ABC", EFFECTIVE_DATE))
                .thenReturn(List.of(txn));

        service.applySplit("ABC", EFFECTIVE_DATE, 1, 3, "manual");

        assertThat(txn.getQuantity()).isEqualByComparingTo("100.0000");
    }
```

> If the existing test class lacks a `txnWithQuantity(...)` helper or the exact
> field names above, adapt to the fixtures already present — the assertion
> (`300` through a `1:3` split becomes exactly `100.0000`) is the point.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=StockSplitServiceTest#applySplit_reverseSplitOddRatio_adjustsQuantityExactly`
Expected: FAIL — old pre-divided ratio yields `99.9999` (or similar drift), not `100.0000`.

- [ ] **Step 3: Implement — route math through `SplitMath`**

In `StockSplitService.applySplit`, remove the `ratio` local and stop passing it:

```java
        int txnCount = adjustTransactions(split, symbol, effectiveDate);
        int priceCount = adjustPrices(split, symbol, effectiveDate, affectedTenantIds);
```

Change `adjustTransactions` to drop the `ratio` parameter and use `SplitMath`:

```java
    private int adjustTransactions(StockSplitEntity split, String symbol, LocalDate effectiveDate) {
        var txns = transactionRepository.findBySymbolAndDateOnOrBefore(symbol, effectiveDate);
        int adjusted = 0;
        for (var txn : txns) {
            if (txn.getQuantity() == null || txn.getQuantity().signum() == 0) {
                continue;
            }
            var oldQty = txn.getQuantity();
            var newQty = SplitMath.adjustShares(oldQty, split.getNumerator(), split.getDenominator());
            txn.setQuantity(newQty);

            adjustmentRepository.save(new StockSplitAdjustmentEntity(
                    split, txn.getTenantId(), "transactions", txn.getId(),
                    "quantity",
                    oldQty.setScale(VALUE_SCALE, VALUE_ROUNDING),
                    newQty.setScale(VALUE_SCALE, VALUE_ROUNDING)));
            adjusted++;
        }
        return adjusted;
    }
```

In `adjustPrices`, replace the `inverseRatio` local and the multiply with `SplitMath`:

```java
        var prices = priceRepository.findBySymbolAndDateBefore(symbol, effectiveDate);
        int adjusted = 0;
        for (var price : prices) {
            var oldClose = price.getClosePrice();
            var newClose = SplitMath.adjustPrice(oldClose, split.getNumerator(), split.getDenominator());
            price.setClosePrice(newClose);

            adjustmentRepository.save(new StockSplitAdjustmentEntity(
                    split, anchorTenantId, "prices", priceUuid(symbol, price.getDate()),
                    "close_price",
                    oldClose.setScale(VALUE_SCALE, VALUE_ROUNDING),
                    newClose.setScale(VALUE_SCALE, VALUE_ROUNDING)));
            adjusted++;
        }
        return adjusted;
```

Delete the now-unused private helpers `ratio(int,int)` and `inverseRatio(int,int)` and the `var inverseRatio = ...` / `var ratio = ...` locals.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=StockSplitServiceTest`
Expected: PASS — new precision test passes AND all pre-existing `StockSplitServiceTest` tests stay green (forward-split behavior unchanged).

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/split/StockSplitService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/split/StockSplitServiceTest.java
git commit -m "fix(core): use exact split math in StockSplitService

Replace the pre-divided scale-8 ratio/inverseRatio with SplitMath's
single multiply-then-divide, eliminating rounding drift on reverse and
odd ratios (e.g. 1:3 of 300 shares now yields exactly 100). Forward-split
behavior is unchanged, guarded by the existing tests."
```

---

### Task 3: Repository finder for splits on/after a transaction date

**Files:**
- Modify: `backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/StockSplitRepository.java`
- Test: `backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/StockSplitRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `List<StockSplitEntity> findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc(String symbol, LocalDate date)` — applied splits for a symbol whose effective date is on/after `date`, oldest first.

- [ ] **Step 1: Write the failing test**

Add to `StockSplitRepositoryIntegrationTest` (it is `@DataJpaTest` + Testcontainers already; reuse its `stockSplitRepository` field and persistence helpers):

```java
    @Test
    void findBySymbolAndEffectiveDateGreaterThanEqual_returnsOnOrAfterOrderedAsc() {
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2014, 6, 9), 7, 1, "manual"));
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual"));
        stockSplitRepository.save(new StockSplitEntity("MSFT", LocalDate.of(2003, 2, 18), 2, 1, "manual"));

        var forOldTxn = stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", LocalDate.of(2013, 1, 1));
        assertThat(forOldTxn).extracting(StockSplitEntity::getEffectiveDate)
                .containsExactly(LocalDate.of(2014, 6, 9), LocalDate.of(2020, 8, 31));

        var forRecentTxn = stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", LocalDate.of(2020, 9, 1));
        assertThat(forRecentTxn).isEmpty();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl wealthview-persistence test -Dtest=StockSplitRepositoryIntegrationTest#findBySymbolAndEffectiveDateGreaterThanEqual_returnsOnOrAfterOrderedAsc`
Expected: FAIL — method not defined (compilation failure).

- [ ] **Step 3: Implement — add the finder**

In `StockSplitRepository`, add (Spring Data derives the query; no `@Query` needed):

```java
    List<StockSplitEntity> findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc(
            String symbol, LocalDate date);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl wealthview-persistence test -Dtest=StockSplitRepositoryIntegrationTest#findBySymbolAndEffectiveDateGreaterThanEqual_returnsOnOrAfterOrderedAsc`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-persistence/src/main/java/com/wealthview/persistence/repository/StockSplitRepository.java \
        backend/wealthview-persistence/src/test/java/com/wealthview/persistence/repository/StockSplitRepositoryIntegrationTest.java
git commit -m "feat(persistence): add finder for splits on/after a date

findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc feeds
the adjust-on-insert applier: given a new transaction's symbol and date,
it returns the applied splits that must be folded over its quantity."
```

---

### Task 4: `SplitAdjustmentApplier` (adjust-on-insert core)

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/split/SplitAdjustmentApplier.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/split/SplitAdjustmentApplierTest.java`

**Interfaces:**
- Consumes: `SplitMath.adjustShares` (Task 1); `StockSplitRepository.findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc` (Task 3); `StockSplitAdjustmentRepository.save`; `StockSplitAdjustmentEntity(split, tenantId, targetTable, targetRowId, fieldName, oldValue, newValue)`; `TransactionEntity` getters `getSymbol()/getQuantity()/getDate()/getId()/getTenantId()` and `setQuantity(BigDecimal)`.
- Produces: `void SplitAdjustmentApplier.adjustNewTransaction(TransactionEntity txn)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.wealthview.core.split;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;
import com.wealthview.persistence.entity.StockSplitEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.StockSplitAdjustmentRepository;
import com.wealthview.persistence.repository.StockSplitRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SplitAdjustmentApplierTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TXN_ID = UUID.randomUUID();
    private static final LocalDate TXN_DATE = LocalDate.of(2019, 1, 1);

    @Mock
    private StockSplitRepository stockSplitRepository;
    @Mock
    private StockSplitAdjustmentRepository adjustmentRepository;
    @InjectMocks
    private SplitAdjustmentApplier applier;

    private TransactionEntity txn(String symbol, BigDecimal quantity) {
        var t = mock(TransactionEntity.class);
        when(t.getSymbol()).thenReturn(symbol);
        when(t.getQuantity()).thenReturn(quantity);
        when(t.getDate()).thenReturn(TXN_DATE);
        when(t.getId()).thenReturn(TXN_ID);
        when(t.getTenantId()).thenReturn(TENANT_ID);
        return t;
    }

    private StockSplitEntity split(int num, int den, LocalDate date) {
        return new StockSplitEntity("AAPL", date, num, den, "manual");
    }

    @Test
    void adjustNewTransaction_noApplicableSplits_leavesQuantityUntouched() {
        var t = txn("AAPL", new BigDecimal("100"));
        when(stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", TXN_DATE))
                .thenReturn(List.of());

        applier.adjustNewTransaction(t);

        verify(t, never()).setQuantity(any());
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void adjustNewTransaction_oneSplit_scalesQuantityAndRecordsRow() {
        var t = txn("AAPL", new BigDecimal("100"));
        when(stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", TXN_DATE))
                .thenReturn(List.of(split(4, 1, LocalDate.of(2020, 8, 31))));

        applier.adjustNewTransaction(t);

        var captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(t).setQuantity(captor.capture());
        assertThat(captor.getValue()).isEqualByComparingTo("400.0000");

        var rowCaptor = ArgumentCaptor.forClass(StockSplitAdjustmentEntity.class);
        verify(adjustmentRepository).save(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getOldValue()).isEqualByComparingTo("100");
        assertThat(rowCaptor.getValue().getNewValue()).isEqualByComparingTo("400");
    }

    @Test
    void adjustNewTransaction_multipleSplits_foldsOldestFirstWithOneRowEach() {
        var t = txn("AAPL", new BigDecimal("10"));
        when(stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", TXN_DATE))
                .thenReturn(List.of(
                        split(7, 1, LocalDate.of(2014, 6, 9)),
                        split(4, 1, LocalDate.of(2020, 8, 31))));

        applier.adjustNewTransaction(t);

        var finalQty = ArgumentCaptor.forClass(BigDecimal.class);
        verify(t).setQuantity(finalQty.capture());
        assertThat(finalQty.getValue()).isEqualByComparingTo("280.0000");

        var rows = ArgumentCaptor.forClass(StockSplitAdjustmentEntity.class);
        verify(adjustmentRepository, org.mockito.Mockito.times(2)).save(rows.capture());
        assertThat(rows.getAllValues().get(0).getNewValue()).isEqualByComparingTo("70");
        assertThat(rows.getAllValues().get(1).getOldValue()).isEqualByComparingTo("70");
        assertThat(rows.getAllValues().get(1).getNewValue()).isEqualByComparingTo("280");
    }

    @Test
    void adjustNewTransaction_nullSymbol_isNoOp() {
        var t = mock(TransactionEntity.class);
        when(t.getSymbol()).thenReturn(null);
        when(t.getQuantity()).thenReturn(new BigDecimal("100"));

        applier.adjustNewTransaction(t);

        verifyNoInteractions(stockSplitRepository, adjustmentRepository);
    }

    @Test
    void adjustNewTransaction_zeroQuantity_isNoOp() {
        var t = mock(TransactionEntity.class);
        when(t.getSymbol()).thenReturn("AAPL");
        when(t.getQuantity()).thenReturn(BigDecimal.ZERO);

        applier.adjustNewTransaction(t);

        verifyNoInteractions(stockSplitRepository, adjustmentRepository);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=SplitAdjustmentApplierTest`
Expected: FAIL — `SplitAdjustmentApplier` does not exist (compilation failure).

- [ ] **Step 3: Implement `SplitAdjustmentApplier`**

```java
package com.wealthview.core.split;

import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.StockSplitAdjustmentRepository;
import com.wealthview.persistence.repository.StockSplitRepository;

/**
 * Adjusts a transaction that is created <i>after</i> the splits affecting it
 * were already applied, so holdings are correct regardless of import order.
 *
 * <p>Maintains the invariant that every stored transaction is split-adjusted.
 * One {@code stock_split_adjustment} row is written per split, oldest first,
 * so {@link StockSplitService#unapplySplit} reverses these transactions too,
 * and per-split unapply composes correctly across multiple splits.
 */
@Component
public class SplitAdjustmentApplier {

    private static final Logger log = LoggerFactory.getLogger(SplitAdjustmentApplier.class);
    private static final int VALUE_SCALE = 8;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final StockSplitRepository stockSplitRepository;
    private final StockSplitAdjustmentRepository adjustmentRepository;

    public SplitAdjustmentApplier(StockSplitRepository stockSplitRepository,
                                  StockSplitAdjustmentRepository adjustmentRepository) {
        this.stockSplitRepository = stockSplitRepository;
        this.adjustmentRepository = adjustmentRepository;
    }

    /**
     * Fold every applied split with an effective date on/after the transaction
     * date over the transaction's quantity. No-op when the transaction has no
     * symbol or no positive quantity, or when no such splits exist.
     */
    public void adjustNewTransaction(TransactionEntity txn) {
        var symbol = txn.getSymbol();
        if (symbol == null || symbol.isBlank()
                || txn.getQuantity() == null || txn.getQuantity().signum() == 0) {
            return;
        }
        var splits = stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc(symbol, txn.getDate());
        if (splits.isEmpty()) {
            return;
        }
        var running = txn.getQuantity();
        for (var split : splits) {
            var next = SplitMath.adjustShares(running, split.getNumerator(), split.getDenominator());
            adjustmentRepository.save(new StockSplitAdjustmentEntity(
                    split, txn.getTenantId(), "transactions", txn.getId(), "quantity",
                    running.setScale(VALUE_SCALE, ROUNDING),
                    next.setScale(VALUE_SCALE, ROUNDING)));
            running = next;
        }
        txn.setQuantity(running);
        log.info("Adjusted late-arriving transaction {} for {} across {} split(s)",
                txn.getId(), symbol, splits.size());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=SplitAdjustmentApplierTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/split/SplitAdjustmentApplier.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/split/SplitAdjustmentApplierTest.java
git commit -m "feat(core): add SplitAdjustmentApplier for late-arriving transactions

Folds every already-applied split (effective date on/after the txn date)
over a new transaction's quantity, writing one reversible adjustment row
per split oldest-first. Establishes the invariant that every stored
transaction is split-adjusted regardless of import order."
```

---

### Task 5: Wire the applier into all transaction-creation paths

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/transaction/TransactionService.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/transaction/TransactionServiceTest.java`

**Interfaces:**
- Consumes: `SplitAdjustmentApplier.adjustNewTransaction(TransactionEntity)` (Task 4).
- Produces: `TransactionService` constructor gains a trailing `SplitAdjustmentApplier splitAdjustmentApplier` parameter. All three create methods call `adjustNewTransaction` after `save` and before holdings recompute.

- [ ] **Step 1: Write the failing test**

Add to `TransactionServiceTest`. It already builds a `TransactionService` under test with mocked `transactionRepository`, `accountRepository`, `holdingsComputationService`, `eventPublisher` — add a `@Mock SplitAdjustmentApplier splitAdjustmentApplier` and pass it as the new last constructor argument wherever the service is constructed (update the existing setup, do not create a second instance).

```java
    @Test
    void create_adjustsForSplitsBeforeRecomputingHoldings() {
        var account = accountFixture();               // existing helper in this test class
        when(accountRepository.findByTenant_IdAndId(TENANT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var request = new TransactionRequest(LocalDate.of(2019, 1, 1), "buy", "AAPL",
                new BigDecimal("100"), new BigDecimal("8000"));

        service.create(TENANT_ID, ACCOUNT_ID, request);

        var inOrder = inOrder(splitAdjustmentApplier, holdingsComputationService);
        inOrder.verify(splitAdjustmentApplier).adjustNewTransaction(any(TransactionEntity.class));
        inOrder.verify(holdingsComputationService)
                .recomputeForAccountAndSymbol(eq(account), any(), eq("AAPL"));
    }
```

Add these static imports if not already present: `import static org.mockito.Mockito.inOrder;` and `import static org.mockito.ArgumentMatchers.eq;`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=TransactionServiceTest#create_adjustsForSplitsBeforeRecomputingHoldings`
Expected: FAIL — constructor has no `SplitAdjustmentApplier` param / `splitAdjustmentApplier` never invoked.

- [ ] **Step 3: Implement — inject and call the applier**

Add the field and constructor parameter (constructor injection, no `@Autowired`):

```java
    private final SplitAdjustmentApplier splitAdjustmentApplier;

    public TransactionService(TransactionRepository transactionRepository,
                              AccountRepository accountRepository,
                              HoldingsComputationService holdingsComputationService,
                              ApplicationEventPublisher eventPublisher,
                              SplitAdjustmentApplier splitAdjustmentApplier) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.holdingsComputationService = holdingsComputationService;
        this.eventPublisher = eventPublisher;
        this.splitAdjustmentApplier = splitAdjustmentApplier;
    }
```

Add the import: `import com.wealthview.core.split.SplitAdjustmentApplier;`

In `create`, `createWithHash`, and `createWithHashNoRecompute`, insert the call immediately after `txn = transactionRepository.save(txn);` and before any `holdingsComputationService.recompute...` call. For `create`:

```java
        txn = transactionRepository.save(txn);
        splitAdjustmentApplier.adjustNewTransaction(txn);

        holdingsComputationService.recomputeForAccountAndSymbol(
                account, account.getTenant(), request.symbol());
```

For `createWithHash` (same insertion point, before its recompute):

```java
        txn = transactionRepository.save(txn);
        splitAdjustmentApplier.adjustNewTransaction(txn);

        holdingsComputationService.recomputeForAccountAndSymbol(
                account, account.getTenant(), request.symbol());
```

For `createWithHashNoRecompute` (no recompute here — the import loop recomputes per symbol afterward):

```java
        txn = transactionRepository.save(txn);
        splitAdjustmentApplier.adjustNewTransaction(txn);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -pl wealthview-core test -Dtest=TransactionServiceTest`
Expected: PASS — new test passes and all existing `TransactionServiceTest` tests pass with the updated constructor.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/transaction/TransactionService.java \
        backend/wealthview-core/src/test/java/com/wealthview/core/transaction/TransactionServiceTest.java
git commit -m "feat(core): split-adjust transactions at creation time

TransactionService now runs SplitAdjustmentApplier after saving in
create, createWithHash, and createWithHashNoRecompute (the import path),
before holdings recompute. Transactions imported or entered after a split
was already applied are now adjusted, so holdings stay correct."
```

---

### Task 6: End-to-end integration test — late-arriving transaction

**Files:**
- Create: `backend/wealthview-app/src/test/java/com/wealthview/app/it/split/LateArrivingSplitIT.java`

**Interfaces:**
- Consumes: the full wired stack via HTTP — admin apply endpoint `POST /api/v1/admin/stock-splits`, transactions endpoint `POST /api/v1/accounts/{accountId}/transactions` (exercised through `data.createBuyTransactionOnDateAndGetId`), unapply `DELETE /api/v1/admin/stock-splits/{id}`.
- Produces: nothing (test only).

- [ ] **Step 1: Write the failing test**

Mirror `StockSplitIT`'s setup (super-admin session + brokerage account). This asserts the *late-arrival ordering*: split first, transaction second.

```java
package com.wealthview.app.it.split;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for late-arriving pre-split transactions: a split is
 * applied first, then a transaction dated before it is created. The stored
 * quantity must be split-adjusted at insert time, and unapply must restore it.
 */
class LateArrivingSplitIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_EMAIL = "late-super@wealthview.test";
    private static final String SUPER_ADMIN_PASS = "superpass123";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthHelper.Session superAdmin;
    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdmin = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        accountId = data.createBrokerageAccountAndGetId();
    }

    @Test
    void transactionImportedAfterSplit_isAdjustedThenRestoredOnUnapply() {
        // Split applied FIRST (no transactions exist yet for AAPL).
        var applyResp = restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);
        var splitId = (String) applyResp.getBody().get("id");

        // Transaction dated BEFORE the split arrives afterward (posts through
        // TransactionService.create -> SplitAdjustmentApplier).
        var txnId = data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        var adjustedQty = jdbcTemplate.queryForObject(
                "select quantity from transactions where id = ?::uuid", BigDecimal.class, txnId);
        assertThat(adjustedQty).isEqualByComparingTo("400.0000");

        var adjustmentRows = jdbcTemplate.queryForObject(
                "select count(*) from stock_split_adjustments where target_row_id = ?::uuid",
                Integer.class, txnId);
        assertThat(adjustmentRows).isEqualTo(1);

        // Unapply restores the raw quantity.
        restTemplate.exchange("/api/v1/admin/stock-splits/" + splitId, HttpMethod.DELETE,
                authHelper.authEntity(null, superAdmin.accessToken()), MAP_TYPE);

        var restoredQty = jdbcTemplate.queryForObject(
                "select quantity from transactions where id = ?::uuid", BigDecimal.class, txnId);
        assertThat(restoredQty).isEqualByComparingTo("100.0000");
    }
}
```

> Match the exact `AuthHelper`/`authEntity` signatures used by the sibling
> `StockSplitIT` in the same package; if `authEntity(null, token)` is not a
> valid overload there, use the same no-body variant `StockSplitIT` uses for
> its `DELETE` unapply call.

- [ ] **Step 2: Run test to verify it fails (on a pre-Task-5 checkout) or passes green**

Run: `cd backend && mvn -pl wealthview-app verify -Dit.test=LateArrivingSplitIT -DfailIfNoTests=false`
Expected: PASS with Tasks 1–5 in place. (If you want to see it red, the assertion `400.0000` fails against code without Task 5 because the stored quantity stays `100.0000`.)

- [ ] **Step 3: No new production code**

This task is verification-only; all production code landed in Tasks 1–5.

- [ ] **Step 4: Run the full core + app suites**

Run: `cd backend && mvn -pl wealthview-core test && mvn -pl wealthview-app verify -Dit.test=LateArrivingSplitIT,StockSplitIT -DfailIfNoTests=false`
Expected: PASS — new IT green and existing `StockSplitIT` still green (no regression to apply/unapply).

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-app/src/test/java/com/wealthview/app/it/split/LateArrivingSplitIT.java
git commit -m "test(app): cover late-arriving pre-split transaction end-to-end

Applies a split first, then creates a transaction dated before it via the
API, asserting the stored quantity is split-adjusted at insert and that
unapply restores the raw quantity."
```

---

## Final verification (after all tasks)

- [ ] Run the full gated build to confirm coverage floors and quality gates hold:

Run: `cd backend && mvn verify -DskipITs`
Expected: PASS — PMD, CPD, SpotBugs, Checkstyle, and JaCoCo (core line ≥ 90%, branch ≥ 0.83) all green.

- [ ] Run integration tests with Docker available:

Run: `cd backend && mvn verify -pl wealthview-app -Dit.test=LateArrivingSplitIT,StockSplitIT,StockSplitSyncIT,StockSplitBackfillIT -DfailIfNoTests=false`
Expected: PASS.

## Self-review notes (coverage of the spec)

- Problem 2 (precision) → Tasks 1–2. Problem 1 (late-arriving) → Tasks 3–6.
- "Keep fractional shares" → asserted in Task 1 (`155` via `1:10` → `15.5000`), never rounded to whole.
- "Reversible / unapply composes" → Task 4 multi-split test + Task 6 unapply round-trip.
- "Dedup unaffected" → unchanged hashing (documented in spec); no code touches `TransactionHashUtil`.
- Out of scope (cash-in-lieu, `TransactionService.update`, frontend, detection) → no tasks, intentionally.
