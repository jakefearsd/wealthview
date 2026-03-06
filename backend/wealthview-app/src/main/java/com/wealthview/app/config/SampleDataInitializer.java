package com.wealthview.app.config;

import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Component
@Profile({"dev", "docker"})
@Order(2)
public class SampleDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataInitializer.class);

    private static final String TEST_USER_EMAIL = "demo@wealthview.local";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyIncomeRepository incomeRepository;
    private final PropertyExpenseRepository expenseRepository;
    private final PasswordEncoder passwordEncoder;

    public SampleDataInitializer(UserRepository userRepository,
                                 TenantRepository tenantRepository,
                                 AccountRepository accountRepository,
                                 TransactionRepository transactionRepository,
                                 HoldingRepository holdingRepository,
                                 PropertyRepository propertyRepository,
                                 PropertyIncomeRepository incomeRepository,
                                 PropertyExpenseRepository expenseRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.propertyRepository = propertyRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
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
        var account = accountRepository.save(
                new AccountEntity(tenant, "Fidelity Brokerage", "brokerage", "Fidelity"));

        // Buy AAPL
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2023, 3, 15), "buy", "AAPL",
                new BigDecimal("25"), new BigDecimal("3875.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2024, 1, 10), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1850.00")));

        // Buy NVDA
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2023, 6, 20), "buy", "NVDA",
                new BigDecimal("40"), new BigDecimal("16800.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2024, 8, 5), "buy", "NVDA",
                new BigDecimal("15"), new BigDecimal("17250.00")));

        // Buy GOOG
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2023, 9, 12), "buy", "GOOG",
                new BigDecimal("50"), new BigDecimal("6700.00")));

        // Buy VOO (S&P 500 ETF)
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2022, 11, 1), "buy", "VOO",
                new BigDecimal("20"), new BigDecimal("7200.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2024, 4, 15), "buy", "VOO",
                new BigDecimal("10"), new BigDecimal("4950.00")));

        // Sell some GOOG
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2025, 1, 8), "sell", "GOOG",
                new BigDecimal("20"), new BigDecimal("3900.00")));

        // Holdings (net positions)
        holdingRepository.save(new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("35"), new BigDecimal("5725.00")));
        holdingRepository.save(new HoldingEntity(account, tenant, "NVDA",
                new BigDecimal("55"), new BigDecimal("34050.00")));
        holdingRepository.save(new HoldingEntity(account, tenant, "GOOG",
                new BigDecimal("30"), new BigDecimal("4020.00")));
        holdingRepository.save(new HoldingEntity(account, tenant, "VOO",
                new BigDecimal("30"), new BigDecimal("12150.00")));
    }

    private void seedRetirementAccount(TenantEntity tenant) {
        var account = accountRepository.save(
                new AccountEntity(tenant, "Fidelity 401(k)", "401k", "Fidelity"));

        // Regular contributions to FXAIX and SCHD
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2023, 1, 15), "buy", "FXAIX",
                new BigDecimal("30"), new BigDecimal("4500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2023, 7, 15), "buy", "FXAIX",
                new BigDecimal("28"), new BigDecimal("4500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2024, 1, 15), "buy", "FXAIX",
                new BigDecimal("25"), new BigDecimal("4500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2024, 7, 15), "buy", "FXAIX",
                new BigDecimal("24"), new BigDecimal("4500.00")));

        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2023, 3, 1), "buy", "SCHD",
                new BigDecimal("60"), new BigDecimal("4500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2024, 3, 1), "buy", "SCHD",
                new BigDecimal("55"), new BigDecimal("4500.00")));

        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2023, 6, 1), "buy", "VXUS",
                new BigDecimal("80"), new BigDecimal("4400.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2024, 6, 1), "buy", "VXUS",
                new BigDecimal("75"), new BigDecimal("4200.00")));

        holdingRepository.save(new HoldingEntity(account, tenant, "FXAIX",
                new BigDecimal("107"), new BigDecimal("18000.00")));
        holdingRepository.save(new HoldingEntity(account, tenant, "SCHD",
                new BigDecimal("115"), new BigDecimal("9000.00")));
        holdingRepository.save(new HoldingEntity(account, tenant, "VXUS",
                new BigDecimal("155"), new BigDecimal("8600.00")));
    }

    private void seedBankAccount(TenantEntity tenant) {
        var account = accountRepository.save(
                new AccountEntity(tenant, "Chase Checking", "bank", "Chase"));

        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2025, 1, 1), "deposit", null,
                null, new BigDecimal("8500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2025, 1, 15), "deposit", null,
                null, new BigDecimal("8500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2025, 2, 1), "deposit", null,
                null, new BigDecimal("8500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2025, 2, 15), "deposit", null,
                null, new BigDecimal("8500.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2025, 1, 5), "withdrawal", null,
                null, new BigDecimal("3200.00")));
        transactionRepository.save(new TransactionEntity(account, tenant,
                LocalDate.of(2025, 2, 5), "withdrawal", null,
                null, new BigDecimal("3200.00")));
    }

    private void seedProperties(TenantEntity tenant) {
        // Rental property 1
        var rental1 = propertyRepository.save(new PropertyEntity(tenant,
                "742 Evergreen Terrace, Springfield",
                new BigDecimal("285000.00"), LocalDate.of(2019, 8, 15),
                new BigDecimal("340000.00"), new BigDecimal("195000.00")));

        // 12 months of rent
        for (int month = 1; month <= 12; month++) {
            incomeRepository.save(new PropertyIncomeEntity(rental1, tenant,
                    LocalDate.of(2025, month, 1), new BigDecimal("2200.00"),
                    "rent", "Monthly rent"));
        }
        // Annual expenses
        expenseRepository.save(new PropertyExpenseEntity(rental1, tenant,
                LocalDate.of(2025, 1, 15), new BigDecimal("3800.00"),
                "insurance", "Annual homeowners insurance"));
        expenseRepository.save(new PropertyExpenseEntity(rental1, tenant,
                LocalDate.of(2025, 3, 10), new BigDecimal("1200.00"),
                "maintenance", "HVAC service and filter replacement"));
        expenseRepository.save(new PropertyExpenseEntity(rental1, tenant,
                LocalDate.of(2025, 6, 20), new BigDecimal("4200.00"),
                "tax", "Property tax - H1"));
        expenseRepository.save(new PropertyExpenseEntity(rental1, tenant,
                LocalDate.of(2025, 7, 5), new BigDecimal("850.00"),
                "maintenance", "Plumbing repair"));

        // Rental property 2
        var rental2 = propertyRepository.save(new PropertyEntity(tenant,
                "1600 Pennsylvania Ave, Washington DC",
                new BigDecimal("425000.00"), LocalDate.of(2021, 3, 1),
                new BigDecimal("485000.00"), new BigDecimal("310000.00")));

        for (int month = 1; month <= 12; month++) {
            incomeRepository.save(new PropertyIncomeEntity(rental2, tenant,
                    LocalDate.of(2025, month, 1), new BigDecimal("3100.00"),
                    "rent", "Monthly rent"));
        }
        expenseRepository.save(new PropertyExpenseEntity(rental2, tenant,
                LocalDate.of(2025, 2, 1), new BigDecimal("4500.00"),
                "insurance", "Annual homeowners insurance"));
        expenseRepository.save(new PropertyExpenseEntity(rental2, tenant,
                LocalDate.of(2025, 5, 15), new BigDecimal("5100.00"),
                "tax", "Property tax - H1"));
        expenseRepository.save(new PropertyExpenseEntity(rental2, tenant,
                LocalDate.of(2025, 9, 8), new BigDecimal("2800.00"),
                "maintenance", "Roof repair"));
    }
}
