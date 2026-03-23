# Dynamic Sequencing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 4 withdrawal order options with 2 (Taxable First + Dynamic Sequencing), where DS draws Traditional up to a bracket ceiling before switching to Taxable then Roth, with IRMAA warning at age 63+.

**Architecture:** Dynamic Sequencing adds a bracket-aware withdrawal strategy that draws from Traditional IRA up to a user-chosen tax bracket ceiling (10%/12%/22%), then Taxable, then Roth. The bracket space calculation accounts for Roth conversions and RMDs that already consumed space. IRMAA warning flags years where total income exceeds the 22% bracket after age 63. Changes touch the enum, PoolStrategy, MC optimizer's splitWithdrawal, RothConversionOptimizer's processSpendingWithdrawal, the deterministic engine's param parsing, and the frontend WithdrawalStrategySection.

**Tech Stack:** Java 21 / Spring Boot 3.3 / JUnit 5 + Mockito + AssertJ / React 18 + TypeScript + Vitest

**Spec:** `docs/superpowers/specs/2026-03-23-dynamic-sequencing-design.md`

---

## File Map

### Modified Files (Backend)

| File | Changes |
|------|---------|
| `backend/wealthview-core/.../strategy/WithdrawalOrder.java` | Add `DYNAMIC_SEQUENCING` enum value |
| `backend/wealthview-core/.../dto/GuardrailOptimizationInput.java` | Add `dynamicSequencingBracketRate` field |
| `backend/wealthview-core/.../dto/ProjectionYearDto.java` | Add `irmaaWarning` field + `withIrmaaWarning()` |
| `backend/wealthview-projection/.../PoolStrategy.java` | DS logic in `MultiPool.executeWithdrawals()` + `executeOrderedWithdrawals()` |
| `backend/wealthview-projection/.../DeterministicProjectionEngine.java` | Parse DS bracket rate from params, pass to MultiPool, compute IRMAA warning |
| `backend/wealthview-projection/.../MonteCarloSpendingOptimizer.java` | DS in `splitWithdrawal()`, pre-compute DS bracket ceilings |
| `backend/wealthview-projection/.../RothConversionOptimizer.java` | DS in `processSpendingWithdrawal()` |

### Modified Files (Frontend)

| File | Changes |
|------|---------|
| `frontend/src/components/WithdrawalStrategySection.tsx` | Replace 4 options with 2, add DS bracket dropdown |
| `frontend/src/components/ScenarioForm.tsx` | Add DS bracket rate state, persist to params_json |
| `frontend/src/types/projection.ts` | Add `irmaa_warning` to ProjectionYear |
| `frontend/src/pages/ProjectionDetailPage.tsx` | IRMAA warning indicator in Data Table |

---

## Regression Test Checkpoints

- **After Task 3:** `cd backend && mvn test -pl wealthview-core,wealthview-projection`
- **After Task 5:** `cd backend && mvn test -pl wealthview-projection` (full module)
- **After Task 7:** `cd backend && mvn clean install -DskipITs` (all modules)
- **After Task 9:** `cd frontend && npm run test && npm run build`

---

## Task 1: Add DYNAMIC_SEQUENCING to WithdrawalOrder enum

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/strategy/WithdrawalOrder.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/strategy/WithdrawalOrderTest.java`

- [ ] **Step 1: Write failing test**

Add to `WithdrawalOrderTest.java`:
```java
@Test
void fromString_dynamicSequencing_returnsDynamicSequencing() {
    assertThat(WithdrawalOrder.fromString("dynamic_sequencing"))
            .isEqualTo(WithdrawalOrder.DYNAMIC_SEQUENCING);
}
```

- [ ] **Step 2: Run test — fails (no DYNAMIC_SEQUENCING value)**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=WithdrawalOrderTest`

- [ ] **Step 3: Add enum value**

```java
public enum WithdrawalOrder {
    TAXABLE_FIRST,
    TRADITIONAL_FIRST,
    ROTH_FIRST,
    PRO_RATA,
    DYNAMIC_SEQUENCING;

    public static WithdrawalOrder fromString(String value) {
        if (value == null) {
            return TAXABLE_FIRST;
        }
        return switch (value.toLowerCase(Locale.US)) {
            case "taxable_first" -> TAXABLE_FIRST;
            case "traditional_first" -> TRADITIONAL_FIRST;
            case "roth_first" -> ROTH_FIRST;
            case "pro_rata" -> PRO_RATA;
            case "dynamic_sequencing" -> DYNAMIC_SEQUENCING;
            default -> TAXABLE_FIRST;
        };
    }
}
```

