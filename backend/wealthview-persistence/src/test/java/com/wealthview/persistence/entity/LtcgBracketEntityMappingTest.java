package com.wealthview.persistence.entity;

import java.util.Arrays;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code rate} column mapped by {@link AbstractTaxBracketEntity} is
 * {@code numeric(5,4)}, which matches {@code tax_brackets} (V013) and
 * {@code state_tax_brackets} (V043) but NOT {@code ltcg_brackets}, whose rate is
 * {@code numeric(6,4)} (V071). Hibernate's {@code ddl-auto: validate} does not compare
 * precision, so the drift is invisible at boot — this test pins it instead.
 */
class LtcgBracketEntityMappingTest {

    @Test
    void ltcgBracketEntity_overridesRatePrecision_toMatchNumeric6Comma4() {
        var override = rateOverrideOn(LtcgBracketEntity.class);

        assertThat(override).isNotNull();
        assertThat(override.column().precision()).isEqualTo(6);
        assertThat(override.column().scale()).isEqualTo(4);
        assertThat(override.column().nullable()).isFalse();
    }

    @Test
    void siblingBracketEntities_inheritTheAbstractRateMapping_ofNumeric5Comma4() throws NoSuchFieldException {
        var inherited = AbstractTaxBracketEntity.class.getDeclaredField("rate").getAnnotation(Column.class);

        assertThat(inherited.precision()).isEqualTo(5);
        assertThat(inherited.scale()).isEqualTo(4);
        assertThat(rateOverrideOn(TaxBracketEntity.class)).isNull();
        assertThat(rateOverrideOn(StateTaxBracketEntity.class)).isNull();
    }

    private AttributeOverride rateOverrideOn(Class<?> entityType) {
        return Arrays.stream(entityType.getAnnotationsByType(AttributeOverride.class))
                .filter(override -> "rate".equals(override.name()))
                .findFirst()
                .orElse(null);
    }
}
