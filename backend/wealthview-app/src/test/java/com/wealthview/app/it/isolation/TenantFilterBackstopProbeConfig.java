package com.wealthview.app.it.isolation;

import com.wealthview.persistence.repository.AccountRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TenantFilterBackstopProbeConfig {

    @Bean
    public TenantFilterBackstopProbe tenantFilterBackstopProbe(AccountRepository accountRepository) {
        return new TenantFilterBackstopProbe(accountRepository);
    }
}
