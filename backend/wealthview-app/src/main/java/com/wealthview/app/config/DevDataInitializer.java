package com.wealthview.app.config;

import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import com.wealthview.persistence.repository.UserRepository;
import com.wealthview.core.holding.HoldingsComputationService;
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
import java.time.OffsetDateTime;
import java.util.Set;

@Component
@Profile("dev")
@Order(2)
public class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingsComputationService holdingsComputationService;
    private final PriceRepository priceRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyIncomeRepository propertyIncomeRepository;
    private final PropertyExpenseRepository propertyExpenseRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataInitializer(TenantRepository tenantRepository,
                              UserRepository userRepository,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              HoldingsComputationService holdingsComputationService,
                              PriceRepository priceRepository,
                              PropertyRepository propertyRepository,
                              PropertyIncomeRepository propertyIncomeRepository,
                              PropertyExpenseRepository propertyExpenseRepository,
                              InviteCodeRepository inviteCodeRepository,
                              PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.holdingsComputationService = holdingsComputationService;
        this.priceRepository = priceRepository;
        this.propertyRepository = propertyRepository;
        this.propertyIncomeRepository = propertyIncomeRepository;
        this.propertyExpenseRepository = propertyExpenseRepository;
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

        var demoMember = new UserEntity(demoTenant, "demo-member@wealthview.local",
                passwordEncoder.encode("demo123"), "member");
        demoMember = userRepository.save(demoMember);

        // Invite code
        inviteCodeRepository.save(new InviteCodeEntity(demoTenant, "DEMO1234", demoAdmin,
                OffsetDateTime.now().plusDays(30)));

        // 5 Accounts
        var brokerage = accountRepository.save(
                new AccountEntity(demoTenant, "Fidelity Brokerage", "brokerage", "Fidelity"));
        var ira = accountRepository.save(
                new AccountEntity(demoTenant, "Vanguard IRA", "ira", "Vanguard"));
        var fourOhOneK = accountRepository.save(
                new AccountEntity(demoTenant, "Employer 401k", "401k", "Empower"));
        var roth = accountRepository.save(
                new AccountEntity(demoTenant, "Roth IRA", "roth", "Vanguard"));
        var checking = accountRepository.save(
                new AccountEntity(demoTenant, "Chase Checking", "bank", "Chase"));

        // Transactions for brokerage
        createTxn(brokerage, demoTenant, "2025-01-10", "buy", "AAPL", "20", "3400.00");
        createTxn(brokerage, demoTenant, "2025-02-15", "buy", "MSFT", "10", "4200.00");
        createTxn(brokerage, demoTenant, "2025-03-01", "sell", "AAPL", "5", "950.00");
        createTxn(brokerage, demoTenant, "2025-04-10", "dividend", "AAPL", null, "25.00");

        // Transactions for IRA
        createTxn(ira, demoTenant, "2025-01-05", "buy", "VOO", "15", "7500.00");
        createTxn(ira, demoTenant, "2025-02-05", "buy", "VTI", "20", "5000.00");
        createTxn(ira, demoTenant, "2025-03-05", "buy", "VOO", "5", "2600.00");

        // Transactions for 401k
        createTxn(fourOhOneK, demoTenant, "2025-01-15", "buy", "VTI", "30", "7500.00");
        createTxn(fourOhOneK, demoTenant, "2025-02-15", "buy", "BND", "50", "3500.00");
        createTxn(fourOhOneK, demoTenant, "2025-03-15", "buy", "VTI", "10", "2600.00");

        // Transactions for Roth
        createTxn(roth, demoTenant, "2025-01-20", "buy", "AAPL", "5", "850.00");
        createTxn(roth, demoTenant, "2025-02-20", "buy", "MSFT", "8", "3360.00");

        // Bank transactions
        createTxn(checking, demoTenant, "2025-01-01", "deposit", null, null, "5000.00");
        createTxn(checking, demoTenant, "2025-02-01", "deposit", null, null, "5000.00");
        createTxn(checking, demoTenant, "2025-01-15", "withdrawal", null, null, "2000.00");
        createTxn(checking, demoTenant, "2025-02-15", "withdrawal", null, null, "1500.00");
        createTxn(checking, demoTenant, "2025-03-01", "deposit", null, null, "5000.00");

        // Recompute holdings for all symbols
        for (var symbol : Set.of("AAPL", "MSFT", "VOO", "VTI", "BND")) {
            holdingsComputationService.recomputeForAccountAndSymbol(brokerage, demoTenant, symbol);
            holdingsComputationService.recomputeForAccountAndSymbol(ira, demoTenant, symbol);
            holdingsComputationService.recomputeForAccountAndSymbol(fourOhOneK, demoTenant, symbol);
            holdingsComputationService.recomputeForAccountAndSymbol(roth, demoTenant, symbol);
        }

        // Prices
        priceRepository.save(new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("185.50"), "manual"));
        priceRepository.save(new PriceEntity("MSFT", LocalDate.of(2025, 3, 1), new BigDecimal("425.00"), "manual"));
        priceRepository.save(new PriceEntity("VOO", LocalDate.of(2025, 3, 1), new BigDecimal("520.00"), "manual"));
        priceRepository.save(new PriceEntity("VTI", LocalDate.of(2025, 3, 1), new BigDecimal("260.00"), "manual"));
        priceRepository.save(new PriceEntity("BND", LocalDate.of(2025, 3, 1), new BigDecimal("72.00"), "manual"));

        // Properties
        var property1 = propertyRepository.save(new PropertyEntity(demoTenant,
                "123 Main Street, Springfield", new BigDecimal("320000"),
                LocalDate.of(2020, 6, 1), new BigDecimal("385000"), new BigDecimal("240000")));

        var property2 = propertyRepository.save(new PropertyEntity(demoTenant,
                "456 Oak Avenue, Shelbyville", new BigDecimal("275000"),
                LocalDate.of(2022, 3, 15), new BigDecimal("310000"), new BigDecimal("210000")));

        // Property income/expenses for 6 months
        for (int month = 1; month <= 6; month++) {
            var date = LocalDate.of(2025, month, 1);

            propertyIncomeRepository.save(new PropertyIncomeEntity(property1, demoTenant,
                    date, new BigDecimal("2200"), "rent", "Monthly rent"));
            propertyExpenseRepository.save(new PropertyExpenseEntity(property1, demoTenant,
                    date, new BigDecimal("1400"), "mortgage", "Monthly mortgage"));
            propertyExpenseRepository.save(new PropertyExpenseEntity(property1, demoTenant,
                    date, new BigDecimal("200"), "insurance", "Homeowner's insurance"));

            propertyIncomeRepository.save(new PropertyIncomeEntity(property2, demoTenant,
                    date, new BigDecimal("1800"), "rent", "Monthly rent"));
            propertyExpenseRepository.save(new PropertyExpenseEntity(property2, demoTenant,
                    date, new BigDecimal("1200"), "mortgage", "Monthly mortgage"));
        }

        // One-off maintenance expense
        propertyExpenseRepository.save(new PropertyExpenseEntity(property1, demoTenant,
                LocalDate.of(2025, 3, 15), new BigDecimal("850"), "maintenance", "Plumbing repair"));

        log.info("Demo data created successfully: tenant={}, 2 users, 5 accounts, {} transactions, 2 properties",
                demoTenant.getId(), transactionRepository.count());
    }

    private void createTxn(AccountEntity account, TenantEntity tenant, String date,
                           String type, String symbol, String quantity, String amount) {
        var txn = new TransactionEntity(account, tenant,
                LocalDate.parse(date), type, symbol,
                quantity != null ? new BigDecimal(quantity) : null,
                new BigDecimal(amount));
        transactionRepository.save(txn);
    }
}
