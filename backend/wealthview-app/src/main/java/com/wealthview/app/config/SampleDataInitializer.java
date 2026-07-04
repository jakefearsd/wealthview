package com.wealthview.app.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.app.config.DemoDataSeeder.ExpenseSpec;
import com.wealthview.app.config.DemoDataSeeder.IncomeSpec;
import com.wealthview.app.config.DemoDataSeeder.TxnSpec;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;

@Component
@Profile({"dev", "docker"})
@Order(2)
public class SampleDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataInitializer.class);

    private static final String TEST_USER_EMAIL = "demo@wealthview.local";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final DemoDataSeeder demoDataSeeder;
    private final PasswordEncoder passwordEncoder;

    public SampleDataInitializer(UserRepository userRepository,
                                 TenantRepository tenantRepository,
                                 DemoDataSeeder demoDataSeeder,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.demoDataSeeder = demoDataSeeder;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(TEST_USER_EMAIL)) {
            log.info("Sample data already exists, skipping seeding");
            return;
        }

        log.info("Seeding sample data...");

        var tenant = tenantRepository.save(new TenantEntity("Demo Family"));

        var user = new UserEntity(tenant, TEST_USER_EMAIL,
                passwordEncoder.encode("demo123"), "admin");
        userRepository.save(user);

        seedBrokerageAccount(tenant);
        seedRetirementAccount(tenant);
        seedBankAccount(tenant);
        seedProperties(tenant);

        log.info("Sample data seeded for tenant '{}' with user '{}'", tenant.getName(), TEST_USER_EMAIL);
    }

    private void seedBrokerageAccount(TenantEntity tenant) {
        // Holdings (AAPL 35/5725, NVDA 55/34050, GOOG 30/4020, VOO 30/12150) are derived
        // from these transactions by DemoDataSeeder, not hand-written.
        demoDataSeeder.seedAccount(tenant, "Fidelity Brokerage", "brokerage", "Fidelity", List.of(
                buyTxn("2023-03-15", "AAPL", "25", "3875.00"),
                buyTxn("2024-01-10", "AAPL", "10", "1850.00"),
                buyTxn("2023-06-20", "NVDA", "40", "16800.00"),
                buyTxn("2024-08-05", "NVDA", "15", "17250.00"),
                buyTxn("2023-09-12", "GOOG", "50", "6700.00"),
                buyTxn("2022-11-01", "VOO", "20", "7200.00"),
                buyTxn("2024-04-15", "VOO", "10", "4950.00"),
                new TxnSpec(LocalDate.parse("2025-01-08"), "sell", "GOOG",
                        new BigDecimal("20"), new BigDecimal("3900.00"))));
    }

    private void seedRetirementAccount(TenantEntity tenant) {
        // Derived holdings: FXAIX 107/18000, SCHD 115/9000, VXUS 155/8600.
        demoDataSeeder.seedAccount(tenant, "Fidelity 401(k)", "401k", "Fidelity", List.of(
                buyTxn("2023-01-15", "FXAIX", "30", "4500.00"),
                buyTxn("2023-07-15", "FXAIX", "28", "4500.00"),
                buyTxn("2024-01-15", "FXAIX", "25", "4500.00"),
                buyTxn("2024-07-15", "FXAIX", "24", "4500.00"),
                buyTxn("2023-03-01", "SCHD", "60", "4500.00"),
                buyTxn("2024-03-01", "SCHD", "55", "4500.00"),
                buyTxn("2023-06-01", "VXUS", "80", "4400.00"),
                buyTxn("2024-06-01", "VXUS", "75", "4200.00")));
    }

    private void seedBankAccount(TenantEntity tenant) {
        demoDataSeeder.seedAccount(tenant, "Chase Checking", "bank", "Chase", List.of(
                cashTxn("2025-01-01", "deposit", "8500.00"),
                cashTxn("2025-01-15", "deposit", "8500.00"),
                cashTxn("2025-02-01", "deposit", "8500.00"),
                cashTxn("2025-02-15", "deposit", "8500.00"),
                cashTxn("2025-01-05", "withdrawal", "3200.00"),
                cashTxn("2025-02-05", "withdrawal", "3200.00")));
    }

    private static TxnSpec buyTxn(String date, String symbol, String quantity, String amount) {
        return new TxnSpec(LocalDate.parse(date), "buy", symbol,
                new BigDecimal(quantity), new BigDecimal(amount));
    }

    private static TxnSpec cashTxn(String date, String type, String amount) {
        return new TxnSpec(LocalDate.parse(date), type, null, null, new BigDecimal(amount));
    }

    private void seedProperties(TenantEntity tenant) {
        // Rental property 1
        var rental1 = new PropertyEntity(tenant,
                "742 Evergreen Terrace, Springfield",
                new BigDecimal("285000.00"), LocalDate.of(2019, 8, 15),
                new BigDecimal("340000.00"), new BigDecimal("195000.00"));

        var rental1Income = new ArrayList<IncomeSpec>();
        for (int month = 1; month <= 12; month++) {
            rental1Income.add(rent(LocalDate.of(2025, month, 1), "2200.00"));
        }
        var rental1Expenses = List.of(
                expense(LocalDate.of(2025, 1, 15), "3800.00",
                        "insurance", "Annual homeowners insurance"),
                expense(LocalDate.of(2025, 3, 10), "1200.00",
                        "maintenance", "HVAC service and filter replacement"),
                expense(LocalDate.of(2025, 6, 20), "4200.00", "tax", "Property tax - H1"),
                expense(LocalDate.of(2025, 7, 5), "850.00", "maintenance", "Plumbing repair"));
        demoDataSeeder.seedProperty(tenant, rental1, rental1Income, rental1Expenses);

        // Rental property 2 — real test case, with computed loan balance
        var rental2 = new PropertyEntity(tenant,
                "2020 Beryl Street, San Diego CA 92109",
                new BigDecimal("1300000.00"), LocalDate.of(2020, 9, 1),
                new BigDecimal("1300000.00"), new BigDecimal("905000.00"));
        rental2.setLoanAmount(new BigDecimal("905000.00"));
        rental2.setAnnualInterestRate(new BigDecimal("0.0275"));
        rental2.setLoanTermMonths(360);
        rental2.setLoanStartDate(LocalDate.of(2020, 9, 1));
        rental2.setUseComputedBalance(true);

        var rental2Income = new ArrayList<IncomeSpec>();
        for (int month = 1; month <= 12; month++) {
            rental2Income.add(rent(LocalDate.of(2025, month, 1), "4200.00"));
        }
        var rental2Expenses = List.of(
                expense(LocalDate.of(2025, 2, 1), "6200.00",
                        "insurance", "Annual homeowners insurance"),
                expense(LocalDate.of(2025, 4, 10), "7800.00", "tax", "Property tax - H1"),
                expense(LocalDate.of(2025, 8, 15), "1500.00",
                        "maintenance", "Landscaping and irrigation repair"));
        demoDataSeeder.seedProperty(tenant, rental2, rental2Income, rental2Expenses);
    }

    private static IncomeSpec rent(LocalDate date, String amount) {
        return new IncomeSpec(date, new BigDecimal(amount), "rent", "Monthly rent");
    }

    private static ExpenseSpec expense(LocalDate date, String amount, String category, String description) {
        return new ExpenseSpec(date, new BigDecimal(amount), category, description);
    }
}
