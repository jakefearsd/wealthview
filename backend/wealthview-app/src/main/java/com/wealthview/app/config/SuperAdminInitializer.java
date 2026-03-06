package com.wealthview.app.config;

import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "docker"})
@Order(1)
public class SuperAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminInitializer.class);

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final String superAdminEmail;
    private final String superAdminPassword;

    public SuperAdminInitializer(UserRepository userRepository,
                                 TenantRepository tenantRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.super-admin.email}") String superAdminEmail,
                                 @Value("${app.super-admin.password}") String superAdminPassword) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.superAdminEmail = superAdminEmail;
        this.superAdminPassword = superAdminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(superAdminEmail)) {
            log.info("Super-admin account already exists");
            return;
        }

        var systemTenant = tenantRepository.save(new TenantEntity("System"));

        var superAdmin = new UserEntity(
                systemTenant,
                superAdminEmail,
                passwordEncoder.encode(superAdminPassword),
                "admin"
        );
        superAdmin.setSuperAdmin(true);
        userRepository.save(superAdmin);

        log.info("Super-admin account created: {}", superAdminEmail);
    }
}