- [ ] **Step 4: Run test — passes**
- [ ] **Step 5: Commit**

```
feat(core): add DYNAMIC_SEQUENCING to WithdrawalOrder enum
```

---

## Task 2: Add DS bracket rate to data model records

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationInput.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/GuardrailOptimizationRequest.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/GuardrailProfileService.java`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java` (ScenarioParams + parseParams)

- [ ] **Step 1: Add field to GuardrailOptimizationInput**

Add `BigDecimal dynamicSequencingBracketRate` at the end of the record. Update ALL constructor call sites to pass `null` for backward compatibility. Key files to search:
- `GuardrailProfileService.java` → `buildOptimizationInput()` (~line 253)
- `MonteCarloSpendingOptimizerTest.java` → all `new GuardrailOptimizationInput(` calls
- `GuardrailProfileServiceTest.java`, `GuardrailControllerTest.java`

- [ ] **Step 2: Add field to GuardrailOptimizationRequest**

Add `BigDecimal dynamicSequencingBracketRate` at the end. Update `GuardrailProfileService.buildOptimizationInput()` to read it with default `null`:
```java
request.dynamicSequencingBracketRate()  // pass through to input
```

Also update `reoptimize()` to reconstruct the request with the DS bracket rate from the scenario params.

- [ ] **Step 3: Add field to ScenarioParams in DeterministicProjectionEngine**

Add `BigDecimal dynamicSequencingBracketRate` to the `ScenarioParams` record. In `parseParams()`, read it:
```java
BigDecimal dynamicSequencingBracketRate = parseOptionalBigDecimal(node, "dynamic_sequencing_bracket_rate");
```

- [ ] **Step 4: Run tests — all pass (new field defaults to null)**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-projection`

- [ ] **Step 5: Commit**

```
feat(core): add dynamicSequencingBracketRate to data model records

Added to GuardrailOptimizationInput, GuardrailOptimizationRequest,
ScenarioParams, and GuardrailProfileService wiring.
```

---

## Task 3: Implement DS in PoolStrategy.MultiPool

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTest.java`

- [ ] **Step 1: Write failing tests**

Add tests that create a projection with `withdrawal_order: "dynamic_sequencing"` and `dynamic_sequencing_bracket_rate: 0.12` in params_json. Verify:

1. `dynamicSequencing_drawsTraditionalUpToBracket` — Traditional drawn first up to 12% bracket ceiling, remainder from Taxable
2. `dynamicSequencing_traditionalSmallerThanBracketSpace_drawsAllTraditionalThenTaxable` — When Traditional < bracket space, draws all Traditional then Taxable
3. `dynamicSequencing_conversionExceedsDsBracket_noTraditionalWithdrawal` — When Roth conversion fills past the DS bracket, Traditional withdrawal for spending is $0
4. `dynamicSequencing_beforeAge60_taxableOnly` — Before 59.5 proxy, DS behaves as Taxable First
5. `dynamicSequencing_atAge75WithRmd_bracketSpaceReducedByRmd` — RMD consumes bracket space, DS Traditional withdrawal reduced accordingly

- [ ] **Step 2: Run tests — fail (DS not implemented)**

- [ ] **Step 3: Implement DS in MultiPool**

Add `BigDecimal dynamicSequencingBracketRate` to `MultiPool` constructor. The `executeWithdrawals()` method already receives `conversionAmount` — but it does NOT currently receive `rmdAmount`. For DS, RMDs consume bracket space. Two options:
- Add `rmdAmount` as a new parameter to `executeWithdrawals()` (changes the `PoolStrategy` interface)
- Or track RMD amount inside `MultiPool` as state set before `executeWithdrawals()` is called

Recommended: pass RMD amount as a parameter since the engine already knows it. Update the `PoolStrategy` interface and `SinglePool` implementation (just ignores it).

In `executeWithdrawals()`, when `withdrawalOrder == DYNAMIC_SEQUENCING`:

