package com.wealthview.core.tenant;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.TenantAccessDeniedException;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final UserRepository userRepository;

    public UserManagementService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<UserEntity> getUsersForTenant(UUID tenantId) {
        return userRepository.findByTenantId(tenantId);
    }

    @Transactional
    public UserEntity updateUserRole(UUID tenantId, UUID userId, String newRole) {
        var user = userRepository.findByTenantIdAndId(tenantId, userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        user.setRole(newRole);
        user.setUpdatedAt(OffsetDateTime.now());
        log.info("Updated role for user {} to {}", userId, newRole);
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(UUID tenantId, UUID userId) {
        var user = userRepository.findByTenantIdAndId(tenantId, userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        userRepository.delete(user);
        log.info("Deleted user {} from tenant {}", userId, tenantId);
    }
}
