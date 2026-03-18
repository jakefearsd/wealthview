# Per-Pool Transparency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose per-pool growth, tax source breakdown, and per-pool withdrawal amounts so every dollar is auditable year-over-year in the projection data table.

**Architecture:** Enrich existing return types on PoolStrategy (GrowthResult, TaxSourceResult, expanded WithdrawalTaxResult/ConversionResult) to surface data already computed internally. Wire through DeterministicProjectionEngine into 9 new fields on ProjectionYearDto. Display in a collapsible "Pool Details" section in the frontend.

**Tech Stack:** Java 21 records, Spring Boot, React 18 / TypeScript, Vitest

**Spec:** `docs/superpowers/specs/2026-03-18-per-pool-transparency-design.md`

---

### Task 1: New Record Types on PoolStrategy

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java:77-78`

- [ ] **Step 1: Add GrowthResult and TaxSourceResult records**

Add after the existing `ConversionResult`/`WithdrawalTaxResult` records (line 77-78):

```java
record GrowthResult(BigDecimal total, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {}

record TaxSourceResult(BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth) {
    static final TaxSourceResult ZERO = new TaxSourceResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    TaxSourceResult add(TaxSourceResult other) {
        return new TaxSourceResult(
                fromTaxable.add(other.fromTaxable),
                fromTraditional.add(other.fromTraditional),
                fromRoth.add(other.fromRoth));
    }
}
```

- [ ] **Step 2: Expand WithdrawalTaxResult**

Change from:
```java
record WithdrawalTaxResult(BigDecimal totalWithdrawn, BigDecimal taxLiability) {}
```
To:
```java
record WithdrawalTaxResult(BigDecimal totalWithdrawn, BigDecimal taxLiability,
                           BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth,
                           TaxSourceResult taxSource) {}
```

- [ ] **Step 3: Expand ConversionResult**

Change from:
```java
record ConversionResult(BigDecimal amountConverted, BigDecimal taxLiability) {}
```
To:
```java
record ConversionResult(BigDecimal amountConverted, BigDecimal taxLiability, TaxSourceResult taxSource) {}
```

- [ ] **Step 4: Change applyGrowth() return type in interface**

Change:
```java
BigDecimal applyGrowth();
```
To:
```java
GrowthResult applyGrowth();
```

- [ ] **Step 5: Verify it compiles (with expected errors in implementations)**

Run: `cd backend && mvn compile -pl wealthview-projection 2>&1 | tail -5`
Expected: Compile errors in SinglePool/MultiPool implementations (not yet updated). This confirms the interface changes are picked up.

---

### Task 2: Update SinglePool Implementations

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java:82-182` (SinglePool class)

- [ ] **Step 1: Update SinglePool.applyGrowth()**

Change (line 110-113):
```java
public BigDecimal applyGrowth() {
    BigDecimal growth = balance.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    balance = balance.add(growth);
    return growth;
}
```
To:
```java
public GrowthResult applyGrowth() {
    BigDecimal growth = balance.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    balance = balance.add(growth);
    return new GrowthResult(growth, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
}
```

- [ ] **Step 2: Update SinglePool.executeWithdrawals()**

Change (line 117-124):
```java
public WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year,
                                              BigDecimal effectiveOtherIncome,
                                              BigDecimal conversionAmount) {
    BigDecimal withdrawn = need.min(balance);
    balance = balance.subtract(withdrawn);
    return new WithdrawalTaxResult(withdrawn, BigDecimal.ZERO);
}
```
To:
```java
public WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year,
                                              BigDecimal effectiveOtherIncome,
                                              BigDecimal conversionAmount) {
    BigDecimal withdrawn = need.min(balance);
    balance = balance.subtract(withdrawn);
    return new WithdrawalTaxResult(withdrawn, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
}
```

- [ ] **Step 3: Update SinglePool.executeRothConversion()**

Change (line 127-130):
```java
public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome) {
    return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO);
}
```
To:
```java
public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome) {
    return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
}
```

---

### Task 3: Update MultiPool Implementations

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java:186-445` (MultiPool class)

- [ ] **Step 1: Update MultiPool.applyGrowth()**

Change (line 265-273):
```java
public BigDecimal applyGrowth() {
    BigDecimal tradGrowth = traditional.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    BigDecimal rothGrowth = roth.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    BigDecimal taxableGrowth = taxable.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    traditional = traditional.add(tradGrowth);
    roth = roth.add(rothGrowth);
    taxable = taxable.add(taxableGrowth);
    return tradGrowth.add(rothGrowth).add(taxableGrowth);
}
```
To:
```java
public GrowthResult applyGrowth() {
    BigDecimal tradGrowth = traditional.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    BigDecimal rothGrowth = roth.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    BigDecimal taxableGrowth = taxable.multiply(weightedReturn).setScale(SCALE, ROUNDING);
    traditional = traditional.add(tradGrowth);
    roth = roth.add(rothGrowth);
    taxable = taxable.add(taxableGrowth);
    return new GrowthResult(tradGrowth.add(rothGrowth).add(taxableGrowth),
            taxableGrowth, tradGrowth, rothGrowth);
}
```

- [ ] **Step 2: Update deductFromPools() to return TaxSourceResult**

Change (line 429-444):
```java
private void deductFromPools(BigDecimal amount) {
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
        return;
    }
    BigDecimal remaining = amount;

    BigDecimal fromTax = remaining.min(taxable);
    taxable = taxable.subtract(fromTax);
    remaining = remaining.subtract(fromTax);

    BigDecimal fromTrad = remaining.min(traditional);
    traditional = traditional.subtract(fromTrad);
    remaining = remaining.subtract(fromTrad);

    roth = roth.subtract(remaining);
}
```
To:
```java
private TaxSourceResult deductFromPools(BigDecimal amount) {
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
        return TaxSourceResult.ZERO;
    }
    BigDecimal remaining = amount;

    BigDecimal fromTax = remaining.min(taxable);
    taxable = taxable.subtract(fromTax);
    remaining = remaining.subtract(fromTax);

    BigDecimal fromTrad = remaining.min(traditional);
    traditional = traditional.subtract(fromTrad);
    remaining = remaining.subtract(fromTrad);

    roth = roth.subtract(remaining);
    return new TaxSourceResult(fromTax, fromTrad, remaining);
}
```

- [ ] **Step 3: Update MultiPool.executeWithdrawals()**

Change (line 276-316) to capture per-pool withdrawal amounts and tax source in the return:

```java
@Override
public WithdrawalTaxResult executeWithdrawals(BigDecimal totalNeed, int year,
                                              BigDecimal effectiveOtherIncome,
                                              BigDecimal conversionAmount) {
    if (totalNeed.compareTo(BigDecimal.ZERO) <= 0) {
        return new WithdrawalTaxResult(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
    }

    BigDecimal fromTaxable;
    BigDecimal fromTraditional;
    BigDecimal fromRoth;

    if (withdrawalOrder == WithdrawalOrder.PRO_RATA) {
        BigDecimal total = getTotal();
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return new WithdrawalTaxResult(BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }
        BigDecimal need = totalNeed.min(total);
        fromTaxable = need.multiply(taxable).divide(total, SCALE, ROUNDING).min(taxable);
        fromTraditional = need.multiply(traditional).divide(total, SCALE, ROUNDING).min(traditional);
        fromRoth = need.subtract(fromTaxable).subtract(fromTraditional).min(roth).max(BigDecimal.ZERO);
    } else {
        var ordered = executeOrderedWithdrawals(totalNeed);
        fromTaxable = ordered[0];
        fromTraditional = ordered[1];
        fromRoth = ordered[2];
    }

    taxable = taxable.subtract(fromTaxable);
    traditional = traditional.subtract(fromTraditional);
    roth = roth.subtract(fromRoth);

    TaxSourceResult withdrawalTaxSource = TaxSourceResult.ZERO;
    BigDecimal withdrawalTax = BigDecimal.ZERO;
    if (fromTraditional.compareTo(BigDecimal.ZERO) > 0 && taxCalculator != null) {
        withdrawalTax = taxCalculator.computeTax(
                fromTraditional.add(effectiveOtherIncome), year, filingStatus);
        withdrawalTaxSource = deductFromPools(withdrawalTax);
    }

    return new WithdrawalTaxResult(
            fromTaxable.add(fromTraditional).add(fromRoth), withdrawalTax,
            fromTaxable, fromTraditional, fromRoth, withdrawalTaxSource);
}
```

- [ ] **Step 4: Update MultiPool.executeRothConversion()**

Change (line 319-349) to capture and return the TaxSourceResult:

```java
@Override
public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome) {
    if (rothConversionStartYear != null && year < rothConversionStartYear) {
        return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
    }

    BigDecimal effectiveLimit;
    if ("fill_bracket".equals(rothConversionStrategy) && targetBracketRate != null && taxCalculator != null) {
        BigDecimal bracketCeiling = taxCalculator.computeMaxIncomeForBracket(
                targetBracketRate, year, filingStatus);
        BigDecimal space = bracketCeiling.subtract(effectiveOtherIncome).max(BigDecimal.ZERO);
        effectiveLimit = space;
    } else {
        effectiveLimit = annualRothConversion;
    }

    if (effectiveLimit.compareTo(BigDecimal.ZERO) <= 0
            || traditional.compareTo(BigDecimal.ZERO) <= 0) {
        return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
    }
    BigDecimal actual = effectiveLimit.min(traditional);
    traditional = traditional.subtract(actual);
    roth = roth.add(actual);

    if (taxCalculator != null) {
        BigDecimal taxableIncome = actual.add(effectiveOtherIncome);
        BigDecimal tax = taxCalculator.computeTax(taxableIncome, year, filingStatus);
        TaxSourceResult taxSource = deductFromPools(tax);
        return new ConversionResult(actual, tax, taxSource);
    }
    return new ConversionResult(actual, BigDecimal.ZERO, TaxSourceResult.ZERO);
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd backend && mvn compile -pl wealthview-projection 2>&1 | tail -10`
Expected: Compile errors only in DeterministicProjectionEngine (not yet updated) and tests. PoolStrategy itself should compile.

---

### Task 4: Add 9 New Fields to ProjectionYearDto

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionYearDto.java`

- [ ] **Step 1: Add 9 new fields to the record**

Add after `surplusReinvested` (line 36), before the closing paren:

```java
        BigDecimal surplusReinvested,
        BigDecimal taxableGrowth,
        BigDecimal traditionalGrowth,
        BigDecimal rothGrowth,
        BigDecimal taxPaidFromTaxable,
        BigDecimal taxPaidFromTraditional,
        BigDecimal taxPaidFromRoth,
        BigDecimal withdrawalFromTaxable,
        BigDecimal withdrawalFromTraditional,
        BigDecimal withdrawalFromRoth) {
```

- [ ] **Step 2: Update the simple() factory method**

Add 9 more nulls to the constructor call (line 42-46):

```java
    public static ProjectionYearDto simple(int year, int age, BigDecimal startBalance,
                                            BigDecimal contributions, BigDecimal growth,
                                            BigDecimal withdrawals, BigDecimal endBalance,
                                            boolean retired) {
        return new ProjectionYearDto(year, age, startBalance, contributions, growth,
                withdrawals, endBalance, retired, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null, null, null, null);
    }
```

- [ ] **Step 3: Verify core module compiles**

Run: `cd backend && mvn compile -pl wealthview-core 2>&1 | tail -5`
Expected: Success (core has no dependency on projection module).

---

### Task 5: Update PoolStrategy.buildYearDto() and MultiPool.buildYearDto()

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`

- [ ] **Step 1: Update interface signature**

Change `buildYearDto` (line 44-47) to accept per-pool data:

```java
ProjectionYearDto buildYearDto(int year, int age, BigDecimal startBalance,
                               BigDecimal contributions, BigDecimal totalGrowth,
                               BigDecimal withdrawals, boolean retired,
                               BigDecimal conversionAmount, BigDecimal taxLiability,
                               GrowthResult growthResult,
                               BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                               BigDecimal withdrawalFromRoth,
                               TaxSourceResult combinedTaxSource);
```

- [ ] **Step 2: Update SinglePool.buildYearDto()**

Change (line 144-151):
```java
public ProjectionYearDto buildYearDto(int year, int age, BigDecimal startBalance,
                                      BigDecimal contributions, BigDecimal totalGrowth,
                                      BigDecimal withdrawals, boolean retired,
                                      BigDecimal conversionAmount, BigDecimal taxLiability,
                                      GrowthResult growthResult,
                                      BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                                      BigDecimal withdrawalFromRoth,
                                      TaxSourceResult combinedTaxSource) {
    return ProjectionYearDto.simple(year, age, startBalance, contributions,
            totalGrowth, withdrawals, balance, retired);
}
```

- [ ] **Step 3: Update MultiPool.buildYearDto()**

Change (line 363-376):
```java
public ProjectionYearDto buildYearDto(int year, int age, BigDecimal startBalance,
                                      BigDecimal contributions, BigDecimal totalGrowth,
                                      BigDecimal withdrawals, boolean retired,
                                      BigDecimal conversionAmount, BigDecimal taxLiability,
                                      GrowthResult growthResult,
                                      BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                                      BigDecimal withdrawalFromRoth,
                                      TaxSourceResult combinedTaxSource) {
    return new ProjectionYearDto(
            year, age, startBalance, contributions, totalGrowth, withdrawals, getTotal(), retired,
            traditional, roth, taxable,
            conversionAmount.compareTo(BigDecimal.ZERO) > 0 ? conversionAmount : null,
            taxLiability.compareTo(BigDecimal.ZERO) > 0 ? taxLiability : null,
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null,
            growthResult.taxable(), growthResult.traditional(), growthResult.roth(),
            combinedTaxSource.fromTaxable().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromTaxable() : null,
            combinedTaxSource.fromTraditional().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromTraditional() : null,
            combinedTaxSource.fromRoth().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromRoth() : null,
            withdrawalFromTaxable.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromTaxable : null,
            withdrawalFromTraditional.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromTraditional : null,
            withdrawalFromRoth.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromRoth : null);
}
```

---

### Task 6: Wire Engine Main Loop

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java`

- [ ] **Step 1: Update RetirementWithdrawalResult to carry per-pool data**

Change (line 259-264):
```java
private record RetirementWithdrawalResult(
        BigDecimal withdrawals,
        BigDecimal taxLiability,
        BigDecimal previousWithdrawal,
        BigDecimal surplusReinvested) {
}
```
To:
```java
private record RetirementWithdrawalResult(
        BigDecimal withdrawals,
        BigDecimal taxLiability,
        BigDecimal previousWithdrawal,
        BigDecimal surplusReinvested,
        BigDecimal withdrawalFromTaxable,
        BigDecimal withdrawalFromTraditional,
        BigDecimal withdrawalFromRoth,
        PoolStrategy.TaxSourceResult withdrawalTaxSource) {
}
```

- [ ] **Step 2: Update processRetirementWithdrawals to return per-pool data**

In `processRetirementWithdrawals()` (around line 267-320), the `executeWithdrawals` call already returns the enriched `WithdrawalTaxResult`. Capture the new fields and return them:

Change the return statement at the end of the method from:
```java
return new RetirementWithdrawalResult(withdrawalResult.totalWithdrawn(), taxLiability,
        previousWithdrawal, surplusReinvested);
```
To:
```java
return new RetirementWithdrawalResult(withdrawalResult.totalWithdrawn(), taxLiability,
        previousWithdrawal, surplusReinvested,
        withdrawalResult.fromTaxable(), withdrawalResult.fromTraditional(),
        withdrawalResult.fromRoth(), withdrawalResult.taxSource());
```

- [ ] **Step 3: Update the main year loop to capture and wire data**

In the main `run()` loop (around line 158-210):

Change:
```java
BigDecimal totalGrowth = pool.applyGrowth();
```
To:
```java
var growthResult = pool.applyGrowth();
BigDecimal totalGrowth = growthResult.total();
```

After the retirement withdrawal section (around line 193), add variables to track per-pool data:
```java
BigDecimal wdFromTaxable = BigDecimal.ZERO;
BigDecimal wdFromTraditional = BigDecimal.ZERO;
BigDecimal wdFromRoth = BigDecimal.ZERO;
PoolStrategy.TaxSourceResult withdrawalTaxSource = PoolStrategy.TaxSourceResult.ZERO;
```

Inside the `if (retired)` block, after existing assignments, add:
```java
wdFromTaxable = retirementResult.withdrawalFromTaxable();
wdFromTraditional = retirementResult.withdrawalFromTraditional();
wdFromRoth = retirementResult.withdrawalFromRoth();
withdrawalTaxSource = retirementResult.withdrawalTaxSource();
```

- [ ] **Step 4: Compute combined tax source and pass to buildYearDto**

After the retirement block, compute the combined tax source from conversion + withdrawal:

```java
var conversionTaxSource = incomeResult.conversionTaxSource();
var combinedTaxSource = conversionTaxSource.add(withdrawalTaxSource);
```

This requires adding `conversionTaxSource` to `IncomeAndConversionResult`. Update that record (line 225-234) to include it:

```java
private record IncomeAndConversionResult(
        IncomeSourceProcessor.IncomeSourceYearResult isResult,
        BigDecimal totalActiveIncome,
        BigDecimal effectiveOtherIncome,
        BigDecimal conversionAmount,
        BigDecimal taxLiability,
        BigDecimal suspendedLoss,
        PoolStrategy.TaxSourceResult conversionTaxSource) {
}
```

Update `processIncomeAndConversions()` (line 236-257) to pass `conversion.taxSource()`:
```java
return new IncomeAndConversionResult(incomeSourceResult, totalActiveIncome, effectiveOtherIncome,
        conversion.amountConverted(), conversion.taxLiability(), suspendedLoss, conversion.taxSource());
```

- [ ] **Step 5: Update buildYearDto call**

Change the `buildYearDto` call (around line 200-201):
```java
var yearDto = pool.buildYearDto(year, age, startBalance, contributions,
        totalGrowth, withdrawals, retired, conversionAmount, taxLiability);
```
To:
```java
var yearDto = pool.buildYearDto(year, age, startBalance, contributions,
        totalGrowth, withdrawals, retired, conversionAmount, taxLiability,
        growthResult, wdFromTaxable, wdFromTraditional, wdFromRoth, combinedTaxSource);
```

---

### Task 7: Update Copy-Constructor Helper Methods

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java`

Each of the 4 copy-constructor methods must pass through the 9 new fields. Add these 9 lines to the end of each `new ProjectionYearDto(...)` call, before the closing paren:

```java
base.taxableGrowth(), base.traditionalGrowth(), base.rothGrowth(),
base.taxPaidFromTaxable(), base.taxPaidFromTraditional(), base.taxPaidFromRoth(),
base.withdrawalFromTaxable(), base.withdrawalFromTraditional(), base.withdrawalFromRoth()
```

- [ ] **Step 1: Update applyViability()** (line 583-593)
- [ ] **Step 2: Update applyIncomeSourceFields()** (line 605-621)
- [ ] **Step 3: Update applySurplusReinvested()** (line 625-637)
- [ ] **Step 4: Update applyPropertyEquity()** (line 685-697)
- [ ] **Step 5: Verify full compilation**

Run: `cd backend && mvn compile -pl wealthview-core,wealthview-projection 2>&1 | tail -5`
Expected: BUILD SUCCESS

---

### Task 8: Update Golden Test Files

**Files:**
- Modify: `backend/wealthview-projection/src/test/resources/golden/multi-pool-roth-conversion.json`
- Modify: `backend/wealthview-projection/src/test/resources/golden/simple-preretirement.json`
- Modify: `backend/wealthview-projection/src/test/resources/golden/tiered-spending-with-income.json`

- [ ] **Step 1: Add 9 null fields to every year object in all 3 golden files**

After `"surplusReinvested" : null` in each year object, add:
```json
    "taxableGrowth" : null,
    "traditionalGrowth" : null,
    "rothGrowth" : null,
    "taxPaidFromTaxable" : null,
    "taxPaidFromTraditional" : null,
    "taxPaidFromRoth" : null,
    "withdrawalFromTaxable" : null,
    "withdrawalFromTraditional" : null,
    "withdrawalFromRoth" : null
```

Note: The `multi-pool-roth-conversion.json` golden file WILL have non-null values for the growth and tax fields since it uses MultiPool. After the tests run and fail on value mismatches, regenerate this golden file from the actual test output.

- [ ] **Step 2: Run existing tests to check**

Run: `cd backend && mvn test -pl wealthview-projection 2>&1 | tail -20`
Expected: Golden file tests may need value updates for multi-pool. Fix mismatches by updating golden files with actual values.

---

### Task 9: Write Per-Pool Transparency Tests

**Files:**
- Modify: `backend/wealthview-projection/src/test/java/com/wealthview/projection/DeterministicProjectionEngineTest.java`

- [ ] **Step 1: Write test for per-pool growth fields**

```java
@Test
void run_multiPool_exposesPerPoolGrowth() {
    var input = createInput(
            LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
            """
            {"birth_year": %d, "filing_status": "single"}
            """.formatted(LocalDate.now().getYear() - 35),
            List.of(
                    acct("200000.0000", "0", "0.0500", "traditional"),
                    acct("100000.0000", "0", "0.0500", "roth"),
                    acct("50000.0000", "0", "0.0500", "taxable")));

    var result = engine.run(input);

    var year1 = result.yearlyData().getFirst();
    assertThat(year1.traditionalGrowth()).isEqualByComparingTo(bd("10000.0000"));
    assertThat(year1.rothGrowth()).isEqualByComparingTo(bd("5000.0000"));
    assertThat(year1.taxableGrowth()).isEqualByComparingTo(bd("2500.0000"));
    assertThat(year1.growth()).isEqualByComparingTo(
            year1.traditionalGrowth().add(year1.rothGrowth()).add(year1.taxableGrowth()));
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest="DeterministicProjectionEngineTest#run_multiPool_exposesPerPoolGrowth" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 3: Write test for conversion tax source**

```java
@Test
void run_rothConversion_exposesConversionTaxSource() {
    stubSingle2025(taxBracketRepository, standardDeductionRepository);
    var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

    var input = createInput(
            LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
            """
            {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 50000}
            """.formatted(LocalDate.now().getYear() - 35),
            List.of(
                    acct("500000.0000", "0", "0.0700", "traditional"),
                    acct("100000.0000", "0", "0.0700", "roth"),
                    acct("50000.0000", "0", "0.0700", "taxable")));

    var result = engineTax.run(input);

    var year1 = result.yearlyData().getFirst();
    // Tax should come from taxable first
    assertThat(year1.taxPaidFromTaxable()).isNotNull();
    assertThat(year1.taxPaidFromTaxable()).isGreaterThan(BigDecimal.ZERO);
    // Total tax paid from pools should equal tax liability
    BigDecimal totalTaxPaid = year1.taxPaidFromTaxable()
            .add(year1.taxPaidFromTraditional() != null ? year1.taxPaidFromTraditional() : BigDecimal.ZERO)
            .add(year1.taxPaidFromRoth() != null ? year1.taxPaidFromRoth() : BigDecimal.ZERO);
    assertThat(totalTaxPaid).isEqualByComparingTo(year1.taxLiability());
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest="DeterministicProjectionEngineTest#run_rothConversion_exposesConversionTaxSource" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Write test for per-pool withdrawal breakdown**

```java
@Test
void run_retirementWithdrawal_exposesPerPoolWithdrawals() {
    stubSingle2025(taxBracketRepository, standardDeductionRepository);
    var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

    var input = createInput(
            LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
            """
            {"birth_year": %d, "filing_status": "single", "withdrawal_rate": 0.04}
            """.formatted(LocalDate.now().getYear() - 66),
            List.of(
                    acct("300000.0000", "0", "0.0500", "traditional"),
                    acct("100000.0000", "0", "0.0500", "roth"),
                    acct("100000.0000", "0", "0.0500", "taxable")));

    var result = engineTax.run(input);

    var year1 = result.yearlyData().getFirst();
    assertThat(year1.withdrawals()).isGreaterThan(BigDecimal.ZERO);
    // Default order is taxable-first
    assertThat(year1.withdrawalFromTaxable()).isNotNull();
    // Per-pool withdrawals should sum to total withdrawals
    BigDecimal totalPerPool = (year1.withdrawalFromTaxable() != null ? year1.withdrawalFromTaxable() : BigDecimal.ZERO)
            .add(year1.withdrawalFromTraditional() != null ? year1.withdrawalFromTraditional() : BigDecimal.ZERO)
            .add(year1.withdrawalFromRoth() != null ? year1.withdrawalFromRoth() : BigDecimal.ZERO);
    assertThat(totalPerPool).isEqualByComparingTo(year1.withdrawals());
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest="DeterministicProjectionEngineTest#run_retirementWithdrawal_exposesPerPoolWithdrawals" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 7: Write test for SinglePool returns null for all per-pool fields**

```java
@Test
void run_singlePool_perPoolFieldsAreNull() {
    var input = createInput(
            LocalDate.now().plusYears(30), 90, bd("0.0300"),
            """
            {"birth_year": %d}
            """.formatted(LocalDate.now().getYear() - 35),
            List.of(acct("100000.0000", "10000.0000", "0.0700")));

    var result = engine.run(input);

    var year1 = result.yearlyData().getFirst();
    assertThat(year1.taxableGrowth()).isNull();
    assertThat(year1.traditionalGrowth()).isNull();
    assertThat(year1.rothGrowth()).isNull();
    assertThat(year1.taxPaidFromTaxable()).isNull();
    assertThat(year1.taxPaidFromTraditional()).isNull();
    assertThat(year1.taxPaidFromRoth()).isNull();
    assertThat(year1.withdrawalFromTaxable()).isNull();
    assertThat(year1.withdrawalFromTraditional()).isNull();
    assertThat(year1.withdrawalFromRoth()).isNull();
}
```

- [ ] **Step 8: Run all projection tests**

Run: `cd backend && mvn test -pl wealthview-projection 2>&1 | tail -20`
Expected: ALL PASS

- [ ] **Step 9: Commit backend changes**

```bash
git add backend/
git commit -m "feat(projection,core): expose per-pool growth, tax source, and withdrawal breakdown

Add GrowthResult, TaxSourceResult records to PoolStrategy. Enrich
WithdrawalTaxResult and ConversionResult with per-pool data.
Nine new fields on ProjectionYearDto: taxableGrowth, traditionalGrowth,
rothGrowth, taxPaidFromTaxable/Traditional/Roth,
withdrawalFromTaxable/Traditional/Roth. All null for SinglePool."
```

---

### Task 10: Frontend Type and CSV Updates

**Files:**
- Modify: `frontend/src/types/projection.ts`
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx`

- [ ] **Step 1: Add 9 fields to ProjectionYear interface**

After `surplus_reinvested` (line 81) in `projection.ts`:
```typescript
    taxable_growth: number | null;
    traditional_growth: number | null;
    roth_growth: number | null;
    tax_paid_from_taxable: number | null;
    tax_paid_from_traditional: number | null;
    tax_paid_from_roth: number | null;
    withdrawal_from_taxable: number | null;
    withdrawal_from_traditional: number | null;
    withdrawal_from_roth: number | null;
```

- [ ] **Step 2: Update CSV export in ProjectionDetailPage.tsx**

In `buildProjectionCsv()`, after the existing `hasPoolData` headers push (line 107), add pool detail headers:
```typescript
if (hasPoolData) {
    headers.push('Traditional', 'Roth', 'Taxable', 'Conversion', 'Tax');
    headers.push('Trad Growth', 'Roth Growth', 'Taxable Growth',
        'Tax from Taxable', 'Tax from Trad', 'Tax from Roth',
        'WD from Taxable', 'WD from Trad', 'WD from Roth');
}
```

In the row-building section, after existing pool data values (line 119-122), add:
```typescript
if (hasPoolData) {
    vals.push(
        y.traditional_balance ?? '', y.roth_balance ?? '', y.taxable_balance ?? '',
        y.roth_conversion_amount ?? '', y.tax_liability ?? '',
        y.traditional_growth ?? '', y.roth_growth ?? '', y.taxable_growth ?? '',
        y.tax_paid_from_taxable ?? '', y.tax_paid_from_traditional ?? '', y.tax_paid_from_roth ?? '',
        y.withdrawal_from_taxable ?? '', y.withdrawal_from_traditional ?? '', y.withdrawal_from_roth ?? '',
    );
}
```

---

### Task 11: Frontend Collapsible Pool Details Section

**Files:**
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx`

- [ ] **Step 1: Add toggle state**

Near the top of the component function (around line 88), add:
```typescript
const [showPoolDetails, setShowPoolDetails] = useState(false);
```

- [ ] **Step 2: Add toggle button**

Above the table (near the Download CSV button area, around line 466-479), add a Pool Details toggle button:
```tsx
{hasPoolData && (
    <button
        onClick={() => setShowPoolDetails(!showPoolDetails)}
        style={{
            padding: '0.4rem 0.8rem',
            background: showPoolDetails ? '#e65100' : '#757575',
            color: '#fff',
            border: 'none',
            borderRadius: '4px',
            cursor: 'pointer',
            fontSize: '0.85rem',
            marginLeft: '0.5rem',
        }}
    >
        {showPoolDetails ? 'Hide' : 'Show'} Pool Details
    </button>
)}
```

- [ ] **Step 3: Move Surplus/Deficit, Surplus Reinvested, Status into collapsible section**

Remove the Status column from the main header (line 494). Remove the Surplus/Deficit and Surplus Reinvested columns from the `hasSpendingData` header block (lines 509-512).

Add a new collapsible header block after the `hasSpendingData` block:
```tsx
{showPoolDetails && hasPoolData && (
    <>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Trad Growth</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Roth Growth</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Taxable Growth</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Tax from Taxable</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Tax from Trad</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Tax from Roth</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>WD from Taxable</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>WD from Trad</th>
        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>WD from Roth</th>
        {hasSpendingData && (
            <>
                <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Surplus/Deficit</th>
                {hasSurplusReinvested && (
                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Surplus Reinvested</th>
                )}
            </>
        )}
        <th style={{ textAlign: 'center', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Status</th>
    </>
)}
```

- [ ] **Step 4: Add corresponding body cells**

In the row rendering section, after the `hasSpendingData` body block, add:
```tsx
{showPoolDetails && hasPoolData && (
    <>
        <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.traditional_growth != null ? formatCurrency(y.traditional_growth) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.roth_growth != null ? formatCurrency(y.roth_growth) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.taxable_growth != null ? formatCurrency(y.taxable_growth) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_paid_from_taxable != null ? formatCurrency(y.tax_paid_from_taxable) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_paid_from_traditional != null ? formatCurrency(y.tax_paid_from_traditional) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_paid_from_roth != null ? formatCurrency(y.tax_paid_from_roth) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawal_from_taxable != null ? formatCurrency(y.withdrawal_from_taxable) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawal_from_traditional != null ? formatCurrency(y.withdrawal_from_traditional) : '-'}</td>
        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawal_from_roth != null ? formatCurrency(y.withdrawal_from_roth) : '-'}</td>
        {hasSpendingData && (
            <>
                <td style={{
                    padding: '0.5rem', textAlign: 'right',
                    color: y.spending_surplus != null && Math.abs(y.spending_surplus) >= 1 ? (y.spending_surplus > 0 ? '#2e7d32' : '#d32f2f') : undefined,
                    fontWeight: 600,
                }}>
                    {y.spending_surplus != null && Math.abs(y.spending_surplus) >= 1 ? formatCurrency(y.spending_surplus) : '-'}
                </td>
                {hasSurplusReinvested && (
                    <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>
                        {y.surplus_reinvested != null && y.surplus_reinvested > 0 ? formatCurrency(y.surplus_reinvested) : '-'}
                    </td>
                )}
            </>
        )}
        <td style={{ padding: '0.5rem', textAlign: 'center' }}>{y.retired ? 'Retired' : 'Working'}</td>
    </>
)}
```

---

### Task 12: Update Frontend Test Fixtures

**Files:**
- Modify: `frontend/src/components/IncomeStreamsChart.test.tsx`
- Modify: `frontend/src/pages/ProjectionDetailPage.test.tsx`
- Modify: `frontend/src/utils/monthlyInterpolation.test.ts`
- Modify: `frontend/src/utils/projectionCalcs.test.ts`

- [ ] **Step 1: Update makeYear() helpers**

In each `makeYear()` function, add after `surplus_reinvested: null`:
```typescript
        taxable_growth: null,
        traditional_growth: null,
        roth_growth: null,
        tax_paid_from_taxable: null,
        tax_paid_from_traditional: null,
        tax_paid_from_roth: null,
        withdrawal_from_taxable: null,
        withdrawal_from_traditional: null,
        withdrawal_from_roth: null,
```

- [ ] **Step 2: Update inline test data in ProjectionDetailPage.test.tsx**

The test at line 1971-1972 has an inline year object — add the 9 new null fields.

- [ ] **Step 3: Run frontend tests**

Run: `cd frontend && npm run test -- --run 2>&1 | tail -20`
Expected: ALL PASS

- [ ] **Step 4: Commit frontend changes**

```bash
git add frontend/
git commit -m "feat(frontend): add collapsible Pool Details section with per-pool transparency

Show per-pool growth, tax source, and withdrawal breakdown in a
collapsible Pool Details toggle on the projection data table.
Surplus/Deficit, Surplus Reinvested, and Status moved into this section.
CSV export includes all 9 new pool detail columns."
```

---

### Task 13: Full Integration Verification

- [ ] **Step 1: Run all backend tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection 2>&1 | tail -20`
Expected: ALL PASS

- [ ] **Step 2: Run frontend tests**

Run: `cd frontend && npm run test -- --run 2>&1 | tail -10`
Expected: ALL PASS

- [ ] **Step 3: Build and verify Docker**

Run: `docker compose down && docker compose up --build -d`
Verify: Open http://localhost:80, log in as demo@wealthview.local / demo123, navigate to a projection scenario with multi-pool accounts, run the projection, verify the "Pool Details" toggle appears and shows per-pool data.
