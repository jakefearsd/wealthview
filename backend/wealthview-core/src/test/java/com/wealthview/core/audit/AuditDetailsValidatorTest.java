package com.wealthview.core.audit;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditDetailsValidatorTest {

    @Test
    void validate_smallFlatMap_returnsUnchanged() {
        var input = Map.<String, Object>of("symbol", "AAPL", "quantity", 10);

        var result = AuditDetailsValidator.validate(input);

        assertThat(result).isSameAs(input);
    }

    @Test
    void validate_nullDetails_returnsNull() {
        assertThat(AuditDetailsValidator.validate(null)).isNull();
    }

    @Test
    void validate_depthAtLimit_returnsUnchanged() {
        // 3 levels: outer -> middle -> leaf value. Depth of the top Map is 3.
        var input = Map.<String, Object>of(
                "level1", Map.of("level2", Map.of("level3", "value")));

        var result = AuditDetailsValidator.validate(input);

        assertThat(result).isSameAs(input);
    }

    @Test
    void validate_depthExceedsLimit_returnsTruncationMarker() {
        var input = Map.<String, Object>of(
                "a", Map.of("b", Map.of("c", Map.of("d", "too-deep"))));

        var result = AuditDetailsValidator.validate(input);

        assertThat(result)
                .containsEntry("_truncated", true)
                .containsEntry("_reason", "exceeded_max_depth");
    }

    @Test
    void validate_oversizePayload_returnsTruncationMarker() {
        var big = new HashMap<String, Object>();
        for (int i = 0; i < 500; i++) {
            big.put("key_" + i, "x".repeat(64));
        }

        var result = AuditDetailsValidator.validate(big);

        assertThat(result)
                .containsEntry("_truncated", true)
                .containsEntry("_reason", "exceeded_max_size");
    }

    @Test
    void validate_listNestingCountsTowardDepth() {
        // Map -> List -> Map -> List -> value is 4 levels.
        var input = Map.<String, Object>of(
                "items", List.of(Map.of("children", List.of("x"))));

        var result = AuditDetailsValidator.validate(input);

        assertThat(result).containsEntry("_truncated", true);
    }
}
