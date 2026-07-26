package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.projection.SecurityClassificationService.AllocationResult;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.LinkedAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.household.LifeExpectancy;
import com.wealthview.core.projection.mortality.MortalityTable;
import com.wealthview.core.projection.mortality.MortalityTableProvider;
import com.wealthview.core.property.DepreciationCalculator;
import com.wealthview.core.testutil.ScenarioMother;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectionInputBuilderTest {

    @Mock
    private AccountService accountService;

    @Mock
    private ExchangeRateService exchangeRateService;

    @Mock
    private ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;

    @Mock
    private DepreciationCalculator depreciationCalculator;

    @Mock
    private GuardrailSpendingProfileRepository guardrailSpendingProfileRepository;

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private SecurityClassificationService classificationService;

    @Mock
    private MortalityTableProvider mortalityTableProvider;

    @InjectMocks
    private ProjectionInputBuilder builder;

    private UUID tenantId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        lenient().when(depreciationCalculator.computeSchedule(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of());
        lenient().when(exchangeRateService.convertToUsd(any(BigDecimal.class), eq("USD"), any(UUID.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(classificationService.deriveAllocation(any(UUID.class), any()))
                .thenReturn(new AllocationResult(AssetAllocation.ALL_US, Set.of()));
    }

    @Test
    void build_withHypotheticalAccount_usesStoredBalance() {
        var scenario = ScenarioMother.scenario(tenant);
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
        var scenario = ScenarioMother.scenario(tenant);
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
    void toAccountInput_linkedNoStoredAllocation_derivesFromHoldings() {
        var linkedAccount = new AccountEntity(tenant, "Bond Fund", "brokerage", "Vanguard");
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("0"), null, "traditional");
        scenario.addAccount(projAcct);

        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("50000"));
        when(classificationService.deriveAllocation(eq(tenantId), any()))
                .thenReturn(new AllocationResult(
                        AssetAllocation.fromDoubles(Map.of(AssetClass.BOND, 1.0)), Set.of()));
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account).isInstanceOf(LinkedAccountInput.class);
        assertThat(account.allocation().weights().get(AssetClass.BOND))
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(account.expectedReturnOverride()).isEmpty();
    }

    @Test
    void toAccountInput_linked_costBasisSumsHoldingsCostBasis() {
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("10000"), null, "taxable");
        scenario.addAccount(projAcct);

        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("150000"));
        when(accountService.computeCostBasis(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("95000"));
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account).isInstanceOf(LinkedAccountInput.class);
        assertThat(account.costBasis()).isEqualByComparingTo(new BigDecimal("95000"));
    }

    @Test
    void toAccountInput_hypothetical_noStoredCostBasis_defaultsToInitialBalance() {
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "taxable");
        // No cost basis set on the entity.
        scenario.addAccount(projAcct);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account).isInstanceOf(HypotheticalAccountInput.class);
        assertThat(account.costBasis()).isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    void toAccountInput_hypothetical_storedCostBasis_usesStoredValue() {
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "taxable");
        projAcct.setCostBasis(new BigDecimal("40000"));
        scenario.addAccount(projAcct);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account.costBasis()).isEqualByComparingTo(new BigDecimal("40000"));
    }

    @Test
    void toAccountInput_expectedReturnPresent_setsOverride() {
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(projAcct);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account.expectedReturnOverride()).contains(new BigDecimal("0.07"));
        assertThat(account.allocation()).isEqualTo(AssetAllocation.ALL_US);
    }

    @Test
    void toAccountInput_hypotheticalWithStoredAllocation_parsesAllocation() {
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("0"), null, "taxable");
        projAcct.setAllocation(Map.of(
                AssetClass.US_STOCK.key(), new BigDecimal("0.6"),
                AssetClass.BOND.key(), new BigDecimal("0.4")));
        scenario.addAccount(projAcct);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account.allocation().weights().get(AssetClass.US_STOCK))
                .isEqualByComparingTo(new BigDecimal("0.60"));
        assertThat(account.allocation().weights().get(AssetClass.BOND))
                .isEqualByComparingTo(new BigDecimal("0.40"));
        assertThat(account.expectedReturnOverride()).isEmpty();
    }

    @Test
    void build_withIncomeSources_resolvesFromLinks() {
        var scenario = ScenarioMother.scenario(tenant);

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
        var scenario = ScenarioMother.scenario(tenant);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).isEmpty();
    }

    @Test
    void build_withRentalPropertyStraightLine_computesDepreciation() {
        var scenario = ScenarioMother.scenario(tenant);

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
        when(depreciationCalculator.computeSchedule(property))
                .thenReturn(depByYear);

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        assertThat(result.incomeSources().getFirst().depreciationMethod()).isEqualTo("straight_line");
        assertThat(result.incomeSources().getFirst().depreciationByYear()).isEqualTo(depByYear);
    }

    @Test
    void build_withSpendingProfile_includesSpendingInput() {
        var scenario = ScenarioMother.scenario(tenant);
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
        var scenario = ScenarioMother.scenario(tenant);

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
        var scenario = ScenarioMother.scenario(tenant);

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
    void build_withMortgagedProperty_setsMortgagePrincipalOnIncomeSource() {
        var scenario = ScenarioMother.scenario(tenant);

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

        var input = result.incomeSources().getFirst();
        // annualMortgagePrincipal = fullAnnualPayment - annualInterest; both must be > 0
        assertThat(input.annualMortgagePrincipal()).isNotNull();
        assertThat(input.annualMortgagePrincipal()).isPositive();
        // principal + interest should equal full annual payment (within rounding)
        var fullAnnualPayment = com.wealthview.core.property.AmortizationCalculator.monthlyPayment(
                new BigDecimal("300000"), new BigDecimal("0.065"), 360)
                .multiply(new BigDecimal("12"));
        assertThat(input.annualMortgageInterest().add(input.annualMortgagePrincipal()))
                .isEqualByComparingTo(fullAnnualPayment.setScale(4, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void build_rentalNoProperty_expensesRemainNull() {
        var scenario = ScenarioMother.scenario(tenant);

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
        var scenario = ScenarioMother.scenario(tenant);

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

    // ── Household/survivor modeling (sub-project A, T3) ──

    @Test
    void build_withoutSpouseBirthYear_buildsSingleHousehold() {
        var scenario = ScenarioMother.scenarioWithParams(tenant, "{\"birth_year\":1968}");

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().isHousehold()).isFalse();
        assertThat(result.household().primary().birthYear()).isEqualTo(1968);
        assertThat(result.household().spouse()).isNull();
    }

    @Test
    void build_withSpouseBirthYear_buildsTwoPersonHousehold() {
        var scenario = ScenarioMother.scenarioWithParams(
                tenant, "{\"birth_year\":1968,\"spouse_birth_year\":1970}");

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().isHousehold()).isTrue();
        assertThat(result.household().primary().birthYear()).isEqualTo(1968);
        assertThat(result.household().spouse().birthYear()).isEqualTo(1970);
    }

    @Test
    void build_withExplicitDeathAges_usesExplicitValues() {
        var scenario = ScenarioMother.scenarioWithParams(tenant,
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,"
                        + "\"primary_death_age\":88,\"spouse_death_age\":92}");

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().primary().deathYear()).isEqualTo(1968 + 88);
        assertThat(result.household().spouse().deathYear()).isEqualTo(1970 + 92);
    }

    @Test
    void build_withoutExplicitDeathAges_usesSsaDefaults() {
        var scenario = ScenarioMother.scenarioWithParams(
                tenant, "{\"birth_year\":1968,\"spouse_birth_year\":1970}");

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().primary().deathYear())
                .isEqualTo(1968 + LifeExpectancy.defaultDeathAge(1968));
        assertThat(result.household().spouse().deathYear())
                .isEqualTo(1970 + LifeExpectancy.defaultDeathAge(1970));
    }

    @Test
    void build_withSecondDeathBeyondHorizon_secondDeathYearEmptyButTransitionYearPresent() {
        // endAge=90, birthYear=1968 -> horizonEndYear = 2058. Primary dies at 80 (2048, within
        // horizon, the first death -> transitionYear). Spouse (born 2000) dies at 90 (2090, well
        // beyond the horizon) -> secondDeathYear is clamped to empty per HouseholdContext#of.
        var scenario = ScenarioMother.scenarioWithParams(tenant,
                "{\"birth_year\":1968,\"spouse_birth_year\":2000,\"spouse_death_age\":90,"
                        + "\"primary_death_age\":80}");

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.household().transitionYear()).contains(2048);
        assertThat(result.household().secondDeathYear()).isEmpty();
    }

    @Test
    void toAccountInput_ownerPersisted_passesThroughToInput() {
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "traditional");
        projAcct.setOwner("spouse");
        scenario.addAccount(projAcct);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account.owner()).isEqualTo("spouse");
    }

    @Test
    void toAccountInput_ownerNotSet_defaultsToPrimary() {
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(projAcct);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var account = builder.build(scenario, tenantId).accounts().getFirst();

        assertThat(account.owner()).isEqualTo("primary");
    }

    @Test
    void toIncomeSourceInput_ownerAndSurvivorPercentPersisted_passesThrough() {
        var scenario = ScenarioMother.scenario(tenant);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Pension", "pension",
                new BigDecimal("24000"), 65, null,
                BigDecimal.ZERO, false, "taxable");
        incomeSource.setOwner("spouse");
        incomeSource.setSurvivorPercent(new BigDecimal("0.5"));
        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        assertThat(result.incomeSources().getFirst().owner()).isEqualTo("spouse");
        assertThat(result.incomeSources().getFirst().survivorPercent()).isEqualByComparingTo("0.5");
    }

    // ── Guardrail Spending Loading Tests ──

    @Test
    void build_withGuardrailProfile_loadsGuardrailSpending() {
        var scenario = ScenarioMother.scenario(tenant);

        var guardrailEntity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Guardrail", new BigDecimal("30000"));
        guardrailEntity.setYearlySpending("""
                [{"year":2030,"age":62,"recommended":75000,"corridorLow":62000,
                  "corridorHigh":91000,"essentialFloor":30000,"discretionary":45000,
                  "incomeOffset":12000,"portfolioWithdrawal":63000,"phaseName":"Early"}]
                """);
        guardrailEntity.setPhases("[]");
        guardrailEntity.setScenarioHash("abc");
        scenario.setGuardrailProfile(guardrailEntity);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());
        when(guardrailSpendingProfileRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(Optional.of(guardrailEntity));

        var result = builder.build(scenario, tenantId);

        assertThat(result.guardrailSpending()).isNotNull();
        assertThat(result.guardrailSpending().yearlySpending()).hasSize(1);
        assertThat(result.guardrailSpending().yearlySpending().getFirst().year()).isEqualTo(2030);
        assertThat(result.guardrailSpending().yearlySpending().getFirst().recommended())
                .isEqualByComparingTo(new BigDecimal("75000"));
    }

    @Test
    void build_withoutGuardrailProfile_returnsNullGuardrailSpending() {
        var scenario = ScenarioMother.scenario(tenant);
        // No guardrail profile set

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.guardrailSpending()).isNull();
    }

    @Test
    void build_withGuardrailProfileButRepoReturnsEmpty_returnsNullGuardrailSpending() {
        var scenario = ScenarioMother.scenario(tenant);

        var guardrailEntity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Guardrail", new BigDecimal("30000"));
        scenario.setGuardrailProfile(guardrailEntity);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());
        when(guardrailSpendingProfileRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(Optional.empty());

        var result = builder.build(scenario, tenantId);

        assertThat(result.guardrailSpending()).isNull();
    }

    // ── Property Resolution Tests ──

    @Test
    void build_withProperties_populatesPropertyInputs() {
        var scenario = ScenarioMother.scenario(tenant);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var property = new PropertyEntity(tenant, "123 Main St",
                new BigDecimal("400000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("500000"), new BigDecimal("280000"));
        property.setAnnualAppreciationRate(new BigDecimal("0.03"));
        property.setLoanAmount(new BigDecimal("320000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));

        when(propertyRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(property));

        var result = builder.build(scenario, tenantId);

        assertThat(result.properties()).hasSize(1);
        var propInput = result.properties().getFirst();
        assertThat(propInput.name()).isEqualTo("123 Main St");
        assertThat(propInput.currentValue()).isEqualByComparingTo("500000");
        assertThat(propInput.annualAppreciationRate()).isEqualByComparingTo("0.03");
        assertThat(propInput.loanAmount()).isEqualByComparingTo("320000");
        assertThat(propInput.annualInterestRate()).isEqualByComparingTo("0.065");
        assertThat(propInput.loanTermMonths()).isEqualTo(360);
        // Mortgage balance should be computed via amortization (not manual 280000)
        assertThat(propInput.mortgageBalance()).isLessThan(new BigDecimal("320000"));
        assertThat(propInput.mortgageBalance()).isPositive();
    }

    @Test
    void build_withNoProperties_returnsEmptyPropertyList() {
        var scenario = ScenarioMother.scenario(tenant);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());
        when(propertyRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of());

        var result = builder.build(scenario, tenantId);

        assertThat(result.properties()).isEmpty();
    }

    @Test
    void toIncomeSourceInput_costSegProperty_populatesDepreciationByYear() {
        var scenario = ScenarioMother.scenario(tenant);

        var property = new PropertyEntity(tenant, "Beryl St",
                new BigDecimal("500000"), LocalDate.of(2021, 7, 1),
                new BigDecimal("500000"), BigDecimal.ZERO);
        property.setDepreciationMethod("cost_segregation");
        property.setInServiceDate(LocalDate.of(2021, 7, 1));
        property.setLandValue(new BigDecimal("75000"));
        property.setCostSegAllocations("""
                [{"assetClass":"5yr","allocation":80000},
                 {"assetClass":"7yr","allocation":60000},
                 {"assetClass":"15yr","allocation":40000},
                 {"assetClass":"27_5yr","allocation":245000}]
                """);
        property.setBonusDepreciationRate(new BigDecimal("1.0000"));
        property.setCostSegStudyYear(null);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Beryl St Rental", "rental_property",
                new BigDecimal("36000"), 0, null,
                BigDecimal.ZERO, false, "active_participation");
        incomeSource.setProperty(property);

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of(link));

        var expectedSchedule = Map.of(2021, new BigDecimal("184462.1212"));
        when(depreciationCalculator.computeSchedule(property))
                .thenReturn(expectedSchedule);

        var result = builder.build(scenario, tenantId);

        assertThat(result.incomeSources()).hasSize(1);
        assertThat(result.incomeSources().getFirst().depreciationMethod()).isEqualTo("cost_segregation");
        assertThat(result.incomeSources().getFirst().depreciationByYear()).isNotNull();
        assertThat(result.incomeSources().getFirst().depreciationByYear()).isNotEmpty();
        assertThat(result.incomeSources().getFirst().depreciationByYear().get(2021))
                .isEqualByComparingTo(new BigDecimal("184462.1212"));
    }

    // ── Unclassified Symbol Collection Tests ──

    @Test
    void buildWithMetadata_linkedAccountWithUnknownSymbol_returnsNonEmptyUnclassifiedSymbols() {
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("0"), null, "taxable");
        scenario.addAccount(projAcct);

        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("50000"));
        when(classificationService.deriveAllocation(eq(tenantId), any()))
                .thenReturn(new AllocationResult(AssetAllocation.ALL_US, Set.of("ZZZZ")));
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.buildWithMetadata(scenario, tenantId);

        assertThat(result.unclassifiedSymbols()).containsExactly("ZZZZ");
        assertThat(result.input().accounts()).hasSize(1);
    }

    @Test
    void buildWithMetadata_allSymbolsClassified_returnsEmptyUnclassifiedSymbols() {
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("0"), null, "taxable");
        scenario.addAccount(projAcct);

        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("50000"));
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.buildWithMetadata(scenario, tenantId);

        assertThat(result.unclassifiedSymbols()).isEmpty();
    }

    @Test
    void buildWithMetadata_multipleLinkedAccounts_unionsAndDedupesUnclassifiedSymbols() {
        var linkedAccount1 = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var linkedAccount2 = new AccountEntity(tenant, "IRA", "ira", "Vanguard");
        var scenario = ScenarioMother.scenario(tenant);
        var projAcct1 = new ProjectionAccountEntity(
                scenario, linkedAccount1, null,
                new BigDecimal("0"), null, "taxable");
        var projAcct2 = new ProjectionAccountEntity(
                scenario, linkedAccount2, null,
                new BigDecimal("0"), null, "traditional");
        scenario.addAccount(projAcct1);
        scenario.addAccount(projAcct2);

        when(accountService.computeBalance(linkedAccount1, tenantId))
                .thenReturn(new BigDecimal("50000"));
        when(accountService.computeBalance(linkedAccount2, tenantId))
                .thenReturn(new BigDecimal("25000"));
        // Accounts are un-persisted (id == null for both) so distinguish calls by invocation
        // order rather than by id: resolveAccounts() visits account1 then account2, in the order
        // they were added to the scenario.
        when(classificationService.deriveAllocation(eq(tenantId), any()))
                .thenReturn(new AllocationResult(AssetAllocation.ALL_US, Set.of("ZZZZ", "WEIRDX")))
                .thenReturn(new AllocationResult(AssetAllocation.ALL_US, Set.of("ZZZZ")));
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var result = builder.buildWithMetadata(scenario, tenantId);

        assertThat(result.unclassifiedSymbols()).containsExactlyInAnyOrder("ZZZZ", "WEIRDX");
    }

    @Test
    void build_delegatesToWithMetadata_returnsSameInput() {
        var scenario = ScenarioMother.scenario(tenant);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());

        var viaBuild = builder.build(scenario, tenantId);
        var viaMetadata = builder.buildWithMetadata(scenario, tenantId).input();

        assertThat(viaBuild).isEqualTo(viaMetadata);
    }

    @Test
    void build_withMalformedGuardrailJson_returnsNullGuardrailSpending() {
        var scenario = ScenarioMother.scenario(tenant);

        var guardrailEntity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Guardrail", new BigDecimal("30000"));
        guardrailEntity.setYearlySpending("NOT VALID JSON");
        guardrailEntity.setPhases("[]");
        guardrailEntity.setScenarioHash("abc");
        scenario.setGuardrailProfile(guardrailEntity);

        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(List.of());
        when(guardrailSpendingProfileRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(Optional.of(guardrailEntity));

        var result = builder.build(scenario, tenantId);

        assertThat(result.guardrailSpending()).isNull();
    }

    // Stochastic mortality (sub-project B, T3): resolveMortalityTable is gated so a toggle-off
    // scenario never touches mortality_rates -- part of the byte-identical-to-sub-project-A anchor.

    @Test
    void resolveMortalityTable_stochasticMortalityTrue_loadsFromProvider() {
        var table = new MortalityTable(Map.of(70, 0.02), Map.of(70, 0.01));
        when(mortalityTableProvider.load()).thenReturn(table);
        var params = ScenarioParams.parseOrEmpty(new ObjectMapper(), "{\"stochastic_mortality\": true}");

        var resolved = builder.resolveMortalityTable(params);

        assertThat(resolved).isSameAs(table);
    }

    @Test
    void resolveMortalityTable_stochasticMortalityFalse_returnsNullWithoutTouchingProvider() {
        var params = ScenarioParams.parseOrEmpty(new ObjectMapper(), "{\"stochastic_mortality\": false}");

        var resolved = builder.resolveMortalityTable(params);

        assertThat(resolved).isNull();
        verifyNoInteractions(mortalityTableProvider);
    }

    @Test
    void resolveMortalityTable_stochasticMortalityAbsent_returnsNullWithoutTouchingProvider() {
        var resolved = builder.resolveMortalityTable(ScenarioParams.EMPTY);

        assertThat(resolved).isNull();
        verifyNoInteractions(mortalityTableProvider);
    }
}