```java
case DYNAMIC_SEQUENCING -> {
    // Note: uses computeMaxIncomeForTargetRate (TaxCalculationStrategy interface),
    // NOT computeMaxIncomeForBracket (FederalTaxCalculator concrete class).
    // PoolStrategy only has access to TaxCalculationStrategy.
    // Inflation indexing is NOT available here — it's handled in the MC optimizer path.
    BigDecimal bracketCeiling = taxCalculator.computeMaxIncomeForTargetRate(
            dynamicSequencingBracketRate, year, filingStatus);
    BigDecimal bracketSpace = bracketCeiling.subtract(effectiveOtherIncome)
            .subtract(conversionAmount).subtract(rmdAmount).max(BigDecimal.ZERO);
    BigDecimal tradDraw = bracketSpace.min(traditional).min(totalNeed);
    BigDecimal remaining = totalNeed.subtract(tradDraw);
    BigDecimal taxDraw = remaining.min(taxable);
    remaining = remaining.subtract(taxDraw);
    BigDecimal rothDraw = remaining.min(roth);
    fromTaxable = taxDraw;
    fromTraditional = tradDraw;
    fromRoth = rothDraw;
}
```

Update `buildPoolStrategy()` in `DeterministicProjectionEngine` to pass `dynamicSequencingBracketRate` from `ScenarioParams` to `MultiPool`. Update the engine's call to `executeWithdrawals()` to pass `rmdAmount`.

- [ ] **Step 4: Run tests — pass**
- [ ] **Step 5: Commit**

```
feat(projection): implement Dynamic Sequencing in PoolStrategy.MultiPool

Draws Traditional up to bracket ceiling, then Taxable, then Roth.
Bracket space accounts for conversions and other income. Before age
60 (59.5 proxy), falls back to taxable-only behavior.
```

---

## Task 4: Implement DS in MonteCarloSpendingOptimizer.splitWithdrawal()

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/MonteCarloSpendingOptimizer.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/MonteCarloSpendingOptimizerTest.java`

- [ ] **Step 1: Write failing tests**

Add to the existing `splitWithdrawal` test:
```java
// dynamic_sequencing with bracket space: Traditional first up to ceiling
var ds = MonteCarloSpendingOptimizer.splitWithdrawal(
        100, 200, 300, 150, "dynamic_sequencing", false, 180, 30, 0, 0);
// bracketSpace = 180 - 30 - 0 - 0 = 150. Draw 150 from Traditional.
assertThat(ds[0]).isEqualTo(0);   // taxable
assertThat(ds[1]).isEqualTo(150); // traditional
assertThat(ds[2]).isEqualTo(0);   // roth

// dynamic_sequencing with zero bracket space: Taxable first, then Roth
var ds0 = MonteCarloSpendingOptimizer.splitWithdrawal(
        100, 200, 300, 150, "dynamic_sequencing", false, 50, 60, 0, 0);
// bracketSpace = max(0, 50 - 60) = 0. fromTrad=0, fromTaxable=100, fromRoth=50.
assertThat(ds0[0]).isEqualTo(100);  // taxable
assertThat(ds0[1]).isEqualTo(0);    // traditional (zero bracket space)
assertThat(ds0[2]).isEqualTo(50);   // roth (remainder)

// dynamic_sequencing with preAge595: Taxable only
var dsPre = MonteCarloSpendingOptimizer.splitWithdrawal(
        100, 200, 300, 150, "dynamic_sequencing", true, 180, 30, 0, 0);
assertThat(dsPre[0]).isEqualTo(100);
assertThat(dsPre[1]).isEqualTo(0);
assertThat(dsPre[2]).isEqualTo(0);

// dynamic_sequencing with RMD consuming bracket space
var dsRmd = MonteCarloSpendingOptimizer.splitWithdrawal(
        100, 200, 300, 150, "dynamic_sequencing", false, 180, 30, 0, 100);
