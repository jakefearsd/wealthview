package com.wealthview.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.wealthview")
public class WealthViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(WealthViewApplication.class, args);
    }
}
