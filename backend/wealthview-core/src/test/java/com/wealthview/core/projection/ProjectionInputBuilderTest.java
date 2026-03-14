package com.wealthview.core.projection;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.LinkedAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.property.DepreciationCalculator;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.PropertyDepreciationScheduleRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectionInputBuilderTest {

    @Mock
    private AccountService accountService;

    @Mock
    private ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;

    @Mock
    private PropertyDepreciationScheduleRepository depreciationScheduleRepository;

    @Mock
    private DepreciationCalculator depreciationCalculator;

    @InjectMocks
    private ProjectionInputBuilder builder;

    private UUID tenantId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void build_withHypotheticalAccount_usesStoredBalance() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("10000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(projAcct);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.accounts()).hasSize(1);
        assertThat(result.accounts().getFirst()).isInstanceOf(HypotheticalAccountInput.class);
        assertThat(result.accounts().getFirst().initialBalance())
                .isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    void build_withLinkedAccount_resolvesCurrentBalance() {
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("10000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(projAcct);

        var currentBalance = new BigDecimal("150000.00");
        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(currentBalance);
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.accounts()).hasSize(1);
        assertThat(result.accounts().getFirst()).isInstanceOf(LinkedAccountInput.class);
        assertThat(result.accounts().getFirst().initialBalance())
                .isEqualByComparingTo(currentBalance);
    }

    @Test
    void build_withIncomeSources_resolvesFromLinks() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Social Security", "social_security",
                new BigDecimal("24000"), 67, null,
                BigDecimal.ZERO, false, "taxable");
        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, new BigDecimal("28000"));

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        assertThat(result.incomeSources().getFirst().name()).isEqualTo("Social Security");
        assertThat(result.incomeSources().getFirst().annualAmount())
                .isEqualByComparingTo(new BigDecimal("28000"));
    }

    @Test
    void build_withNoIncomeSources_returnsEmptyList() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).isEmpty();
    }

    @Test
    void build_withRentalPropertyStraightLine_computesDepreciation() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var property = new PropertyEntity(tenant, "123 Main St",
                new BigDecimal("300000"), LocalDate.of(2020, 6, 1),
                new BigDecimal("300000"), BigDecimal.ZERO);
        property.setDepreciationMethod("straight_line");
        property.setInServiceDate(LocalDate.of(2020, 6, 1));
        property.setLandValue(new BigDecimal("50000"));
        property.setUsefulLifeYears(new BigDecimal("27"));

        var incomeSource = new IncomeSourceEntity(
                tenant, "Rental Income", "rental_property",
                new BigDecimal("24000"), 0, null,
                BigDecimal.ZERO, false, "taxable");
        incomeSource.setProperty(property);

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var depByYear = Map.of(2020, new BigDecimal("4629.63"), 2021, new BigDecimal("9259.26"));
        when(depreciationCalculator.computeStraightLine(
                new BigDecimal("300000"), new BigDecimal("50000"),
                LocalDate.of(2020, 6, 1), new BigDecimal("27")))
                .thenReturn(depByYear);

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        assertThat(result.incomeSources().getFirst().depreciationMethod()).isEqualTo("straight_line");
        assertThat(result.incomeSources().getFirst().depreciationByYear()).isEqualTo(depByYear);
    }

    @Test
    void build_withSpendingProfile_includesSpendingInput() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var profile = new SpendingProfileEntity(tenant, "Standard",
                new BigDecimal("40000"), new BigDecimal("20000"), null);
        scenario.setSpendingProfile(profile);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.spendingProfile()).isNotNull();
        assertThat(result.spendingProfile().essentialExpenses())
                .isEqualByComparingTo(new BigDecimal("40000"));
        assertThat(result.spendingProfile().discretionaryExpenses())
                .isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    void build_rentalWithProperty_populatesExpenses() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var property = new PropertyEntity(tenant, "123 Main St",
                new BigDecimal("300000"), LocalDate.of(2020, 6, 1),
                new BigDecimal("300000"), BigDecimal.ZERO);
        property.setDepreciationMethod("none");
        property.setAnnualPropertyTax(new BigDecimal("5000"));
        property.setAnnualInsuranceCost(new BigDecimal("1200"));
        property.setAnnualMaintenanceCost(new BigDecimal("2400"));

        var incomeSource = new IncomeSourceEntity(
                tenant, "Rental Income", "rental_property",
                new BigDecimal("24000"), 0, null,
                BigDecimal.ZERO, false, "taxable");
        incomeSource.setProperty(property);

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        var input = result.incomeSources().getFirst();
        assertThat(input.annualPropertyTax()).isEqualByComparingTo("5000");
        assertThat(input.annualOperatingExpenses()).isEqualByComparingTo("3600");
        assertThat(input.annualMortgageInterest()).isNull();
    }

    @Test
    void build_rentalWithLoan_populatesMortgageInterest() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var property = new PropertyEntity(tenant, "456 Oak Ave",
                new BigDecimal("400000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("400000"), new BigDecimal("280000"));
        property.setDepreciationMethod("none");
        property.setLoanAmount(new BigDecimal("300000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));

        var incomeSource = new IncomeSourceEntity(
                tenant, "Rental Income", "rental_property",
                new BigDecimal("30000"), 0, null,
                BigDecimal.ZERO, false, "taxable");
        incomeSource.setProperty(property);

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        var input = result.incomeSources().getFirst();
        assertThat(input.annualMortgageInterest()).isNotNull();
        assertThat(input.annualMortgageInterest()).isPositive();
    }

    @Test
    void build_rentalNoProperty_expensesRemainNull() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Hypothetical Rental", "rental_property",
                new BigDecimal("24000"), 0, null,
                BigDecimal.ZERO, false, "taxable");
        // No property linked

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        var input = result.incomeSources().getFirst();
        assertThat(input.annualOperatingExpenses()).isNull();
        assertThat(input.annualMortgageInterest()).isNull();
        assertThat(input.annualPropertyTax()).isNull();
    }

    @Test
    void build_nonRentalWithProperty_expensesRemainNull() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var property = new PropertyEntity(tenant, "789 Elm St",
                new BigDecimal("500000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("500000"), BigDecimal.ZERO);
        property.setAnnualPropertyTax(new BigDecimal("8000"));
        property.setAnnualInsuranceCost(new BigDecimal("2000"));

        var incomeSource = new IncomeSourceEntity(
                tenant, "Pension", "pension",
                new BigDecimal("36000"), 65, null,
                BigDecimal.ZERO, false, "taxable");
        incomeSource.setProperty(property);

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        var input = result.incomeSources().getFirst();
        assertThat(input.annualOperatingExpenses()).isNull();
        assertThat(input.annualMortgageInterest()).isNull();
        assertThat(input.annualPropertyTax()).isNull();
    }

    @Test
    void build_setsScenarioMetadata() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "My Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), "{\"birth_year\":1990}");

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.scenarioId()).isEqualTo(scenario.getId());
        assertThat(result.scenarioName()).isEqualTo("My Plan");
        assertThat(result.retirementDate()).isEqualTo(LocalDate.of(2055, 1, 1));
        assertThat(result.endAge()).isEqualTo(90);
        assertThat(result.inflationRate()).isEqualByComparingTo(new BigDecimal("0.03"));
        assertThat(result.paramsJson()).isEqualTo("{\"birth_year\":1990}");
    }
}
