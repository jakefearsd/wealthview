package com.wealthview.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SuppressWarnings("PMD.UseUtilityClass")
@SpringBootApplication(scanBasePackages = "com.wealthview")
// com.wealthview.persistence.projection (MortalityRateEntity / MortalityRateRepository, sub-project
// B task 1) sits alongside entity/repository as its own subpackage rather than inside either -- both
// scans must include it or MortalityTableProvider (core, a @Service that unconditionally depends on
// MortalityRateRepository) fails to start with a NoSuchBeanDefinitionException.
@EntityScan(basePackages = {"com.wealthview.persistence.entity", "com.wealthview.persistence.projection"})
@EnableJpaRepositories(basePackages = {"com.wealthview.persistence.repository",
        "com.wealthview.persistence.projection"})
public class WealthViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(WealthViewApplication.class, args);
    }
}
