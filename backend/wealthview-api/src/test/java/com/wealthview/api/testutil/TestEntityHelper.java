package com.wealthview.api.testutil;

import java.lang.reflect.Field;
import java.util.UUID;

public final class TestEntityHelper {

    private TestEntityHelper() {
    }

    /**
     * Sets the {@code id} field on a JPA entity via reflection, for controller tests that
     * need a stable id on an entity built through its non-persisting constructor.
     *
     * <p>Walks up the class hierarchy because {@code id} is declared on a
     * {@code @MappedSuperclass} ({@code UuidAuditable} / {@code UuidCreatedAtEntity}) for
     * every entity in the UUID-primary-key ladder, not on the concrete entity class itself.
     */
    public static void setId(Object entity, UUID id) {
        try {
            Field field = findIdField(entity.getClass());
            field.setAccessible(true);
            field.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to set id on entity", e);
        }
    }

    private static Field findIdField(Class<?> type) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField("id");
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        throw new NoSuchFieldException("id");
    }
}
