package com.wealthview.core.tenant;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.TenantRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantLookupTest {

    @Mock
    private TenantRepository tenantRepository;

    @Test
    void requireTenant_existingTenant_returnsEntity() {
        var tenantId = UUID.randomUUID();
        var tenant = new TenantEntity("Test Family");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var result = new TenantLookup(tenantRepository).requireTenant(tenantId);

        assertThat(result).isSameAs(tenant);
    }

    @Test
    void requireTenant_unknownTenant_throwsInvalidSession() {
        var tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new TenantLookup(tenantRepository).requireTenant(tenantId))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("Session expired");
    }
}
