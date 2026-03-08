package com.wealthview.core.projection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.projection.dto.SpendingTierRequest;
import com.wealthview.core.projection.dto.SpendingTierResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpendingTierSerializationContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializeAndDeserialize_spendingTiers_roundTrips() throws Exception {
        var tiers = List.of(
                new SpendingTierRequest("Conservation", 54, 62, new BigDecimal("96000"), new BigDecimal("0")),
                new SpendingTierRequest("Go-Go", 62, 70, new BigDecimal("156000"), new BigDecimal("60000")),
                new SpendingTierRequest("Glide", 80, null, new BigDecimal("250000"), new BigDecimal("118000"))
        );

        var json = mapper.writeValueAsString(tiers);
        var deserialized = mapper.readValue(json, new TypeReference<List<SpendingTierResponse>>() {});

        assertThat(deserialized).hasSize(3);

        assertThat(deserialized.get(0).name()).isEqualTo("Conservation");
        assertThat(deserialized.get(0).startAge()).isEqualTo(54);
        assertThat(deserialized.get(0).endAge()).isEqualTo(62);
        assertThat(deserialized.get(0).essentialExpenses()).isEqualByComparingTo(new BigDecimal("96000"));
        assertThat(deserialized.get(0).discretionaryExpenses()).isEqualByComparingTo(new BigDecimal("0"));

        assertThat(deserialized.get(1).name()).isEqualTo("Go-Go");
        assertThat(deserialized.get(1).essentialExpenses()).isEqualByComparingTo(new BigDecimal("156000"));

        assertThat(deserialized.get(2).name()).isEqualTo("Glide");
        assertThat(deserialized.get(2).endAge()).isNull();
        assertThat(deserialized.get(2).essentialExpenses()).isEqualByComparingTo(new BigDecimal("250000"));
    }

    @Test
    void serializedFormat_usesCamelCaseFieldNames() throws Exception {
        var tier = new SpendingTierRequest("Active", 70, 80, new BigDecimal("200000"), new BigDecimal("74000"));

        var json = mapper.writeValueAsString(tier);

        assertThat(json).contains("\"startAge\"");
        assertThat(json).contains("\"endAge\"");
        assertThat(json).contains("\"essentialExpenses\"");
        assertThat(json).contains("\"discretionaryExpenses\"");
        assertThat(json).doesNotContain("start_age");
        assertThat(json).doesNotContain("end_age");
        assertThat(json).doesNotContain("essential_expenses");
        assertThat(json).doesNotContain("discretionary_expenses");
    }
}
