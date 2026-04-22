package com.wealthview.core.audit;

import com.wealthview.persistence.entity.AuditLogEntity;
import com.wealthview.persistence.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditLogRepository auditLogRepository;

    public AuditEventListener(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAuditEvent(AuditEvent event) {
        var safeDetails = AuditDetailsValidator.validate(event.details());
        if (safeDetails != event.details()) {
            log.warn("Audit details for action {} on {} {} truncated (reason={})",
                    event.action(), event.entityType(), event.entityId(),
                    safeDetails.get("_reason"));
        }
        var entity = new AuditLogEntity(
                event.tenantId(), event.userId(), event.action(),
                event.entityType(), event.entityId(), safeDetails);
        auditLogRepository.save(entity);
        log.debug("Audit log: {} {} {} by user {}",
                event.action(), event.entityType(), event.entityId(), event.userId());
    }
}
