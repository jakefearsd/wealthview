package com.wealthview.core.auth;

import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SessionStateValidator {

    private static final Logger log = LoggerFactory.getLogger(SessionStateValidator.class);

    private final UserRepository userRepository;

    public SessionStateValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public boolean isSessionValid(UUID userId, int tokenGeneration) {
        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Session rejected: user {} not found", userId);
            return false;
        }
        if (!user.isActive()) {
            log.warn("Session rejected: user {} is disabled", userId);
            return false;
        }
        if (!user.getTenant().isActive()) {
            log.warn("Session rejected: tenant {} is disabled (user {})",
                    user.getTenantId(), userId);
            return false;
        }
        if (tokenGeneration != user.getTokenGeneration()) {
            log.warn("Session rejected: stale generation for user {} (token={}, current={})",
                    userId, tokenGeneration, user.getTokenGeneration());
            return false;
        }
        return true;
    }
}
