package com.wealthview.core.common;

import com.wealthview.core.exception.EntityNotFoundException;

import java.util.Optional;
import java.util.function.Supplier;

public final class Entities {

    private Entities() {
    }

    public static <T> T required(Optional<T> optional, String entityType) {
        return optional.orElseThrow(notFound(entityType));
    }

    public static <T> T required(Optional<T> optional, String entityType, Object id) {
        return optional.orElseThrow(notFound(entityType, id));
    }

    public static Supplier<EntityNotFoundException> notFound(String entityType) {
        return () -> new EntityNotFoundException(entityType + " not found");
    }

    public static Supplier<EntityNotFoundException> notFound(String entityType, Object id) {
        return () -> new EntityNotFoundException(entityType + " not found: " + id);
    }
}
