package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BondTentConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static BondTentConfig tent() {
        return new BondTentConfig(
                new BigDecimal("2.5"), 3,
                List.of(new BondTentMix("Balanced", new BondMaturityValues(
                        new BigDecimal("0.2"), new BigDecimal("0.3"), new BigDecimal("0.5"), BigDecimal.ZERO))),
                new BondMaturityValues(new BigDecimal("0.0394"), new BigDecimal("0.0465"),
                        new BigDecimal("0.048"), new BigDecimal("0.0539")));
    }

    private static ScenarioParams paramsWith(BondTentConfig tent) {
        return new ScenarioParams(
                1968, new BigDecimal("0.04"), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, tent);
    }

    @Test
    void toJson_tent_writesSnakeCaseAndMaturityKeys() {
        var json = paramsWith(tent()).toJson(mapper);

        assertThat(json).contains("\"bond_tent\":{\"coverage_years\":2.5,\"build_up_start_years\":3,"
                + "\"mixes\":[{\"name\":\"Balanced\",\"shares\":{\"ultrashort\":0.2,\"short\":0.3,"
                + "\"intermediate\":0.5,\"long\":0}}],\"yields\":{\"ultrashort\":0.0394,"
                + "\"short\":0.0465,\"intermediate\":0.048,\"long\":0.0539}}");
    }

    @Test
    void parseOrEmpty_tentJson_roundTrips() {
        var params = paramsWith(tent());

        var parsed = ScenarioParams.parseOrEmpty(mapper, params.toJson(mapper));

        assertThat(parsed).isEqualTo(params);
    }

    @Test
    void toJson_noTent_omitsBondTentKey() {
        var json = paramsWith(null).toJson(mapper);

        assertThat(json).contains("birth_year").doesNotContain("bond_tent");
    }

    @Test
    void get_eachMaturity_returnsItsValue() {
        var values = new BondMaturityValues(
                new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.03"), new BigDecimal("0.04"));

        assertThat(values.get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("0.01");
        assertThat(values.get(BondMaturity.SHORT)).isEqualByComparingTo("0.02");
        assertThat(values.get(BondMaturity.INTERMEDIATE)).isEqualByComparingTo("0.03");
        assertThat(values.get(BondMaturity.LONG)).isEqualByComparingTo("0.04");
    }

    @Test
    void sum_withNullComponent_treatsNullAsZero() {
        var values = new BondMaturityValues(new BigDecimal("0.5"), null, new BigDecimal("0.5"), null);

        assertThat(values.sum()).isEqualByComparingTo("1");
    }

    @Test
    void planMix_multipleMixes_returnsFirst() {
        var second = new BondTentMix("Other", new BondMaturityValues(BigDecimal.ONE, null, null, null));
        var config = new BondTentConfig(BigDecimal.ONE, 0, List.of(tent().mixes().getFirst(), second), null);

        assertThat(config.planMix().name()).isEqualTo("Balanced");
    }

    @Test
    void planMix_nullMixes_throwsIllegalState() {
        var config = new BondTentConfig(BigDecimal.ONE, 0, null, null);

        assertThatThrownBy(config::planMix).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void planMix_emptyMixes_throwsIllegalState() {
        var config = new BondTentConfig(BigDecimal.ONE, 0, List.of(), null);

        assertThatThrownBy(config::planMix).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toJson_convertedMarker_writesSnakeCaseKey() {
        var base = tent();
        var marked = new BondTentConfig(base.coverageYears(), base.buildUpStartYears(), base.mixes(),
                base.yields(), true);

        var json = paramsWith(marked).toJson(mapper);

        assertThat(json).contains("\"converted_from_cash_reserve\":true");
        assertThat(ScenarioParams.parseOrEmpty(mapper, json).bondTent().convertedFromCashReserve()).isTrue();
    }

    @Test
    void toJson_noMarker_omitsTheKey() {
        var json = paramsWith(tent()).toJson(mapper);

        assertThat(json).doesNotContain("converted_from_cash_reserve");
    }
}
