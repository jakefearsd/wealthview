package com.wealthview.core.audit;

import com.wealthview.persistence.entity.AuditLogEntity;
import com.wealthview.persistence.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void getAuditLogs_noFilter_returnsAll() {
        var tenantId = UUID.randomUUID();
        var pageable = PageRequest.of(0, 50);
        var entity = new AuditLogEntity(tenantId, UUID.randomUUID(), "CREATE", "account", UUID.randomUUID(), Map.of());
        when(auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable))
                .thenReturn(new PageImpl<>(List.of(entity)));

        var result = auditLogService.getAuditLogs(tenantId, null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).action()).isEqualTo("CREATE");
    }

    @Test
    void getAuditLogs_withEntityTypeFilter_filtersResults() {
        var tenantId = UUID.randomUUID();
        var pageable = PageRequest.of(0, 50);
        var entity = new AuditLogEntity(tenantId, UUID.randomUUID(), "DELETE", "transaction", UUID.randomUUID(), Map.of());
        when(auditLogRepository.findByTenantIdAndEntityTypeOrderByCreatedAtDesc(tenantId, "transaction", pageable))
                .thenReturn(new PageImpl<>(List.of(entity)));

        var result = auditLogService.getAuditLogs(tenantId, "transaction", pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).entityType()).isEqualTo("transaction");
    }
}