// bracketSpace = 180 - 30 - 0 - 100 = 50. Draw 50 from Traditional, 100 from Taxable.
assertThat(dsRmd[0]).isEqualTo(100);
assertThat(dsRmd[1]).isEqualTo(50);
assertThat(dsRmd[2]).isEqualTo(0);
```

- [ ] **Step 2: Run tests — fail (signature doesn't match)**

- [ ] **Step 3: Update splitWithdrawal signature and implement**

New signature:
```java
static double[] splitWithdrawal(double taxable, double traditional, double roth,
                                double need, String order, boolean preAge595,
                                double dsBracketCeiling, double otherIncome,
                                double conversionAmount, double rmdAmount)
```

Add DS branch:
```java
if ("dynamic_sequencing".equals(order) && !preAge595) {
    double bracketSpace = Math.max(0, dsBracketCeiling - otherIncome - conversionAmount - rmdAmount);
    double fromTrad = Math.min(bracketSpace, Math.min(Math.max(0, traditional), need));
    double remaining = need - fromTrad;
    double fromTax = Math.min(remaining, Math.max(0, taxable));
    remaining -= fromTax;
    double fromRoth = Math.min(remaining, Math.max(0, roth));
    return new double[]{fromTax, fromTrad, fromRoth};
}
```

For non-DS orders, the extra parameters are ignored (pass `0` for all four). Two call sites need updating:

1. **`isSustainable()` trial loop** (~line 931): has `conversionByYear[y]` available for `conversionAmount`, `incomeByYear[y]` for `otherIncome`. Pass `dsBracketCeilingByYear[y]` and `0` for `rmdAmount` (RMD is already handled separately in isSustainable).

2. **Final simulation loop** (~line 371): similar variables available. Pass same ceiling array indexed by `y`.

Pre-compute DS ceilings:
```java
double[] dsBracketCeilingByYear = new double[years];
if ("dynamic_sequencing".equals(withdrawalOrder) && input.dynamicSequencingBracketRate() != null) {
    for (int y = 0; y < years; y++) {
        dsBracketCeilingByYear[y] = taxCalculator.computeMaxIncomeForBracket(
                input.dynamicSequencingBracketRate(), retirementYear + y, filingStatus,
                input.inflationRate()).doubleValue();
    }
}
```

- [ ] **Step 4: Run tests — pass**
- [ ] **Step 5: Commit**

```
feat(projection): implement Dynamic Sequencing in MC optimizer splitWithdrawal

Bracket-aware Traditional-first withdrawal with pre-computed inflation-
indexed ceilings per year. Accounts for conversions, RMDs, and other
income consuming bracket space. Falls back to taxable-only before 59.5.
```

---

## Task 5: Implement DS in RothConversionOptimizer

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/RothConversionOptimizer.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/RothConversionOptimizerTest.java`

- [ ] **Step 1: Write failing test**

```java
@Test
void optimize_dynamicSequencing_lowerLifetimeTaxThanTaxableFirst() {
    var dsOptimizer = testBuilder()
            .traditional(1_000_000)
            .taxable(200_000)
            .withdrawalOrder("dynamic_sequencing")
            .dynamicSequencingBracketRate(0.12)
            .build();

    var taxFirstOptimizer = testBuilder()
            .traditional(1_000_000)
            .taxable(200_000)
            .withdrawalOrder("taxable,traditional,roth")
            .build();

    var dsResult = dsOptimizer.optimize();
    var tfResult = taxFirstOptimizer.optimize();

    assertThat(dsResult.lifetimeTaxWith())
            .as("DS should produce lower lifetime tax by drawing Traditional at low brackets")
            .isLessThanOrEqualTo(tfResult.lifetimeTaxWith());
}
```

- [ ] **Step 2: Run test — fails (DS not handled in processSpendingWithdrawal)**

- [ ] **Step 3: Implement DS in processSpendingWithdrawal and Builder**

Add `double dynamicSequencingBracketRate` to the constructor and `Builder`. In `processSpendingWithdrawal()`, check for `"dynamic_sequencing"` before `parseWithdrawalOrder()`:

