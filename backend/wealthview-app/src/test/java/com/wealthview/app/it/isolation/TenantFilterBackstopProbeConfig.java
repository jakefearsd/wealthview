package com.wealthview.app.it.isolation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.wealthview.persistence.repository.AccountRepository;

@TestConfiguration
public class TenantFilterBackstopProbeConfig {

    @Bean
    public TenantFilterBackstopProbe tenantFilterBackstopProbe(AccountRepository accountRepository) {
        return new TenantFilterBackstopProbe(accountRepository);
    }
}
