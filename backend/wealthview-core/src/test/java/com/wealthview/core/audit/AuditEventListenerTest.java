package com.wealthview.core.audit;

import com.wealthview.persistence.entity.AuditLogEntity;
import com.wealthview.persistence.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditEventListener auditEventListener;

    @Test
    void handleAuditEvent_persistsAuditLog() {
        var tenantId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var details = Map.<String, Object>of("symbol", "AAPL");
        var event = new AuditEvent(tenantId, userId, "CREATE", "transaction", entityId, details);

        auditEventListener.handleAuditEvent(event);

        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());

        var saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAction()).isEqualTo("CREATE");
        assertThat(saved.getEntityType()).isEqualTo("transaction");
        assertThat(saved.getEntityId()).isEqualTo(entityId);
        assertThat(saved.getDetails()).containsEntry("symbol", "AAPL");
    }
}
