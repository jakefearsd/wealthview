package com.wealthview.core.tenant;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.tenant.dto.TenantDetailResponse;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenantRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TenantService(TenantRepository tenantRepository,
                         InviteCodeRepository inviteCodeRepository,
                         UserRepository userRepository,
                         AccountRepository accountRepository,
                         ApplicationEventPublisher eventPublisher) {
        this.tenantRepository = tenantRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TenantEntity createTenant(String name) {
        var tenant = tenantRepository.save(new TenantEntity(name));
        log.info("Tenant created: {} ({})", name, tenant.getId());
        eventPublisher.publishEvent(new AuditEvent(tenant.getId(), null, "CREATE", "tenant",
                tenant.getId(), Map.of("name", name)));
        return tenant;
    }

    @Transactional(readOnly = true)
    public List<TenantEntity> getAllTenants() {
        return tenantRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<TenantDetailResponse> getAllTenantDetails() {
        return tenantRepository.findAll().stream()
                .map(this::toDetailResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantDetailResponse getTenantDetail(UUID tenantId) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found"));
        return toDetailResponse(tenant);
    }

    @Transactional
    public void setTenantActive(UUID tenantId, boolean active) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found"));
        tenant.setActive(active);
        tenantRepository.save(tenant);
        log.info("Tenant {} set active={}", tenantId, active);
        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "SET_ACTIVE", "tenant",
                tenantId, Map.of("active", active)));
    }

    private TenantDetailResponse toDetailResponse(TenantEntity tenant) {
        var userCount = userRepository.countByTenant_Id(tenant.getId());
        var accountCount = accountRepository.countByTenant_Id(tenant.getId());
        return new TenantDetailResponse(
                tenant.getId(), tenant.getName(), tenant.isActive(),
                userCount, accountCount, tenant.getCreatedAt());
    }

    @Transactional
    public InviteCodeEntity generateInviteCode(UUID tenantId, UUID createdByUserId) {
        return generateInviteCode(tenantId, createdByUserId, 7);
    }

    @Transactional
    public InviteCodeEntity generateInviteCode(UUID tenantId, UUID createdByUserId, int expiryDays) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        var creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + createdByUserId));

        var code = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.US);
        var invite = new InviteCodeEntity(tenant, code, creator,
                OffsetDateTime.now().plusDays(expiryDays));
        invite = inviteCodeRepository.save(invite);

        log.info("Invite code generated for tenant {} with {} day expiry: {}", tenantId, expiryDays, code);
        return invite;
    }

    @Transactional(readOnly = true)
    public List<InviteCodeEntity> getInviteCodes(UUID tenantId) {
        return inviteCodeRepository.findByTenant_Id(tenantId);
    }

    @Transactional
    public void revokeInviteCode(UUID tenantId, UUID codeId) {
        var invite = inviteCodeRepository.findById(codeId)
                .orElseThrow(() -> new EntityNotFoundException("Invite code not found"));

        if (!invite.getTenant().getId().equals(tenantId)) {
            throw new EntityNotFoundException("Invite code not found");
        }

        invite.setRevoked(true);
        invite.setUpdatedAt(OffsetDateTime.now());
        inviteCodeRepository.save(invite);
        log.info("Invite code {} revoked for tenant {}", codeId, tenantId);
    }

    @Transactional
    public int deleteUsedCodes(UUID tenantId) {
        int count = inviteCodeRepository.deleteByTenant_IdAndConsumedByIsNotNull(tenantId);
        log.info("Deleted {} used invite codes for tenant {}", count, tenantId);
        return count;
    }
}
