package com.wealthview.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.wealthview")
@EntityScan(basePackages = "com.wealthview.persistence.entity")
@EnableJpaRepositories(basePackages = "com.wealthview.persistence.repository")
public class WealthViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(WealthViewApplication.class, args);
    }
}
