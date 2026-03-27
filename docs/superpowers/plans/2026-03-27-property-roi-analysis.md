# Property ROI Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-income-source hold-vs-sell ROI comparison to the property detail page.

**Architecture:** New `PropertyRoiService` in wealthview-core computes hold and sell scenarios using existing property, income source, and depreciation data. New endpoint on `PropertyController`. New `PropertyRoiCard.tsx` component renders one card per linked income source on the property detail page.

**Tech Stack:** Java 21 / Spring Boot / BigDecimal math, React 18 / TypeScript / Axios

---

### Task 1: DTO Records — RoiAnalysisResponse, HoldScenarioResult, SellScenarioResult

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/HoldScenarioResult.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/SellScenarioResult.java`
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/RoiAnalysisResponse.java`

- [ ] **Step 1: Create HoldScenarioResult record**

```java
package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record HoldScenarioResult(
        BigDecimal endingPropertyValue,
        BigDecimal endingMortgageBalance,
        BigDecimal cumulativeNetCashFlow,
        BigDecimal endingNetWorth
) {
}
```

- [ ] **Step 2: Create SellScenarioResult record**

```java
package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record SellScenarioResult(
        BigDecimal grossProceeds,
        BigDecimal sellingCosts,
        BigDecimal depreciationRecaptureTax,
        BigDecimal capitalGainsTax,
        BigDecimal netProceeds,
        BigDecimal endingNetWorth
) {
}
```

- [ ] **Step 3: Create RoiAnalysisResponse record**

```java
package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record RoiAnalysisResponse(
        String incomeSourceName,
        BigDecimal annualRent,
        int comparisonYears,
        HoldScenarioResult hold,
        SellScenarioResult sell,
        String advantage,
        BigDecimal advantageAmount
) {
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/HoldScenarioResult.java \
       backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/SellScenarioResult.java \
       backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/RoiAnalysisResponse.java
git commit -m "feat(core): add ROI analysis DTO records

HoldScenarioResult, SellScenarioResult, and RoiAnalysisResponse records
for the property hold-vs-sell comparison feature."
```

---

