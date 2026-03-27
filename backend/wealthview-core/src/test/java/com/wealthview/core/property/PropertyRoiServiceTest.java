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

    @Mock private PropertyRepository propertyRepository;
    @Mock private IncomeSourceRepository incomeSourceRepository;

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
        // Invested at 7% for 10 years: 364,600 * 1.07^10 = ~717,223.38
        assertThat(sell.endingNetWorth()).isEqualByComparingTo("717223.3849");
    }

    @Test
    void computeRoiAnalysis_sellScenario_withStraightLineDepreciation() {
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
        assertThat(sell.depreciationRecaptureTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(sell.netProceeds()).isLessThan(new BigDecimal("364600"));
    }

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

        // Property value after 10 years: 400,000 * 1.03^10
        assertThat(hold.endingPropertyValue()).isGreaterThan(new BigDecimal("530000"));
        // No mortgage
        assertThat(hold.endingMortgageBalance()).isEqualByComparingTo("0");
        // Cumulative net cash flow should be positive (rent > expenses)
        assertThat(hold.cumulativeNetCashFlow()).isGreaterThan(new BigDecimal("170000"));
        // Ending net worth = property value + cash flow - mortgage
        assertThat(hold.endingNetWorth()).isEqualByComparingTo(
                hold.endingPropertyValue()
                        .subtract(hold.endingMortgageBalance())
                        .add(hold.cumulativeNetCashFlow()));
    }

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

    // --- Helper methods ---

    private PropertyEntity createInvestmentProperty(BigDecimal purchasePrice,
                                                      BigDecimal currentValue,
                                                      String depreciationMethod) {
        var property = new PropertyEntity(tenant, "123 Test St", purchasePrice,
                LocalDate.of(2020, 1, 15), currentValue, BigDecimal.ZERO);
        property.setPropertyType("investment");
        property.setDepreciationMethod(depreciationMethod);
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
