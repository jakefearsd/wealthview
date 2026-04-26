package com.wealthview.core.common;

import com.wealthview.core.exception.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntitiesTest {

    @Test
    void required_withPresentValue_returnsValue() {
        var optional = Optional.of("hello");

        var result = Entities.required(optional, "Account");

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void required_withEmptyOptional_throwsEntityNotFoundException() {
        Optional<String> optional = Optional.empty();

        assertThatThrownBy(() -> Entities.required(optional, "Account"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Account not found");
    }

    @Test
    void required_withEmptyOptionalAndId_includesIdInMessage() {
        Optional<String> optional = Optional.empty();
        var id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> Entities.required(optional, "Scenario", id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Scenario not found: 00000000-0000-0000-0000-000000000001");
    }

    @Test
    void required_withPresentValueAndId_returnsValue() {
        var optional = Optional.of(42);

        var result = Entities.required(optional, "Holding", UUID.randomUUID());

        assertThat(result).isEqualTo(42);
    }

    @Test
    void notFound_byType_producesSupplierWithExpectedMessage() {
        var supplier = Entities.notFound("Property");

        var ex = supplier.get();

        assertThat(ex).isInstanceOf(EntityNotFoundException.class);
        assertThat(ex.getMessage()).isEqualTo("Property not found");
    }

    @Test
    void notFound_byTypeAndId_producesSupplierWithIdInMessage() {
        var id = UUID.fromString("00000000-0000-0000-0000-000000000002");

        var supplier = Entities.notFound("Property", id);

        var ex = supplier.get();
        assertThat(ex.getMessage()).isEqualTo("Property not found: 00000000-0000-0000-0000-000000000002");
    }
}
