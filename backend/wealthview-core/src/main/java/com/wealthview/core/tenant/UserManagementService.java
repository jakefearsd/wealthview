package com.wealthview.core.tenant;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserEntity> getUsersForTenant(UUID tenantId) {
        return userRepository.findByTenant_Id(tenantId);
    }

    @Transactional
    public UserEntity updateUserRole(UUID tenantId, UUID userId, String newRole) {
        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        user.setRole(newRole);
        user.setUpdatedAt(OffsetDateTime.now());
        log.info("Updated role for user {} to {}", userId, newRole);
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(UUID tenantId, UUID userId) {
        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        userRepository.delete(user);
        log.info("Deleted user {} from tenant {}", userId, tenantId);
    }

    @Transactional
    public void resetPassword(UUID tenantId, UUID userId, String newPassword) {
        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("Password reset for user {} in tenant {}", userId, tenantId);
    }

    @Transactional
    public void setUserActive(UUID tenantId, UUID userId, boolean active) {
        var user = userRepository.findByTenant_IdAndId(tenantId, userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        user.setActive(active);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("User {} in tenant {} set active={}", userId, tenantId, active);
    }

    @Transactional(readOnly = true)
    public List<UserEntity> getAllUsers() {
        return userRepository.findAllWithTenant();
    }

    @Transactional
    public void resetPasswordByUserId(UUID userId, String newPassword) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("Password reset for user {} by super_admin", userId);
    }

    @Transactional
    public void setUserActiveById(UUID userId, boolean active) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        user.setActive(active);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("User {} set active={} by super_admin", userId, active);
    }
}
