package com.wealthview.core.auth;

import com.wealthview.core.auth.dto.LoginActivityResponse;
import com.wealthview.persistence.entity.LoginActivityEntity;
import com.wealthview.persistence.repository.LoginActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LoginActivityService {

    private static final Logger log = LoggerFactory.getLogger(LoginActivityService.class);

    private final LoginActivityRepository loginActivityRepository;

    public LoginActivityService(LoginActivityRepository loginActivityRepository) {
        this.loginActivityRepository = loginActivityRepository;
    }

    @Transactional
    public void record(String email, UUID tenantId, boolean success, String ipAddress) {
        var entity = new LoginActivityEntity(email, tenantId, success, ipAddress);
        loginActivityRepository.save(entity);
        log.debug("Login activity recorded: email={}, success={}, ip={}", email, success, ipAddress);
    }

    @Transactional(readOnly = true)
    public List<LoginActivityResponse> getRecent(int limit) {
        return loginActivityRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .limit(limit)
                .map(LoginActivityResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LoginActivityResponse> getRecentForTenant(UUID tenantId, int limit) {
        return loginActivityRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .limit(limit)
                .map(LoginActivityResponse::from)
                .toList();
    }
}
