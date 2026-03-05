package com.wealthview.core.testutil;

import java.lang.reflect.Field;
import java.util.UUID;

public final class TestEntityHelper {

    private TestEntityHelper() {
    }

    public static void setId(Object entity, UUID id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to set id on entity", e);
        }
    }
}
