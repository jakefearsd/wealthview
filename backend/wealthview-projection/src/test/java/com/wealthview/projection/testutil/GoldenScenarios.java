package com.wealthview.projection.testutil;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.LifeExpectancy;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.IrmaaSurchargeCalculator;
import com.wealthview.persistence.repository.IrmaaTierRepository;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.mockito.Mockito.mock;

/**
 * Shared loader for the six deterministic-engine golden scenarios
 * ({@code src/test/resources/golden/<name>-input.json}), used by {@code ProjectionGoldenFileTest}
 * (byte-pinned output) and {@code TaxSpaceReconciliationTest} (tax-space vs charged tax).
 */
public final class GoldenScenarios {

    public static final List<String> NAMES = List.of(
            "simple-preretirement",
            "tiered-spending-with-income",
            "multi-pool-roth-conversion",
            "ss-rmd-retiree",
            "accumulation-gap-pension",
            "household-survivor");

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** The three real calculators the golden engine is wired with, backed by 2025 fixture stubs. */
    public record Calculators(FederalTaxCalculator federal, CapitalGainsTaxCalculator capitalGains,
                              IrmaaSurchargeCalculator irmaa) {
    }

    private GoldenScenarios() {
    }

    public static Calculators calculators() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        stubMfj2025(taxBracketRepo, deductionRepo);
        var ltcgBracketRepo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(ltcgBracketRepo);
        stubMfj2025Ltcg(ltcgBracketRepo);
        var irmaaTierRepo = mock(IrmaaTierRepository.class);
        stubSingle2025Irmaa(irmaaTierRepo);
        stubMfj2025Irmaa(irmaaTierRepo);
        return new Calculators(new FederalTaxCalculator(taxBracketRepo, deductionRepo),
                new CapitalGainsTaxCalculator(ltcgBracketRepo), new IrmaaSurchargeCalculator(irmaaTierRepo));
    }

    public static ProjectionInput loadInput(String scenario) throws IOException {
        return parseInput(readResource("golden/" + scenario + "-input.json"));
    }

    private static ProjectionInput parseInput(String json) throws IOException {
        var node = MAPPER.readTree(json);

        var scenarioName = node.get("scenarioName").asText();
        var retirementDate = LocalDate.parse(node.get("retirementDate").asText());
        var endAge = node.get("endAge").asInt();
        var inflationRate = new BigDecimal(node.get("inflationRate").asText());
        var paramsJson = node.get("paramsJson").asText();
        var referenceYear = node.has("referenceYear") ? node.get("referenceYear").asInt() : null;

        List<ProjectionAccountInput> accounts = node.get("accounts").findValues("initialBalance").isEmpty()
                ? List.of()
                : parseAccounts(node.get("accounts"));

        SpendingProfileInput spendingProfile = null;
        if (node.has("spendingProfile") && !node.get("spendingProfile").isNull()) {
            var sp = node.get("spendingProfile");
            spendingProfile = new SpendingProfileInput(
                    new BigDecimal(sp.get("essentialExpenses").asText()),
                    new BigDecimal(sp.get("discretionaryExpenses").asText()),
                    sp.has("spendingTiers") ? sp.get("spendingTiers").asText() : null
            );
        }

        List<ProjectionIncomeSourceInput> incomeSources = List.of();
        if (node.has("incomeSources") && node.get("incomeSources").isArray()) {
            var sourceList = new ArrayList<ProjectionIncomeSourceInput>();
            for (var item : node.get("incomeSources")) {
                // Household task 10: optional "owner"/"survivorPercent" default to the same
                // "primary"/1.0 the production back-compat constructor uses, so the five
                // pre-household input files parse to byte-identical inputs.
                sourceList.add(new ProjectionIncomeSourceInput(
                        UUID.fromString(item.get("id").asText()),
                        item.get("name").asText(),
                        IncomeSourceType.fromString(item.get("incomeType").asText()),
                        new BigDecimal(item.get("annualAmount").asText()),
                        item.get("startAge").asInt(),
                        item.has("endAge") && !item.get("endAge").isNull() ? item.get("endAge").asInt() : null,
                        new BigDecimal(item.get("inflationRate").asText()),
                        item.get("oneTime").asBoolean(),
                        item.get("taxTreatment").asText(),
                        null, null, null, null, null, null,
                        item.has("owner") ? item.get("owner").asText() : "primary",
                        item.has("survivorPercent")
                                ? new BigDecimal(item.get("survivorPercent").asText()) : BigDecimal.ONE
                ));
            }
            incomeSources = sourceList;
        }

        var household = resolveHousehold(paramsJson, endAge);
        return new ProjectionInput(UUID.nameUUIDFromBytes(scenarioName.getBytes()), scenarioName,
                retirementDate, endAge, inflationRate, paramsJson, accounts, spendingProfile,
                referenceYear, incomeSources, null, List.of(), household);
    }

    /**
     * Household task 10: mirrors {@code ProjectionInputBuilder#resolveHousehold} — a scenario whose
     * params carry no {@code spouse_birth_year} gets a {@code null} household (the engines treat
     * that identically to a degenerate single-person context, keeping the five pre-household golden
     * inputs byte-identical); a household scenario resolves death ages from explicit params or the
     * SSA planning default, with the horizon end anchored to the primary's birth year + end age.
     */
    private static HouseholdContext resolveHousehold(String paramsJson, int endAge) {
        var params = ScenarioParams.parseOrEmpty(MAPPER, paramsJson);
        if (params.spouseBirthYear() == null) {
            return null;
        }
        int primaryBirthYear = params.birthYear();
        int primaryDeathAge = params.primaryDeathAge() != null
                ? params.primaryDeathAge() : LifeExpectancy.defaultDeathAge(primaryBirthYear);
        int spouseDeathAge = params.spouseDeathAge() != null
                ? params.spouseDeathAge() : LifeExpectancy.defaultDeathAge(params.spouseBirthYear());
        return HouseholdContext.of(primaryBirthYear, primaryDeathAge,
                params.spouseBirthYear(), spouseDeathAge, primaryBirthYear + endAge);
    }

    private static List<ProjectionAccountInput> parseAccounts(JsonNode accountsNode) {
        var accounts = new ArrayList<ProjectionAccountInput>();
        for (var acctNode : accountsNode) {
            // Household task 10: optional "costBasis"/"owner" thread the taxable basis (step-up
            // fixture) and the account owner (per-owner pools). Absent, the legacy 4-arg
            // constructor path is used verbatim (costBasis = initialBalance, owner = "primary"),
            // keeping the five pre-household input files byte-identical.
            if (acctNode.has("costBasis") || acctNode.has("owner")) {
                var initialBalance = new BigDecimal(acctNode.get("initialBalance").asText());
                accounts.add(new HypotheticalAccountInput(
                        initialBalance,
                        new BigDecimal(acctNode.get("annualContribution").asText()),
                        AssetAllocation.ALL_US,
                        Optional.of(new BigDecimal(acctNode.get("expectedReturn").asText())),
                        acctNode.has("costBasis")
                                ? new BigDecimal(acctNode.get("costBasis").asText()) : initialBalance,
                        acctNode.get("accountType").asText(),
                        acctNode.has("owner") ? acctNode.get("owner").asText() : "primary"
                ));
            } else {
                accounts.add(new HypotheticalAccountInput(
                        new BigDecimal(acctNode.get("initialBalance").asText()),
                        new BigDecimal(acctNode.get("annualContribution").asText()),
                        new BigDecimal(acctNode.get("expectedReturn").asText()),
                        acctNode.get("accountType").asText()
                ));
            }
        }
        return accounts;
    }

    private static String readResource(String path) throws IOException {
        try (var is = GoldenScenarios.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return new String(is.readAllBytes());
        }
    }
}
