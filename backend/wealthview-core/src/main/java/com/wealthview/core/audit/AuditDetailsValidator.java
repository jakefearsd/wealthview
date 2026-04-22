package com.wealthview.core.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class AuditDetailsValidator {

    static final int MAX_SIZE_BYTES = 8 * 1024;
    static final int MAX_DEPTH = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditDetailsValidator() {
    }

    public static Map<String, Object> validate(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return details;
        }
        if (depth(details) > MAX_DEPTH) {
            return Map.of(
                    "_truncated", true,
                    "_reason", "exceeded_max_depth",
                    "_max_depth", MAX_DEPTH);
        }
        if (serializedSize(details) > MAX_SIZE_BYTES) {
            return Map.of(
                    "_truncated", true,
                    "_reason", "exceeded_max_size",
                    "_max_bytes", MAX_SIZE_BYTES);
        }
        return details;
    }

    private static int depth(Object value) {
        if (value instanceof Map<?, ?> map) {
            int max = 0;
            for (var v : map.values()) {
                max = Math.max(max, depth(v));
            }
            return 1 + max;
        }
        if (value instanceof List<?> list) {
            int max = 0;
            for (var v : list) {
                max = Math.max(max, depth(v));
            }
            return 1 + max;
        }
        return 0;
    }

    private static int serializedSize(Map<String, Object> details) {
        try {
            return MAPPER.writeValueAsBytes(details).length;
        } catch (JsonProcessingException e) {
            // If it can't serialize, treat it as oversize so the caller emits a marker
            // rather than attempting to persist an unserializable map.
            return Integer.MAX_VALUE;
        }
    }
}