### Task 2: PropertyRoiService — Sell Scenario Computation + Tests

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyRoiService.java`
- Create: `backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyRoiServiceTest.java`

This task builds the sell scenario calculation. The hold scenario is added in Task 3.

- [ ] **Step 1: Write the failing test for sell scenario — no depreciation**

Create `PropertyRoiServiceTest.java`:

```java
package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropertyRoiServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private IncomeSourceRepository incomeSourceRepository;

    private PropertyRoiService roiService;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        roiService = new PropertyRoiService(propertyRepository, incomeSourceRepository,
                new DepreciationCalculator());
    }

    @Test
    void computeRoiAnalysis_sellScenario_noDepreciation() {
        // Property: purchased at 300k, now worth 400k, no depreciation
        var property = createInvestmentProperty(
                new BigDecimal("300000"), new BigDecimal("400000"), "none");

        var incomeSource = createIncomeSource(property, "Rental Income",
                new BigDecimal("24000"));

        when(propertyRepository.findByTenant_IdAndId(tenantId, property.getId()))
                .thenReturn(Optional.of(property));
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSource.getId()))
                .thenReturn(Optional.of(incomeSource));

        var result = roiService.computeRoiAnalysis(tenantId, property.getId(),
                incomeSource.getId(), 10,
                new BigDecimal("0.07"), new BigDecimal("0.03"), new BigDecimal("0.03"));

        var sell = result.sell();

        // Gross proceeds = 400,000
        assertThat(sell.grossProceeds()).isEqualByComparingTo("400000");
        // Selling costs = 400,000 * 0.06 = 24,000
        assertThat(sell.sellingCosts()).isEqualByComparingTo("24000");
        // No depreciation recapture
        assertThat(sell.depreciationRecaptureTax()).isEqualByComparingTo("0");
        // Capital gain = 400,000 - 300,000 - 24,000 = 76,000; tax = 76,000 * 0.15 = 11,400
        assertThat(sell.capitalGainsTax()).isEqualByComparingTo("11400");
        // Net proceeds = 400,000 - 24,000 - 0 - 11,400 = 364,600
        assertThat(sell.netProceeds()).isEqualByComparingTo("364600");
        // Invested at 7% for 10 years: 364,600 * 1.07^10 = ~717,216.88
        assertThat(sell.endingNetWorth()).isEqualByComparingTo("717216.8808");
    }

    // Helper methods used across tasks 2 and 3:

    private PropertyEntity createInvestmentProperty(BigDecimal purchasePrice,
                                                      BigDecimal currentValue,
                                                      String depreciationMethod) {
        var property = new PropertyEntity(tenant, "123 Test St", purchasePrice,
                LocalDate.of(2020, 1, 15), currentValue, BigDecimal.ZERO);
        property.setPropertyType("investment");
        property.setDepreciationMethod(depreciationMethod);
        // Use reflection to set the ID since PropertyEntity doesn't have a setId
        try {
            var idField = PropertyEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(property, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return property;
    }

    private PropertyEntity createInvestmentPropertyWithLoan(BigDecimal purchasePrice,
                                                              BigDecimal currentValue,
                                                              BigDecimal loanAmount,
                                                              BigDecimal annualRate,
                                                              int termMonths,
                                                              LocalDate loanStartDate) {
        var property = createInvestmentProperty(purchasePrice, currentValue, "none");
        property.setLoanAmount(loanAmount);
        property.setAnnualInterestRate(annualRate);
        property.setLoanTermMonths(termMonths);
        property.setLoanStartDate(loanStartDate);
        return property;
    }

    private IncomeSourceEntity createIncomeSource(PropertyEntity property, String name,
                                                    BigDecimal annualAmount) {
        var source = new IncomeSourceEntity(tenant, name, "rental", annualAmount,
                30, null, BigDecimal.ZERO, false, "taxable");
        source.setProperty(property);
        try {
            var idField = IncomeSourceEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(source, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return source;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest#computeRoiAnalysis_sellScenario_noDepreciation" -q`
Expected: FAIL — `PropertyRoiService` class does not exist.

- [ ] **Step 3: Write PropertyRoiService with sell scenario**

```java
package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.HoldScenarioResult;
import com.wealthview.core.property.dto.RoiAnalysisResponse;
import com.wealthview.core.property.dto.SellScenarioResult;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class PropertyRoiService {

    private static final BigDecimal SELLING_COST_RATE = new BigDecimal("0.06");
    private static final BigDecimal CAPITAL_GAINS_RATE = new BigDecimal("0.15");
    private static final BigDecimal DEPRECIATION_RECAPTURE_RATE = new BigDecimal("0.25");
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final PropertyRepository propertyRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final DepreciationCalculator depreciationCalculator;

    public PropertyRoiService(PropertyRepository propertyRepository,
                               IncomeSourceRepository incomeSourceRepository,
                               DepreciationCalculator depreciationCalculator) {
        this.propertyRepository = propertyRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.depreciationCalculator = depreciationCalculator;
    }

    @Transactional(readOnly = true)
    public RoiAnalysisResponse computeRoiAnalysis(UUID tenantId, UUID propertyId,
                                                    UUID incomeSourceId, int years,
                                                    BigDecimal investmentReturn,
                                                    BigDecimal rentGrowth,
                                                    BigDecimal expenseInflation) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var incomeSource = incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSourceId)
                .orElseThrow(() -> new EntityNotFoundException("Income source not found"));

        var sell = computeSellScenario(property, years, investmentReturn);
        var hold = computeHoldScenario(property, incomeSource.getAnnualAmount(),
                years, rentGrowth, expenseInflation);

        var holdNetWorth = hold.endingNetWorth();
        var sellNetWorth = sell.endingNetWorth();
        var advantage = holdNetWorth.compareTo(sellNetWorth) >= 0 ? "hold" : "sell";
        var advantageAmount = holdNetWorth.subtract(sellNetWorth).abs()
                .setScale(SCALE, ROUNDING);

        return new RoiAnalysisResponse(
                incomeSource.getName(),
                incomeSource.getAnnualAmount(),
                years,
                hold,
                sell,
                advantage,
                advantageAmount
        );
    }

    SellScenarioResult computeSellScenario(PropertyEntity property, int years,
                                             BigDecimal investmentReturn) {
        var grossProceeds = property.getCurrentValue();
        var sellingCosts = grossProceeds.multiply(SELLING_COST_RATE).setScale(SCALE, ROUNDING);

        var accumulatedDepreciation = computeAccumulatedDepreciation(property);
        var depreciationRecaptureTax = accumulatedDepreciation
                .multiply(DEPRECIATION_RECAPTURE_RATE).setScale(SCALE, ROUNDING);

        // Total gain on sale = sale price - original purchase price - selling costs
        // The recapture portion (accumulated depreciation) is taxed at 25% (above).
        // The remaining gain is taxed at LTCG rate.
        var totalGain = grossProceeds.subtract(property.getPurchasePrice()).subtract(sellingCosts);
        var capitalGainForLTCG = totalGain.subtract(accumulatedDepreciation).max(BigDecimal.ZERO);
        var capitalGainsTax = capitalGainForLTCG.multiply(CAPITAL_GAINS_RATE)
                .setScale(SCALE, ROUNDING);

        var netProceeds = grossProceeds.subtract(sellingCosts)
                .subtract(depreciationRecaptureTax).subtract(capitalGainsTax);

        var endingNetWorth = compound(netProceeds, investmentReturn, years);

        return new SellScenarioResult(grossProceeds, sellingCosts, depreciationRecaptureTax,
                capitalGainsTax, netProceeds, endingNetWorth);
    }

    HoldScenarioResult computeHoldScenario(PropertyEntity property,
                                             BigDecimal annualRent, int years,
                                             BigDecimal rentGrowth,
                                             BigDecimal expenseInflation) {
        // Stub — implemented in Task 3
        return new HoldScenarioResult(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private BigDecimal computeAccumulatedDepreciation(PropertyEntity property) {
        var method = property.getDepreciationMethod();
        if (method == null || "none".equals(method)) {
            return BigDecimal.ZERO;
        }

        var landValue = property.getLandValue() != null ? property.getLandValue() : BigDecimal.ZERO;
        var inServiceDate = property.getInServiceDate();
        if (inServiceDate == null) {
            return BigDecimal.ZERO;
        }

        var currentYear = LocalDate.now().getYear();

        if ("cost_segregation".equals(method)) {
            var allocations = PropertyService.parseCostSegAllocations(
                    property.getCostSegAllocations());
            var schedule = depreciationCalculator.computeCostSegregation(
                    allocations, property.getBonusDepreciationRate(),
                    inServiceDate, property.getCostSegStudyYear());
            return sumScheduleThrough(schedule, currentYear);
        }

        var schedule = depreciationCalculator.computeStraightLine(
                property.getPurchasePrice(), landValue,
                inServiceDate, property.getUsefulLifeYears());
        return sumScheduleThrough(schedule, currentYear);
    }

    private BigDecimal sumScheduleThrough(java.util.Map<Integer, BigDecimal> schedule,
                                            int throughYear) {
        var total = BigDecimal.ZERO;
        for (var entry : schedule.entrySet()) {
            if (entry.getKey() <= throughYear) {
                total = total.add(entry.getValue());
            }
        }
        return total.setScale(SCALE, ROUNDING);
    }

    private BigDecimal compound(BigDecimal principal, BigDecimal annualRate, int years) {
        var factor = BigDecimal.ONE.add(annualRate);
        var result = principal;
        for (int i = 0; i < years; i++) {
            result = result.multiply(factor);
        }
        return result.setScale(SCALE, ROUNDING);
    }
}
```

**Note:** `computeHoldScenario` is stubbed — it returns zeros. Task 3 fills it in.

Before this compiles, we need `parseCostSegAllocations` to be accessible. Check if it's already `static` and package-private in PropertyService. If it's private, change its visibility to package-private (`static`) — it's in the same package.

- [ ] **Step 4: Make `parseCostSegAllocations` accessible**

In `backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java`, find the `parseCostSegAllocations` method and ensure it has package-private (no modifier) or `static` access. It should already be `static` — just remove `private` if present:

Change:
```java
private static List<CostSegAllocation> parseCostSegAllocations(String json) {
```
To:
```java
static List<CostSegAllocation> parseCostSegAllocations(String json) {
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest#computeRoiAnalysis_sellScenario_noDepreciation" -q`
Expected: PASS

- [ ] **Step 6: Write test for sell scenario with straight-line depreciation**

Add to `PropertyRoiServiceTest.java`:

```java
@Test
void computeRoiAnalysis_sellScenario_withStraightLineDepreciation() {
    // Property: purchased 2020-01-15 at 300k, land 50k, current value 400k
    // Straight-line 27.5yr, in service 2020-01-15
    // Depreciable basis = 300k - 50k = 250k
    // ~6 years of depreciation (2020 through 2025/early 2026)
    var property = createInvestmentProperty(
            new BigDecimal("300000"), new BigDecimal("400000"), "straight_line");
    property.setLandValue(new BigDecimal("50000"));
    property.setInServiceDate(LocalDate.of(2020, 1, 15));
    property.setUsefulLifeYears(new BigDecimal("27.5"));

    var incomeSource = createIncomeSource(property, "Rental", new BigDecimal("24000"));

    when(propertyRepository.findByTenant_IdAndId(tenantId, property.getId()))
            .thenReturn(Optional.of(property));
    when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSource.getId()))
            .thenReturn(Optional.of(incomeSource));

    var result = roiService.computeRoiAnalysis(tenantId, property.getId(),
            incomeSource.getId(), 10,
            new BigDecimal("0.07"), new BigDecimal("0.03"), new BigDecimal("0.03"));

    var sell = result.sell();

    // Accumulated depreciation should be > 0
    assertThat(sell.depreciationRecaptureTax()).isGreaterThan(BigDecimal.ZERO);
    // Net proceeds should be less than the no-depreciation case
    // because recapture tax applies
    assertThat(sell.netProceeds()).isLessThan(new BigDecimal("364600"));
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest#computeRoiAnalysis_sellScenario_withStraightLineDepreciation" -q`
Expected: PASS

- [ ] **Step 8: Write test for property not found**

Add to `PropertyRoiServiceTest.java`:

```java
@Test
void computeRoiAnalysis_propertyNotFound_throwsException() {
    var propertyId = UUID.randomUUID();
    var sourceId = UUID.randomUUID();

    when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
            .thenReturn(Optional.empty());

    assertThatThrownBy(() -> roiService.computeRoiAnalysis(tenantId, propertyId,
            sourceId, 10,
            new BigDecimal("0.07"), new BigDecimal("0.03"), new BigDecimal("0.03")))
            .isInstanceOf(EntityNotFoundException.class);
}
```

- [ ] **Step 9: Write test for income source not found**

Add to `PropertyRoiServiceTest.java`:

```java
@Test
void computeRoiAnalysis_incomeSourceNotFound_throwsException() {
    var property = createInvestmentProperty(
            new BigDecimal("300000"), new BigDecimal("400000"), "none");
    var sourceId = UUID.randomUUID();

    when(propertyRepository.findByTenant_IdAndId(tenantId, property.getId()))
            .thenReturn(Optional.of(property));
    when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
            .thenReturn(Optional.empty());

    assertThatThrownBy(() -> roiService.computeRoiAnalysis(tenantId, property.getId(),
            sourceId, 10,
            new BigDecimal("0.07"), new BigDecimal("0.03"), new BigDecimal("0.03")))
            .isInstanceOf(EntityNotFoundException.class);
}
```

- [ ] **Step 10: Run all tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest" -q`
Expected: All 4 tests PASS

- [ ] **Step 11: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyRoiService.java \
       backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyRoiServiceTest.java \
       backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java
git commit -m "feat(core): add PropertyRoiService with sell scenario computation

Computes net sale proceeds accounting for 6% selling costs, Section
1250 depreciation recapture at 25%, and long-term capital gains at
15%. Net proceeds are compounded at a user-specified investment return
rate over the comparison period. Hold scenario stubbed for Task 3."
```

---

### Task 3: PropertyRoiService — Hold Scenario Computation + Tests

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyRoiService.java`
- Modify: `backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyRoiServiceTest.java`

- [ ] **Step 1: Write failing test for hold scenario — no mortgage**

Add to `PropertyRoiServiceTest.java`:

```java
@Test
void computeRoiAnalysis_holdScenario_noMortgage() {
    // Property: 300k purchase, 400k current value, 3% appreciation
    // Rent: 24k/year, rent growth 3%, expenses: tax 4k + ins 2k + maint 1k = 7k, exp inflation 3%
    var property = createInvestmentProperty(
            new BigDecimal("300000"), new BigDecimal("400000"), "none");
    property.setAnnualAppreciationRate(new BigDecimal("0.03"));
    property.setAnnualPropertyTax(new BigDecimal("4000"));
    property.setAnnualInsuranceCost(new BigDecimal("2000"));
    property.setAnnualMaintenanceCost(new BigDecimal("1000"));

    var incomeSource = createIncomeSource(property, "Rental", new BigDecimal("24000"));

    when(propertyRepository.findByTenant_IdAndId(tenantId, property.getId()))
            .thenReturn(Optional.of(property));
    when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSource.getId()))
            .thenReturn(Optional.of(incomeSource));

    var result = roiService.computeRoiAnalysis(tenantId, property.getId(),
            incomeSource.getId(), 10,
            new BigDecimal("0.07"), new BigDecimal("0.03"), new BigDecimal("0.03"));

    var hold = result.hold();

    // Property value after 10 years: 400,000 * 1.03^10 = ~537,566.97
    assertThat(hold.endingPropertyValue()).isEqualByComparingTo("537566.9653");
    // No mortgage
    assertThat(hold.endingMortgageBalance()).isEqualByComparingTo("0");
    // Cumulative net cash flow: sum of (rent_yr - expenses_yr) for 10 years
    // Year 1: 24,000 - 7,000 = 17,000
    // Each year both grow at 3%, so net = 17,000 * (1.03^0 + 1.03^1 + ... + 1.03^9)
    // = 17,000 * ((1.03^10 - 1) / 0.03) = 17,000 * 11.4639 = ~194,886.84
    assertThat(hold.cumulativeNetCashFlow()).isEqualByComparingTo("194886.8379");
    // Ending net worth = property value + cash flow - mortgage
    assertThat(hold.endingNetWorth()).isEqualByComparingTo("732453.8032");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest#computeRoiAnalysis_holdScenario_noMortgage" -q`
Expected: FAIL — hold returns zeros.

- [ ] **Step 3: Implement computeHoldScenario**

Replace the stubbed `computeHoldScenario` in `PropertyRoiService.java`:

```java
HoldScenarioResult computeHoldScenario(PropertyEntity property,
                                         BigDecimal annualRent, int years,
                                         BigDecimal rentGrowth,
                                         BigDecimal expenseInflation) {
    var appreciationRate = property.getAnnualAppreciationRate() != null
            ? property.getAnnualAppreciationRate() : BigDecimal.ZERO;

    // Project property value
    var endingPropertyValue = compound(property.getCurrentValue(), appreciationRate, years);

    // Project mortgage balance
    BigDecimal endingMortgageBalance;
    BigDecimal annualMortgagePayment;
    if (property.hasLoanDetails()) {
        var futureDate = LocalDate.now().plusYears(years);
        endingMortgageBalance = AmortizationCalculator.remainingBalance(
                property.getLoanAmount(), property.getAnnualInterestRate(),
                property.getLoanTermMonths(), property.getLoanStartDate(), futureDate);
        annualMortgagePayment = AmortizationCalculator.monthlyPayment(
                property.getLoanAmount(), property.getAnnualInterestRate(),
                property.getLoanTermMonths()).multiply(new BigDecimal("12"));
    } else {
        endingMortgageBalance = BigDecimal.ZERO;
        annualMortgagePayment = BigDecimal.ZERO;
    }

    // Annual operating expenses from property entity
    var baseExpenses = sumNullable(
            property.getAnnualPropertyTax(),
            property.getAnnualInsuranceCost(),
            property.getAnnualMaintenanceCost());

    // Accumulate net cash flow year by year
    var cumulativeNetCashFlow = BigDecimal.ZERO;
    var currentRent = annualRent;
    var currentExpenses = baseExpenses;
    var rentMultiplier = BigDecimal.ONE.add(rentGrowth);
    var expenseMultiplier = BigDecimal.ONE.add(expenseInflation);

    for (int y = 0; y < years; y++) {
        // Mortgage payment is fixed (doesn't inflate)
        // But it stops once the loan is paid off
        BigDecimal mortgageThisYear;
        if (property.hasLoanDetails()) {
            var yearEndDate = LocalDate.now().plusYears(y + 1);
            var yearStartBalance = AmortizationCalculator.remainingBalance(
                    property.getLoanAmount(), property.getAnnualInterestRate(),
                    property.getLoanTermMonths(), property.getLoanStartDate(),
                    LocalDate.now().plusYears(y));
            if (yearStartBalance.compareTo(BigDecimal.ZERO) <= 0) {
                mortgageThisYear = BigDecimal.ZERO;
            } else {
                mortgageThisYear = annualMortgagePayment.min(
                        yearStartBalance.add(yearStartBalance.multiply(
                                property.getAnnualInterestRate())));
                // Simpler: just use the fixed payment if balance > 0
                mortgageThisYear = annualMortgagePayment;
            }
        } else {
            mortgageThisYear = BigDecimal.ZERO;
        }

        var netCashFlow = currentRent.subtract(currentExpenses).subtract(mortgageThisYear);
        cumulativeNetCashFlow = cumulativeNetCashFlow.add(netCashFlow);

        currentRent = currentRent.multiply(rentMultiplier);
        currentExpenses = currentExpenses.multiply(expenseMultiplier);
    }

    cumulativeNetCashFlow = cumulativeNetCashFlow.setScale(SCALE, ROUNDING);
    endingPropertyValue = endingPropertyValue.setScale(SCALE, ROUNDING);
    endingMortgageBalance = endingMortgageBalance.setScale(SCALE, ROUNDING);

    var endingNetWorth = endingPropertyValue
            .subtract(endingMortgageBalance)
            .add(cumulativeNetCashFlow);

    return new HoldScenarioResult(endingPropertyValue, endingMortgageBalance,
            cumulativeNetCashFlow, endingNetWorth);
}

private BigDecimal sumNullable(BigDecimal... values) {
    var sum = BigDecimal.ZERO;
    for (var v : values) {
        if (v != null) {
            sum = sum.add(v);
        }
    }
    return sum;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest#computeRoiAnalysis_holdScenario_noMortgage" -q`
Expected: PASS

- [ ] **Step 5: Write test for hold scenario with mortgage**

Add to `PropertyRoiServiceTest.java`:

```java
@Test
void computeRoiAnalysis_holdScenario_withMortgage() {
    var property = createInvestmentPropertyWithLoan(
            new BigDecimal("300000"), new BigDecimal("400000"),
            new BigDecimal("240000"), new BigDecimal("0.065"),
            360, LocalDate.of(2020, 1, 1));
    property.setAnnualAppreciationRate(new BigDecimal("0.03"));
    property.setAnnualPropertyTax(new BigDecimal("4000"));
    property.setAnnualInsuranceCost(new BigDecimal("2000"));
    property.setAnnualMaintenanceCost(new BigDecimal("1000"));

    var incomeSource = createIncomeSource(property, "Rental", new BigDecimal("30000"));

    when(propertyRepository.findByTenant_IdAndId(tenantId, property.getId()))
            .thenReturn(Optional.of(property));
    when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSource.getId()))
            .thenReturn(Optional.of(incomeSource));

    var result = roiService.computeRoiAnalysis(tenantId, property.getId(),
            incomeSource.getId(), 10,
            new BigDecimal("0.07"), new BigDecimal("0.03"), new BigDecimal("0.03"));

    var hold = result.hold();

    // Property value grows
    assertThat(hold.endingPropertyValue()).isGreaterThan(new BigDecimal("400000"));
    // Mortgage should still have a balance (30yr loan, only ~16 years in)
    assertThat(hold.endingMortgageBalance()).isGreaterThan(BigDecimal.ZERO);
    // Net worth = property value - mortgage + cumulative cash flow
    assertThat(hold.endingNetWorth()).isEqualByComparingTo(
            hold.endingPropertyValue()
                    .subtract(hold.endingMortgageBalance())
                    .add(hold.cumulativeNetCashFlow()));
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest#computeRoiAnalysis_holdScenario_withMortgage" -q`
Expected: PASS

- [ ] **Step 7: Write test for advantage field**

Add to `PropertyRoiServiceTest.java`:

```java
@Test
void computeRoiAnalysis_advantageField_reflectsWinner() {
    var property = createInvestmentProperty(
            new BigDecimal("300000"), new BigDecimal("400000"), "none");
    property.setAnnualAppreciationRate(new BigDecimal("0.03"));
    property.setAnnualPropertyTax(new BigDecimal("4000"));
    property.setAnnualInsuranceCost(new BigDecimal("2000"));
    property.setAnnualMaintenanceCost(new BigDecimal("1000"));

    var incomeSource = createIncomeSource(property, "Rental", new BigDecimal("24000"));

    when(propertyRepository.findByTenant_IdAndId(tenantId, property.getId()))
            .thenReturn(Optional.of(property));
    when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSource.getId()))
            .thenReturn(Optional.of(incomeSource));

    var result = roiService.computeRoiAnalysis(tenantId, property.getId(),
            incomeSource.getId(), 10,
            new BigDecimal("0.07"), new BigDecimal("0.03"), new BigDecimal("0.03"));

    assertThat(result.advantage()).isIn("hold", "sell");
    assertThat(result.advantageAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

    var expectedAdvantageAmount = result.hold().endingNetWorth()
            .subtract(result.sell().endingNetWorth()).abs();
    assertThat(result.advantageAmount()).isEqualByComparingTo(expectedAdvantageAmount);
}
```

- [ ] **Step 8: Run all PropertyRoiServiceTest tests**

Run: `cd backend && mvn test -pl wealthview-core -Dtest="PropertyRoiServiceTest" -q`
Expected: All tests PASS

- [ ] **Step 9: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyRoiService.java \
       backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyRoiServiceTest.java
git commit -m "feat(core): add hold scenario computation to PropertyRoiService

Projects rental income (with growth), operating expenses (with
inflation), and mortgage payments over N years. Calculates ending
net worth as property value minus remaining mortgage plus cumulative
net cash flow. Combined with sell scenario for full hold-vs-sell
comparison."
```

---

### Task 4: Controller Endpoint + Controller Test

**Files:**
- Modify: `backend/wealthview-api/src/main/java/com/wealthview/api/controller/PropertyController.java`
- Create: `backend/wealthview-api/src/test/java/com/wealthview/api/controller/PropertyControllerRoiTest.java`

- [ ] **Step 1: Write the failing controller test**

Create `PropertyControllerRoiTest.java`:

```java
package com.wealthview.api.controller;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.property.PropertyAnalyticsService;
import com.wealthview.core.property.PropertyRoiService;
import com.wealthview.core.property.PropertyService;
import com.wealthview.core.property.PropertyValuationService;
import com.wealthview.core.property.PropertyValuationSyncService;
import com.wealthview.core.property.dto.HoldScenarioResult;
import com.wealthview.core.property.dto.RoiAnalysisResponse;
import com.wealthview.core.property.dto.SellScenarioResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PropertyController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class PropertyControllerRoiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PropertyService propertyService;

    @MockBean
    private PropertyValuationService valuationService;

    @MockBean
    private PropertyAnalyticsService analyticsService;

    @MockBean
    private PropertyRoiService roiService;

    @MockBean(name = "propertyValuationSyncService")
    private PropertyValuationSyncService syncService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID PROPERTY_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();

    @Test
    void getRoiAnalysis_authenticated_returns200() throws Exception {
        var response = new RoiAnalysisResponse(
                "Rental Income",
                new BigDecimal("24000"),
                10,
                new HoldScenarioResult(
                        new BigDecimal("537566.97"),
                        BigDecimal.ZERO,
                        new BigDecimal("194886.84"),
                        new BigDecimal("732453.81")
                ),
                new SellScenarioResult(
                        new BigDecimal("400000"),
                        new BigDecimal("24000"),
                        BigDecimal.ZERO,
                        new BigDecimal("11400"),
                        new BigDecimal("364600"),
                        new BigDecimal("717216.88")
                ),
                "hold",
                new BigDecimal("15236.93")
        );

        when(roiService.computeRoiAnalysis(
                eq(TENANT_ID), eq(PROPERTY_ID), eq(SOURCE_ID),
                eq(10), eq(new BigDecimal("0.07")),
                eq(new BigDecimal("0.03")), eq(new BigDecimal("0.03"))))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis",
                        PROPERTY_ID, SOURCE_ID)
                        .param("years", "10")
                        .param("investment_return", "0.07")
                        .param("rent_growth", "0.03")
                        .param("expense_inflation", "0.03")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income_source_name").value("Rental Income"))
                .andExpect(jsonPath("$.annual_rent").value(24000))
                .andExpect(jsonPath("$.comparison_years").value(10))
                .andExpect(jsonPath("$.hold.ending_property_value").value(537566.97))
                .andExpect(jsonPath("$.sell.net_proceeds").value(364600))
                .andExpect(jsonPath("$.advantage").value("hold"))
                .andExpect(jsonPath("$.advantage_amount").value(15236.93));
    }

    @Test
    void getRoiAnalysis_withDefaults_usesDefaultParams() throws Exception {
        var response = new RoiAnalysisResponse(
                "Rental", new BigDecimal("24000"), 10,
                new HoldScenarioResult(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO),
                new SellScenarioResult(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                "sell", BigDecimal.ZERO
        );

        when(roiService.computeRoiAnalysis(
                eq(TENANT_ID), eq(PROPERTY_ID), eq(SOURCE_ID),
                eq(10), eq(new BigDecimal("0.07")),
                eq(new BigDecimal("0.03")), eq(new BigDecimal("0.03"))))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis",
                        PROPERTY_ID, SOURCE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void getRoiAnalysis_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis",
                        PROPERTY_ID, SOURCE_ID))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -pl wealthview-api -Dtest="PropertyControllerRoiTest#getRoiAnalysis_authenticated_returns200" -q`
Expected: FAIL — no endpoint mapped.

- [ ] **Step 3: Add endpoint to PropertyController**

Add to `PropertyController.java` — new field and constructor parameter for `PropertyRoiService`, and the endpoint method:

Add import:
```java
import com.wealthview.core.property.PropertyRoiService;
import com.wealthview.core.property.dto.RoiAnalysisResponse;
```

Add field and update constructor:
```java
private final PropertyRoiService roiService;

public PropertyController(PropertyService propertyService,
                          PropertyValuationService valuationService,
                          PropertyAnalyticsService analyticsService,
                          PropertyRoiService roiService,
                          @Nullable PropertyValuationSyncService syncService) {
    this.propertyService = propertyService;
    this.valuationService = valuationService;
    this.analyticsService = analyticsService;
    this.roiService = roiService;
    this.syncService = syncService;
}
```

Add endpoint:
```java
@GetMapping("/{propertyId}/income-sources/{sourceId}/roi-analysis")
public ResponseEntity<RoiAnalysisResponse> getRoiAnalysis(
        @AuthenticationPrincipal TenantUserPrincipal principal,
        @PathVariable UUID propertyId,
        @PathVariable UUID sourceId,
        @RequestParam(defaultValue = "10") int years,
        @RequestParam(name = "investment_return", defaultValue = "0.07") BigDecimal investmentReturn,
        @RequestParam(name = "rent_growth", defaultValue = "0.03") BigDecimal rentGrowth,
        @RequestParam(name = "expense_inflation", defaultValue = "0.03") BigDecimal expenseInflation) {
    return ResponseEntity.ok(roiService.computeRoiAnalysis(
            principal.tenantId(), propertyId, sourceId,
            years, investmentReturn, rentGrowth, expenseInflation));
}
```

- [ ] **Step 4: Fix existing PropertyController tests**

The existing `PropertyControllerTest` and `PropertyControllerAnalyticsTest` will fail because the constructor now requires `PropertyRoiService`. Add `@MockBean private PropertyRoiService roiService;` to both test classes.

- [ ] **Step 5: Run all controller tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-api -Dtest="PropertyControllerRoiTest,PropertyControllerTest,PropertyControllerAnalyticsTest" -q`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add backend/wealthview-api/src/main/java/com/wealthview/api/controller/PropertyController.java \
       backend/wealthview-api/src/test/java/com/wealthview/api/controller/PropertyControllerRoiTest.java \
       backend/wealthview-api/src/test/java/com/wealthview/api/controller/PropertyControllerTest.java \
       backend/wealthview-api/src/test/java/com/wealthview/api/controller/PropertyControllerAnalyticsTest.java
git commit -m "feat(api): add ROI analysis endpoint on PropertyController

GET /api/v1/properties/{id}/income-sources/{sourceId}/roi-analysis
with query params for years, investment_return, rent_growth, and
expense_inflation (all with sensible defaults). Returns hold vs sell
comparison for the given income source."
```

---

### Task 5: Frontend Types and API Client

**Files:**
- Modify: `frontend/src/types/property.ts`
- Modify: `frontend/src/api/properties.ts`

- [ ] **Step 1: Add TypeScript interfaces**

Add to `frontend/src/types/property.ts`:

```typescript
export interface HoldScenarioResult {
    ending_property_value: number;
    ending_mortgage_balance: number;
    cumulative_net_cash_flow: number;
    ending_net_worth: number;
}

export interface SellScenarioResult {
    gross_proceeds: number;
    selling_costs: number;
    depreciation_recapture_tax: number;
    capital_gains_tax: number;
    net_proceeds: number;
    ending_net_worth: number;
}

export interface RoiAnalysisResponse {
    income_source_name: string;
    annual_rent: number;
    comparison_years: number;
    hold: HoldScenarioResult;
    sell: SellScenarioResult;
    advantage: 'hold' | 'sell';
    advantage_amount: number;
}
```

- [ ] **Step 2: Add API client function**

Add to `frontend/src/api/properties.ts`:

```typescript
import type { ..., RoiAnalysisResponse } from '../types/property';

export async function getRoiAnalysis(
    propertyId: string,
    sourceId: string,
    params: { years: number; investmentReturn: number; rentGrowth: number; expenseInflation: number }
): Promise<RoiAnalysisResponse> {
    const { data } = await client.get<RoiAnalysisResponse>(
        `/properties/${propertyId}/income-sources/${sourceId}/roi-analysis`,
        {
            params: {
                years: params.years,
                investment_return: params.investmentReturn,
                rent_growth: params.rentGrowth,
                expense_inflation: params.expenseInflation,
            },
        }
    );
    return data;
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/property.ts frontend/src/api/properties.ts
git commit -m "feat(frontend): add ROI analysis types and API client

TypeScript interfaces for HoldScenarioResult, SellScenarioResult,
and RoiAnalysisResponse. API function calls the new
properties/{id}/income-sources/{sourceId}/roi-analysis endpoint."
```

---

### Task 6: PropertyRoiCard Component

**Files:**
- Create: `frontend/src/components/PropertyRoiCard.tsx`

- [ ] **Step 1: Create the component**

```tsx
import { useState, useEffect, useCallback } from 'react';
import { getRoiAnalysis } from '../api/properties';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import type { RoiAnalysisResponse } from '../types/property';
import type { IncomeSource } from '../types/projection';

interface PropertyRoiCardProps {
    propertyId: string;
    incomeSource: IncomeSource;
}

const YEAR_OPTIONS = [5, 10, 15, 20];

export default function PropertyRoiCard({ propertyId, incomeSource }: PropertyRoiCardProps) {
    const [years, setYears] = useState(10);
    const [investmentReturn, setInvestmentReturn] = useState('7');
    const [rentGrowth, setRentGrowth] = useState('3');
    const [expenseInflation, setExpenseInflation] = useState('3');
    const [analysis, setAnalysis] = useState<RoiAnalysisResponse | null>(null);
    const [loading, setLoading] = useState(false);

    const fetchAnalysis = useCallback(() => {
        const ir = parseFloat(investmentReturn);
        const rg = parseFloat(rentGrowth);
        const ei = parseFloat(expenseInflation);
        if (isNaN(ir) || isNaN(rg) || isNaN(ei)) return;

        setLoading(true);
        getRoiAnalysis(propertyId, incomeSource.id, {
            years,
            investmentReturn: ir / 100,
            rentGrowth: rg / 100,
            expenseInflation: ei / 100,
        })
            .then(setAnalysis)
            .catch(() => setAnalysis(null))
            .finally(() => setLoading(false));
    }, [propertyId, incomeSource.id, years, investmentReturn, rentGrowth, expenseInflation]);

    useEffect(() => {
        const timer = setTimeout(fetchAnalysis, 400);
        return () => clearTimeout(timer);
    }, [fetchAnalysis]);

    const inputStyle = {
        padding: '0.3rem 0.5rem',
        border: '1px solid #ccc',
        borderRadius: '4px',
        fontSize: '0.85rem',
        width: '70px',
    };

    const labelStyle = {
        fontSize: '0.75rem',
        color: '#666',
        marginBottom: '0.2rem',
    };

    const columnStyle = {
        flex: 1,
        padding: '1rem',
        background: '#f9f9f9',
        borderRadius: '8px',
    };

    const metricStyle = {
        display: 'flex',
        justifyContent: 'space-between',
        padding: '0.3rem 0',
        fontSize: '0.9rem',
    };

    return (
        <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                <div>
                    <h4 style={{ margin: 0, fontSize: '1rem' }}>{incomeSource.name}</h4>
                    <div style={{ fontSize: '0.85rem', color: '#666' }}>
                        {formatCurrency(incomeSource.annual_amount)}/year ({formatCurrency(incomeSource.annual_amount / 12)}/month)
                    </div>
                </div>
                {loading && <span style={{ fontSize: '0.8rem', color: '#999' }}>Calculating...</span>}
            </div>

            <div style={{ display: 'flex', gap: '1.5rem', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
                <div>
                    <div style={labelStyle}>Period</div>
                    <select value={years} onChange={e => setYears(Number(e.target.value))} style={{ ...inputStyle, width: '80px' }}>
                        {YEAR_OPTIONS.map(y => <option key={y} value={y}>{y} years</option>)}
                    </select>
                </div>
                <div>
                    <div style={labelStyle}>Investment Return %</div>
                    <input type="number" value={investmentReturn} onChange={e => setInvestmentReturn(e.target.value)} style={inputStyle} step="0.5" min="0" max="20" />
                </div>
                <div>
                    <div style={labelStyle}>Rent Growth %</div>
                    <input type="number" value={rentGrowth} onChange={e => setRentGrowth(e.target.value)} style={inputStyle} step="0.5" min="0" max="10" />
                </div>
                <div>
                    <div style={labelStyle}>Expense Inflation %</div>
                    <input type="number" value={expenseInflation} onChange={e => setExpenseInflation(e.target.value)} style={inputStyle} step="0.5" min="0" max="10" />
                </div>
            </div>

            {analysis && (
                <>
                    <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem' }}>
                        <div style={columnStyle}>
                            <h5 style={{ margin: '0 0 0.75rem', color: '#1565c0' }}>Hold & Rent</h5>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Property Value</span>
                                <span>{formatCurrency(analysis.hold.ending_property_value)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Mortgage Balance</span>
                                <span>{formatCurrency(analysis.hold.ending_mortgage_balance)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Cumulative Cash Flow</span>
                                <span style={{ color: analysis.hold.cumulative_net_cash_flow >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                    {formatCurrency(analysis.hold.cumulative_net_cash_flow)}
                                </span>
                            </div>
                            <div style={{ ...metricStyle, borderTop: '2px solid #ddd', paddingTop: '0.5rem', marginTop: '0.3rem', fontWeight: 700 }}>
                                <span>Net Worth</span>
                                <span>{formatCurrency(analysis.hold.ending_net_worth)}</span>
                            </div>
                        </div>

                        <div style={columnStyle}>
                            <h5 style={{ margin: '0 0 0.75rem', color: '#e65100' }}>Sell & Invest</h5>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Gross Proceeds</span>
                                <span>{formatCurrency(analysis.sell.gross_proceeds)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Selling Costs (6%)</span>
                                <span style={{ color: '#d32f2f' }}>-{formatCurrency(analysis.sell.selling_costs)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Depreciation Recapture Tax</span>
                                <span style={{ color: '#d32f2f' }}>
                                    {analysis.sell.depreciation_recapture_tax > 0 ? `-${formatCurrency(analysis.sell.depreciation_recapture_tax)}` : '$0'}
                                </span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Capital Gains Tax</span>
                                <span style={{ color: '#d32f2f' }}>-{formatCurrency(analysis.sell.capital_gains_tax)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Net Proceeds (Invested)</span>
                                <span>{formatCurrency(analysis.sell.net_proceeds)}</span>
                            </div>
                            <div style={{ ...metricStyle, borderTop: '2px solid #ddd', paddingTop: '0.5rem', marginTop: '0.3rem', fontWeight: 700 }}>
                                <span>Net Worth</span>
                                <span>{formatCurrency(analysis.sell.ending_net_worth)}</span>
                            </div>
                        </div>
                    </div>

                    <div style={{
                        padding: '0.75rem 1rem',
                        borderRadius: '8px',
                        background: analysis.advantage === 'hold' ? '#e8f5e9' : '#fff3e0',
                        color: analysis.advantage === 'hold' ? '#2e7d32' : '#e65100',
                        fontWeight: 700,
                        fontSize: '0.95rem',
                        textAlign: 'center',
                    }}>
                        {analysis.advantage === 'hold' ? 'Holding' : 'Selling'} is better by {formatCurrency(analysis.advantage_amount)} over {analysis.comparison_years} years
                    </div>
                </>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/PropertyRoiCard.tsx
git commit -m "feat(frontend): add PropertyRoiCard component

Displays hold vs sell comparison for a single income source.
Inputs for period, investment return, rent growth, and expense
inflation with debounced API calls. Two-column layout showing
key metrics for each scenario with advantage summary."
```

---

### Task 7: Integrate PropertyRoiCard into PropertyDetailPage

**Files:**
- Modify: `frontend/src/pages/PropertyDetailPage.tsx`

- [ ] **Step 1: Update PropertyDetailPage to show multiple income sources and ROI cards**

Currently, the page finds a single `linkedIncomeSource`. Change it to find all linked income sources and render a `PropertyRoiCard` for each.

At the top of the file, add imports:
```typescript
import PropertyRoiCard from '../components/PropertyRoiCard';
import type { IncomeSource } from '../types/projection';
```

Replace the `linkedIncomeSource` memo:
```typescript
const linkedIncomeSources = useMemo(() => {
    if (!allIncomeSources || !id) return [];
    return allIncomeSources.filter(s => s.property_id === id);
}, [allIncomeSources, id]);
```

Replace the "Linked Income Source" card section (the block starting with `{property && (` that renders the linked income source info and ends before `{analytics && (`):

```tsx
{property && (
    <div style={{ ...cardStyle, marginBottom: '2rem', padding: '1rem 1.5rem' }}>
        <h4 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem', color: '#444' }}>Linked Income Sources</h4>
        {linkedIncomeSources.length > 0 ? (
            linkedIncomeSources.map(source => (
                <div key={source.id} style={{ display: 'flex', alignItems: 'center', gap: '1rem', padding: '0.5rem 0' }}>
                    <div>
                        <div style={{ fontWeight: 600 }}>{source.name}</div>
                        <div style={{ fontSize: '0.9rem', color: '#666' }}>
                            {formatCurrency(source.annual_amount)}/year ({formatCurrency(source.annual_amount / 12)}/month)
                        </div>
                    </div>
                </div>
            ))
        ) : (
            <div style={{ color: '#999', fontSize: '0.9rem' }}>
                No income source linked. <Link to="/income-sources" style={{ color: '#1976d2', textDecoration: 'none' }}>Create one on the Income Sources page.</Link>
            </div>
        )}
        {linkedIncomeSources.length > 0 && (
            <div style={{ marginTop: '0.5rem' }}>
                <Link to="/income-sources" style={{ color: '#1976d2', textDecoration: 'none', fontSize: '0.85rem' }}>
                    Manage on Income Sources page
                </Link>
            </div>
        )}
    </div>
)}
```

After the `PropertyAnalyticsSection`, add the ROI cards section (only for investment properties with linked income sources):

```tsx
{property?.property_type === 'investment' && linkedIncomeSources.length > 0 && (
    <div style={{ marginBottom: '2rem' }}>
        <h3 style={{ marginBottom: '1rem' }}>Hold vs. Sell Analysis</h3>
        {linkedIncomeSources.map(source => (
            <PropertyRoiCard
                key={source.id}
                propertyId={id!}
                incomeSource={source}
            />
        ))}
    </div>
)}
```

- [ ] **Step 2: Verify the build compiles**

Run: `cd frontend && npm run build`
Expected: Build succeeds with no TypeScript errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/PropertyDetailPage.tsx
git commit -m "feat(frontend): integrate ROI cards into property detail page

Shows a PropertyRoiCard for each linked income source on investment
properties. Updated linked income source display to handle multiple
sources. ROI analysis section appears after the analytics section."
```

---

### Task 8: Full Backend Test Suite Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all backend unit tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-api,wealthview-import,wealthview-projection -q`
Expected: All tests PASS with no regressions.

- [ ] **Step 2: Run frontend build and lint**

Run: `cd frontend && npm run build && npm run lint`
Expected: Both pass.

- [ ] **Step 3: If any tests fail, fix them before proceeding**

Investigate and fix any regression. Common issues:
- Other PropertyController tests missing the new `PropertyRoiService` mock bean
- Import ordering issues

---
