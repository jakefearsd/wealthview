package com.wealthview.core.projection.dto;

import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.entity.TenantEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpendingProfileResponseTest {

    @Test
    void from_withMalformedSpendingTiersJson_returnsEmptyTierList() {
        var entity = mock(SpendingProfileEntity.class);
        when(entity.getName()).thenReturn("Test Profile");
        when(entity.getEssentialExpenses()).thenReturn(new BigDecimal("30000"));
        when(entity.getDiscretionaryExpenses()).thenReturn(new BigDecimal("15000"));
        when(entity.getSpendingTiers()).thenReturn("[{broken");

        var response = SpendingProfileResponse.from(entity);

        assertThat(response.spendingTiers()).isEmpty();
        assertThat(response.name()).isEqualTo("Test Profile");
        assertThat(response.essentialExpenses()).isEqualByComparingTo(new BigDecimal("30000"));
        assertThat(response.discretionaryExpenses()).isEqualByComparingTo(new BigDecimal("15000"));
    }

    @Test
    void from_withValidSpendingTiersJson_parsesTiers() {
        var entity = mock(SpendingProfileEntity.class);
        when(entity.getName()).thenReturn("Test Profile");
        when(entity.getEssentialExpenses()).thenReturn(new BigDecimal("30000"));
        when(entity.getDiscretionaryExpenses()).thenReturn(new BigDecimal("15000"));
        when(entity.getSpendingTiers()).thenReturn("""
                [{"name":"Active","startAge":65,"endAge":75,"essentialExpenses":40000,"discretionaryExpenses":20000}]
                """);

        var response = SpendingProfileResponse.from(entity);

        assertThat(response.spendingTiers()).hasSize(1);
        assertThat(response.spendingTiers().getFirst().name()).isEqualTo("Active");
    }

    @Test
    void from_withNullSpendingTiers_returnsEmptyTierList() {
        var entity = mock(SpendingProfileEntity.class);
        when(entity.getName()).thenReturn("Test Profile");
        when(entity.getEssentialExpenses()).thenReturn(BigDecimal.ZERO);
        when(entity.getDiscretionaryExpenses()).thenReturn(BigDecimal.ZERO);
        when(entity.getSpendingTiers()).thenReturn(null);

        var response = SpendingProfileResponse.from(entity);

        assertThat(response.spendingTiers()).isEmpty();
    }
}