```java
if ("dynamic_sequencing".equals(withdrawalOrder) && age >= EARLY_WITHDRAWAL_AGE) {
    double bracketCeiling = taxCalculator.computeMaxIncomeForBracket(
            BigDecimal.valueOf(dynamicSequencingBracketRate), calendarYear, filingStatus,
            BigDecimal.valueOf(inflationRate)).doubleValue();
    double bracketSpace = Math.max(0, bracketCeiling - effectiveOtherIncome
            - rmdAmount - conversionAmount);
    // Traditional up to bracket space
    double tradDraw = Math.min(bracketSpace, Math.min(traditional, remaining));
    traditional -= tradDraw;
    remaining -= tradDraw;
    withdrawalTax += computeIncrementalTax(tradDraw,
            effectiveOtherIncome + rmdAmount + conversionAmount, calendarYear);
    // Then Taxable
    double taxDraw = Math.min(remaining, taxable);
    taxable -= taxDraw;
    remaining -= taxDraw;
    // Then Roth
    double rothDraw = Math.min(remaining, roth);
    roth -= rothDraw;
    remaining -= rothDraw;
} else if (age >= EARLY_WITHDRAWAL_AGE) {
    // existing parseWithdrawalOrder() logic
}
```

Also update `OptimizerTestBuilder` with `dynamicSequencingBracketRate` field.

- [ ] **Step 4: Run test — passes**
- [ ] **Step 5: Run full projection module**

Run: `cd backend && mvn test -pl wealthview-projection`

- [ ] **Step 6: Commit**

```
feat(projection): implement Dynamic Sequencing in RothConversionOptimizer

processSpendingWithdrawal handles "dynamic_sequencing" string by drawing
Traditional up to inflation-indexed bracket ceiling, then Taxable, then
Roth. Bracket space accounts for conversions and RMDs.
```

---

## Task 6: Add IRMAA warning to ProjectionYearDto and engine

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionYearDto.java`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTest.java`

- [ ] **Step 1: Add `irmaaWarning` field to ProjectionYearDto**

Add `Boolean irmaaWarning` as the last field in the record. Add `withIrmaaWarning()` method following the `withTaxBreakdown()` pattern. Update ALL `with*()` methods and `simple()` factory to pass `null` for the new field.

- [ ] **Step 2: Write failing tests**

```java
@Test
void irmaaWarning_age63AboveBracket_warningTrue() {
    // Scenario: age 63+, income + conversion > 22% bracket ceiling
    // Verify irmaaWarning = true on the year DTO
}

@Test
void irmaaWarning_age62AboveBracket_warningFalse() {
    // Same income but age 62 → no warning (not in IRMAA lookback window)
}

@Test
void irmaaWarning_age63BelowBracket_warningFalse() {
    // Age 63 but income within 22% bracket → no warning
}
```

- [ ] **Step 3: Implement IRMAA warning in engine**

After computing all withdrawals and conversions for a retired year, compute total income and check against 22% bracket ceiling. Apply via `withIrmaaWarning()`.

- [ ] **Step 4: Run tests — pass**
- [ ] **Step 5: Run full build**

Run: `cd backend && mvn clean install -DskipITs`

- [ ] **Step 6: Commit**

```
feat(projection): add IRMAA warning for age 63+ above 22% bracket

ProjectionYearDto gains irmaaWarning boolean flag. Deterministic engine
computes it after all withdrawals and conversions using the 22% bracket
ceiling as a proxy for IRMAA thresholds. Display flag only — does not
alter projection logic.
```

---

## Task 7: Frontend — Replace WithdrawalStrategySection

**Files:**
- Modify: `frontend/src/components/WithdrawalStrategySection.tsx`
- Modify: `frontend/src/components/ScenarioForm.tsx`

- [ ] **Step 1: Update WithdrawalStrategySection**

Replace `WITHDRAWAL_ORDER_OPTIONS` (4 items) with 2:

```typescript
const WITHDRAWAL_ORDER_OPTIONS = [
    {
        value: 'taxable_first',
        title: 'Taxable First',
        description: 'Draw from taxable accounts first, then traditional, then Roth. Preserves tax-advantaged growth longest.',
    },
    {
        value: 'dynamic_sequencing',
        title: 'Dynamic Sequencing',
        description: 'Draws from Traditional IRA first up to the selected bracket ceiling to burn down the balance at low tax rates, then switches to Taxable. Helps manage RMDs and avoid IRMAA surcharges.',
    },
];
```

Add props for DS bracket rate:
```typescript
export interface WithdrawalStrategySectionProps {
    // ... existing props ...
    dynamicSequencingBracketRate: number;
    onDynamicSequencingBracketRateChange: (rate: number) => void;
}
```

