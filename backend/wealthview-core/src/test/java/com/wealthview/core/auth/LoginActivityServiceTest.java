package com.wealthview.core.auth;

import com.wealthview.persistence.entity.LoginActivityEntity;
import com.wealthview.persistence.repository.LoginActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginActivityServiceTest {

    @Mock
    private LoginActivityRepository loginActivityRepository;

    @InjectMocks
    private LoginActivityService service;

    @Test
    void record_savesLoginActivity() {
        var tenantId = UUID.randomUUID();
        when(loginActivityRepository.save(any(LoginActivityEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.record("user@test.com", tenantId, true, "192.168.1.1");

        var captor = ArgumentCaptor.forClass(LoginActivityEntity.class);
        verify(loginActivityRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getUserEmail()).isEqualTo("user@test.com");
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getIpAddress()).isEqualTo("192.168.1.1");
    }

    @Test
    void record_handlesNullTenantId() {
        when(loginActivityRepository.save(any(LoginActivityEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.record("unknown@test.com", null, false, "10.0.0.1");

        var captor = ArgumentCaptor.forClass(LoginActivityEntity.class);
        verify(loginActivityRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isNull();
        assertThat(captor.getValue().isSuccess()).isFalse();
    }

    @Test
    void getRecent_returnsLimitedResults() {
        var tenantId = UUID.randomUUID();
        var entities = List.of(
                new LoginActivityEntity("a@test.com", tenantId, true, "1.1.1.1"),
                new LoginActivityEntity("b@test.com", tenantId, false, "2.2.2.2"),
                new LoginActivityEntity("c@test.com", tenantId, true, "3.3.3.3")
        );
        when(loginActivityRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(entities);

        var result = service.getRecent(2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).userEmail()).isEqualTo("a@test.com");
        assertThat(result.get(1).userEmail()).isEqualTo("b@test.com");
    }

    @Test
    void getRecent_returnsAllWhenLimitExceedsCount() {
        var entity = new LoginActivityEntity("a@test.com", UUID.randomUUID(), true, "1.1.1.1");
        when(loginActivityRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(entity));

        var result = service.getRecent(50);

        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentForTenant_returnsTenantScopedResults() {
        var tenantId = UUID.randomUUID();
        var entities = List.of(
                new LoginActivityEntity("a@test.com", tenantId, true, "1.1.1.1"),
                new LoginActivityEntity("b@test.com", tenantId, true, "2.2.2.2")
        );
        when(loginActivityRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(entities);

        var result = service.getRecentForTenant(tenantId, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).tenantId()).isEqualTo(tenantId);
    }

    @Test
    void getRecentForTenant_limitsResults() {
        var tenantId = UUID.randomUUID();
        var entities = List.of(
                new LoginActivityEntity("a@test.com", tenantId, true, "1.1.1.1"),
                new LoginActivityEntity("b@test.com", tenantId, false, "2.2.2.2"),
                new LoginActivityEntity("c@test.com", tenantId, true, "3.3.3.3")
        );
        when(loginActivityRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(entities);

        var result = service.getRecentForTenant(tenantId, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userEmail()).isEqualTo("a@test.com");
    }
}
