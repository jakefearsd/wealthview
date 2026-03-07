package com.wealthview.core.tenant;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenantRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final UserRepository userRepository;

    public TenantService(TenantRepository tenantRepository,
                         InviteCodeRepository inviteCodeRepository,
                         UserRepository userRepository) {
        this.tenantRepository = tenantRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TenantEntity createTenant(String name) {
        var tenant = tenantRepository.save(new TenantEntity(name));
        log.info("Tenant created: {} ({})", name, tenant.getId());
        return tenant;
    }

    @Transactional(readOnly = true)
    public List<TenantEntity> getAllTenants() {
        return tenantRepository.findAll();
    }

    @Transactional
    public InviteCodeEntity generateInviteCode(UUID tenantId, UUID createdByUserId) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        var creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + createdByUserId));

        var code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var invite = new InviteCodeEntity(tenant, code, creator,
                OffsetDateTime.now().plusDays(7));
        invite = inviteCodeRepository.save(invite);

        log.info("Invite code generated for tenant {}: {}", tenantId, code);
        return invite;
    }

    @Transactional(readOnly = true)
    public List<InviteCodeEntity> getInviteCodes(UUID tenantId) {
        return inviteCodeRepository.findByTenant_Id(tenantId);
    }
}
