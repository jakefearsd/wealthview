package com.wealthview.core.tenant;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.auth.CommonPasswordChecker;
import com.wealthview.core.auth.TenantContext;
import com.wealthview.core.common.Entities;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);
    private static final Set<String> VALID_ROLES = Set.of("member", "admin");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CommonPasswordChecker commonPasswordChecker;
    private final ApplicationEventPublisher eventPublisher;

    public UserManagementService(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 CommonPasswordChecker commonPasswordChecker,
                                 ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.commonPasswordChecker = commonPasswordChecker;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<UserEntity> getUsersForTenant(UUID tenantId) {
        return userRepository.findByTenant_Id(tenantId);
    }

    @Transactional
    public UserEntity updateUserRole(UUID tenantId, UUID userId, String newRole) {
        if (!VALID_ROLES.contains(newRole)) {
            throw new IllegalArgumentException("Invalid role: " + newRole);
        }

        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(Entities.notFound("User"));

        if (user.isSuperAdmin()) {
            throw new IllegalStateException("Cannot modify super admin role");
        }

        var oldRole = user.getRole();
        user.setRole(newRole);
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        user.setUpdatedAt(OffsetDateTime.now());
        var saved = userRepository.save(user);
        log.info("Updated role for user {} to {} (token generation bumped)", userId, newRole);
        publishAudit(user.getTenantId(), "USER_ROLE_UPDATE", userId,
                Map.of("old_role", oldRole, "new_role", newRole));
        return saved;
    }

    @Transactional
    public void deleteUser(UUID tenantId, UUID userId) {
        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(Entities.notFound("User"));

        var email = user.getEmail();
        var affectedTenant = user.getTenantId();
        userRepository.delete(user);
        log.info("Deleted user {} from tenant {}", userId, tenantId);
        publishAudit(affectedTenant, "USER_DELETE", userId, Map.of("email", email));
    }

    @Transactional
    public void resetPassword(UUID tenantId, UUID userId, String newPassword) {
        if (commonPasswordChecker.isCommon(newPassword)) {
            throw new IllegalArgumentException("Password is too common — choose a stronger one");
        }
        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(Entities.notFound("User"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("Password reset for user {} in tenant {} (token generation bumped)", userId, tenantId);
        publishAudit(user.getTenantId(), "USER_PASSWORD_RESET", userId,
                Map.of("email", user.getEmail()));
    }

    @Transactional
    public void setUserActive(UUID tenantId, UUID userId, boolean active) {
        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(Entities.notFound("User"));

        user.setActive(active);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("User {} in tenant {} set active={}", userId, tenantId, active);
        publishAudit(user.getTenantId(), "USER_SET_ACTIVE", userId, Map.of("active", active));
    }

    @Transactional(readOnly = true)
    public List<UserEntity> getAllUsers() {
        return userRepository.findAllWithTenant();
    }

    @Transactional
    public void resetPasswordByUserId(UUID userId, String newPassword) {
        if (commonPasswordChecker.isCommon(newPassword)) {
            throw new IllegalArgumentException("Password is too common — choose a stronger one");
        }
        var user = userRepository.findById(userId)
                .orElseThrow(Entities.notFound("User", userId));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("Password reset for user {} by super_admin (token generation bumped)", userId);
        publishAudit(user.getTenantId(), "USER_PASSWORD_RESET", userId,
                Map.of("email", user.getEmail(), "by", "super_admin"));
    }

    @Transactional
    public void setUserActiveById(UUID userId, boolean active) {
        var user = userRepository.findById(userId)
                .orElseThrow(Entities.notFound("User", userId));

        user.setActive(active);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("User {} set active={} by super_admin", userId, active);
        publishAudit(user.getTenantId(), "USER_SET_ACTIVE", userId,
                Map.of("active", active, "by", "super_admin"));
    }

    private void publishAudit(UUID tenantId, String action, UUID targetUserId, Map<String, Object> details) {
        eventPublisher.publishEvent(new AuditEvent(
                tenantId, TenantContext.getCurrentUserId(), action, "user", targetUserId, details));
    }
}
