package com.wealthview.persistence.repository;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailProfileConversionSettingsIntegrationTest extends AbstractIntegrationTest {

    private static final String V081 = "db/migration/V081__guardrail_profile_conversion_settings.sql";

    @Autowired
    private GuardrailSpendingProfileRepository repository;

    @Autowired
    private TestEntityManager em;

    private TenantEntity tenant;
    private ProjectionScenarioEntity scenario;

    @BeforeEach
    void setUp() {
        tenant = em.persistAndFlush(new TenantEntity("Tenant A"));
        scenario = newScenario("Plan");
    }

    private ProjectionScenarioEntity newScenario(String name) {
        return em.persistAndFlush(new ProjectionScenarioEntity(
                tenant, name, LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{\"birth_year\":1968}"));
    }

    private GuardrailSpendingProfileEntity newProfile(ProjectionScenarioEntity forScenario) {
        var entity = new GuardrailSpendingProfileEntity(tenant, forScenario, "Plan", new BigDecimal("30000"));
        entity.setScenarioHash("hash");
        return entity;
    }

    @Test
    void conversionSettings_roundTrip() {
        var entity = newProfile(scenario);
        entity.setOptimizeConversions(true);
        entity.setDynamicSequencingBracketRate(new BigDecimal("0.1200"));

        var saved = repository.saveAndFlush(entity);
        em.clear();
        var reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.isOptimizeConversions()).isTrue();
        assertThat(reloaded.getDynamicSequencingBracketRate()).isEqualByComparingTo("0.1200");
    }

    @Test
    void conversionSettings_defaultToOffAndNull_onLegacyInsert() {
        var saved = repository.saveAndFlush(newProfile(scenario));
        em.clear();
        var reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.isOptimizeConversions()).isFalse();
        assertThat(reloaded.getDynamicSequencingBracketRate()).isNull();
    }

    @Test
    void v081Backfill_rowWithConversionSchedule_becomesOptimizeConversionsTrue() throws IOException {
        var withSchedule = newProfile(scenario);
        withSchedule.setConversionSchedule("{\"years\":[]}");
        var withoutSchedule = newProfile(newScenario("Plan 2"));
        withoutSchedule.setScenarioHash("hash-2");
        var a = repository.saveAndFlush(withSchedule);
        var b = repository.saveAndFlush(withoutSchedule);

        em.getEntityManager().createNativeQuery(backfillStatement()).executeUpdate();
        em.clear();

        assertThat(repository.findById(a.getId()).orElseThrow().isOptimizeConversions()).isTrue();
        assertThat(repository.findById(b.getId()).orElseThrow().isOptimizeConversions()).isFalse();
    }

    /** The UPDATE statement exactly as committed in V081, so this test pins the shipped SQL. */
    private static String backfillStatement() throws IOException {
        try (InputStream in = GuardrailProfileConversionSettingsIntegrationTest.class.getClassLoader()
                .getResourceAsStream(V081)) {
            assertThat(in).as(V081).isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("UPDATE");
            return sql.substring(start, sql.indexOf(';', start));
        }
    }
}