Add bracket dropdown that appears when `dynamic_sequencing` is selected:
```tsx
{withdrawalOrder === 'dynamic_sequencing' && (
    <div style={{ marginTop: '0.5rem', marginBottom: '1rem' }}>
        <label style={labelStyle}>Traditional Withdrawal Bracket Ceiling</label>
        <select style={inputStyle}
            value={dynamicSequencingBracketRate}
            onChange={e => onDynamicSequencingBracketRateChange(parseFloat(e.target.value))}
        >
            <option value={0.10}>10%</option>
            <option value={0.12}>12%</option>
            <option value={0.22}>22%</option>
        </select>
    </div>
)}
```

- [ ] **Step 2: Update ScenarioForm**

Add state:
```typescript
const [dynamicSequencingBracketRate, setDynamicSequencingBracketRate] = useState(
    parsedParams.dynamic_sequencing_bracket_rate ?? 0.12
);
```

Include in params_json when submitting:
```typescript
...(withdrawalOrder === 'dynamic_sequencing' ? {
    dynamic_sequencing_bracket_rate: dynamicSequencingBracketRate,
} : {}),
```

Pass new props to `WithdrawalStrategySection`.

- [ ] **Step 3: Add frontend test**

Create or update `frontend/src/components/WithdrawalStrategySection.test.tsx`:
- Test that exactly 2 withdrawal order options render (not 4)
- Test that bracket dropdown appears only when DS is selected
- Test that bracket dropdown is hidden when Taxable First is selected

- [ ] **Step 4: Build and test frontend**

Run: `cd frontend && npm run test -- --run && npm run build`

- [ ] **Step 5: Commit**

```
feat(frontend): replace 4 withdrawal order options with Taxable First + Dynamic Sequencing

Dynamic Sequencing card shows bracket ceiling dropdown (10%/12%/22%)
when selected. Bracket rate persisted in params_json.
```

---

## Task 8: Frontend — IRMAA warning in Data Table

**Files:**
- Modify: `frontend/src/types/projection.ts`
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx`

- [ ] **Step 1: Add type**

Add to `ProjectionYear` interface:
```typescript
irmaa_warning?: boolean;
```

- [ ] **Step 2: Add IRMAA indicator to Data Table**

In the Data Table's year row rendering, when `y.irmaa_warning` is true, add a red indicator. The simplest approach: on the year cell, append a red dot or icon with a tooltip.

```tsx
<td style={{ padding: '0.5rem' }}>
    {y.year}
    {y.irmaa_warning && (
        <span title="Income exceeds 22% bracket — review IRMAA implications for Medicare (2-year lookback)"
              style={{ color: '#d32f2f', marginLeft: 4, cursor: 'help' }}>
            &#9888;
        </span>
    )}
</td>
```

- [ ] **Step 3: Build and test frontend**

Run: `cd frontend && npm run test -- --run && npm run build`

- [ ] **Step 4: Commit**

```
feat(frontend): add IRMAA warning indicator to projection Data Table

Red warning icon with tooltip on year rows where income exceeds the
22% bracket at age 63+. Warns user to review IRMAA Medicare surcharge
implications due to the 2-year lookback rule.
```

---

## Task 9: End-to-End Manual Testing

- [ ] **Step 1: Rebuild and deploy**

```bash
cd /home/jakefear/source/wealthview
DOCKER_BUILDKIT=0 docker compose build --no-cache app
docker compose down && docker compose up -d
```

- [ ] **Step 2: Manual test checklist**

1. Navigate to Projections → edit a scenario
2. Verify Withdrawal Order shows only 2 options (Taxable First + Dynamic Sequencing)
3. Select Dynamic Sequencing → bracket dropdown appears with 10%/12%/22%
4. Set 12%, save, run projection
5. Check Data Table: Traditional should be drawn first in retired years
6. Check that years age 63+ with high income show IRMAA warning icon
7. Switch to Taxable First → verify traditional is NOT drawn first
8. Re-select Dynamic Sequencing → verify bracket rate was persisted (should show 12%)
9. Run the Spending Optimizer with conversions + DS → verify MC optimizer produces results

- [ ] **Step 3: Fix any issues found**
