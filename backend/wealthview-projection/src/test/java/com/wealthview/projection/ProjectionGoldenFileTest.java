package com.wealthview.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static org.mockito.Mockito.mock;

class ProjectionGoldenFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @ParameterizedTest(name = "golden file: {0}")
    @ValueSource(strings = {
            "simple-preretirement",
            "tiered-spending-with-income",
            "multi-pool-roth-conversion"
    })
    void run_matchesGoldenFile(String scenario) throws Exception {
        var inputJson = readResource("golden/" + scenario + "-input.json");
        var input = parseInput(inputJson);

        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);

        var engine = new DeterministicProjectionEngine(
                new FederalTaxCalculator(taxBracketRepo, deductionRepo));
        var result = engine.run(input);

        var actualJson = MAPPER.writeValueAsString(result);

        var goldenPath = goldenFilePath(scenario);
        if (Boolean.getBoolean("update.golden") || !Files.exists(goldenPath)) {
            Files.createDirectories(goldenPath.getParent());
            Files.writeString(goldenPath, actualJson);
            System.out.println("Updated golden file: " + goldenPath);
            return;
        }

        var expectedJson = Files.readString(goldenPath);
        JSONAssert.assertEquals(expectedJson, actualJson, JSONCompareMode.STRICT);
    }

    private ProjectionInput parseInput(String json) throws Exception {
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
            var sourceList = new java.util.ArrayList<ProjectionIncomeSourceInput>();
            for (var item : node.get("incomeSources")) {
                sourceList.add(new ProjectionIncomeSourceInput(
                        UUID.fromString(item.get("id").asText()),
                        item.get("name").asText(),
                        item.get("incomeType").asText(),
                        new BigDecimal(item.get("annualAmount").asText()),
                        item.get("startAge").asInt(),
                        item.has("endAge") && !item.get("endAge").isNull() ? item.get("endAge").asInt() : null,
                        new BigDecimal(item.get("inflationRate").asText()),
                        item.get("oneTime").asBoolean(),
                        item.get("taxTreatment").asText(),
                        null, null, null, null, null
                ));
            }
            incomeSources = sourceList;
        }

        return new ProjectionInput(UUID.nameUUIDFromBytes(scenarioName.getBytes()), scenarioName,
                retirementDate, endAge, inflationRate, paramsJson, accounts, spendingProfile,
                referenceYear, incomeSources);
    }

    private List<ProjectionAccountInput> parseAccounts(JsonNode accountsNode) {
        var accounts = new java.util.ArrayList<ProjectionAccountInput>();
        for (var acctNode : accountsNode) {
            accounts.add(new HypotheticalAccountInput(
                    new BigDecimal(acctNode.get("initialBalance").asText()),
                    new BigDecimal(acctNode.get("annualContribution").asText()),
                    new BigDecimal(acctNode.get("expectedReturn").asText()),
                    acctNode.get("accountType").asText()
            ));
        }
        return accounts;
    }

    private String readResource(String path) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return new String(is.readAllBytes());
        }
    }

    private Path goldenFilePath(String scenario) {
        return Path.of("src/test/resources/golden/" + scenario + ".json");
    }
}
