package com.wealthview.app.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import com.wealthview.persistence.repository.UserRepository;

@Component
@Profile("dev")
@Order(2)
public class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final DemoDataSeeder demoDataSeeder;
    private final TransactionRepository transactionRepository;
    private final PriceRepository priceRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataInitializer(TenantRepository tenantRepository,
                              UserRepository userRepository,
                              DemoDataSeeder demoDataSeeder,
                              TransactionRepository transactionRepository,
                              PriceRepository priceRepository,
                              InviteCodeRepository inviteCodeRepository,
                              PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.demoDataSeeder = demoDataSeeder;
        this.transactionRepository = transactionRepository;
        this.priceRepository = priceRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail("demo-admin@wealthview.local")) {
            log.info("Demo data already exists, skipping initialization");
            return;
        }

        log.info("Creating demo data...");

        // Demo tenant
        var demoTenant = tenantRepository.save(new TenantEntity("Demo Family"));

        // Demo users
        var demoAdmin = new UserEntity(demoTenant, "demo-admin@wealthview.local",
                passwordEncoder.encode("demo123"), "admin");
        demoAdmin = userRepository.save(demoAdmin);

        userRepository.save(new UserEntity(demoTenant, "demo-member@wealthview.local",
                passwordEncoder.encode("demo123"), "member"));

        // Invite code
        inviteCodeRepository.save(new InviteCodeEntity(demoTenant, "DEMO1234", demoAdmin,
                OffsetDateTime.now().plusDays(30)));

        // 5 accounts — holdings are derived from the transactions, not hand-written.
        demoDataSeeder.seedAccount(demoTenant, "Fidelity Brokerage", "brokerage", "Fidelity", List.of(
                buyTxn("2025-01-10", "AAPL", "20", "3400.00"),
                buyTxn("2025-02-15", "MSFT", "10", "4200.00"),
                new TxnSpec(LocalDate.parse("2025-03-01"), "sell", "AAPL",
                        new BigDecimal("5"), new BigDecimal("950.00")),
                new TxnSpec(LocalDate.parse("2025-04-10"), "dividend", "AAPL",
                        null, new BigDecimal("25.00"))));

        demoDataSeeder.seedAccount(demoTenant, "Vanguard IRA", "ira", "Vanguard", List.of(
                buyTxn("2025-01-05", "VOO", "15", "7500.00"),
                buyTxn("2025-02-05", "VTI", "20", "5000.00"),
                buyTxn("2025-03-05", "VOO", "5", "2600.00")));

        demoDataSeeder.seedAccount(demoTenant, "Employer 401k", "401k", "Empower", List.of(
                buyTxn("2025-01-15", "VTI", "30", "7500.00"),
                buyTxn("2025-02-15", "BND", "50", "3500.00"),
                buyTxn("2025-03-15", "VTI", "10", "2600.00")));

        demoDataSeeder.seedAccount(demoTenant, "Roth IRA", "roth", "Vanguard", List.of(
                buyTxn("2025-01-20", "AAPL", "5", "850.00"),
                buyTxn("2025-02-20", "MSFT", "8", "3360.00")));

        demoDataSeeder.seedAccount(demoTenant, "Chase Checking", "bank", "Chase", List.of(
                cashTxn("2025-01-01", "deposit", "5000.00"),
                cashTxn("2025-02-01", "deposit", "5000.00"),
                cashTxn("2025-01-15", "withdrawal", "2000.00"),
                cashTxn("2025-02-15", "withdrawal", "1500.00"),
                cashTxn("2025-03-01", "deposit", "5000.00")));

        // Prices
        priceRepository.save(new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("185.50"), "manual"));
        priceRepository.save(new PriceEntity("MSFT", LocalDate.of(2025, 3, 1), new BigDecimal("425.00"), "manual"));
        priceRepository.save(new PriceEntity("VOO", LocalDate.of(2025, 3, 1), new BigDecimal("520.00"), "manual"));
        priceRepository.save(new PriceEntity("VTI", LocalDate.of(2025, 3, 1), new BigDecimal("260.00"), "manual"));
        priceRepository.save(new PriceEntity("BND", LocalDate.of(2025, 3, 1), new BigDecimal("72.00"), "manual"));

        // Properties
        var property1 = new PropertyEntity(demoTenant,
                "123 Main Street, Springfield", new BigDecimal("320000"),
                LocalDate.of(2020, 6, 1), new BigDecimal("385000"), new BigDecimal("240000"));
        var property2 = new PropertyEntity(demoTenant,
                "456 Oak Avenue, Shelbyville", new BigDecimal("275000"),
                LocalDate.of(2022, 3, 15), new BigDecimal("310000"), new BigDecimal("210000"));

        var property1Income = new ArrayList<IncomeSpec>();
        var property1Expenses = new ArrayList<ExpenseSpec>();
        var property2Income = new ArrayList<IncomeSpec>();
        var property2Expenses = new ArrayList<ExpenseSpec>();
        for (int month = 1; month <= 6; month++) {
            var date = LocalDate.of(2025, month, 1);
            property1Income.add(rent(date, "2200"));
            property1Expenses.add(expense(date, "1400", "mortgage", "Monthly mortgage"));
            property1Expenses.add(expense(date, "200", "insurance", "Homeowner's insurance"));
            property2Income.add(rent(date, "1800"));
            property2Expenses.add(expense(date, "1200", "mortgage", "Monthly mortgage"));
        }
        // One-off maintenance expense
        property1Expenses.add(expense(LocalDate.of(2025, 3, 15), "850", "maintenance", "Plumbing repair"));

        demoDataSeeder.seedProperty(demoTenant, property1, property1Income, property1Expenses);
        demoDataSeeder.seedProperty(demoTenant, property2, property2Income, property2Expenses);

        log.info("Demo data created successfully: tenant={}, 2 users, 5 accounts, {} transactions, 2 properties",
                demoTenant.getId(), transactionRepository.count());
    }

    private static TxnSpec buyTxn(String date, String symbol, String quantity, String amount) {
        return new TxnSpec(LocalDate.parse(date), "buy", symbol,
                new BigDecimal(quantity), new BigDecimal(amount));
    }

    private static TxnSpec cashTxn(String date, String type, String amount) {
        return new TxnSpec(LocalDate.parse(date), type, null, null, new BigDecimal(amount));
    }

    private static IncomeSpec rent(LocalDate date, String amount) {
        return new IncomeSpec(date, new BigDecimal(amount), "rent", "Monthly rent");
    }

    private static ExpenseSpec expense(LocalDate date, String amount, String category, String description) {
        return new ExpenseSpec(date, new BigDecimal(amount), category, description);
    }
}
