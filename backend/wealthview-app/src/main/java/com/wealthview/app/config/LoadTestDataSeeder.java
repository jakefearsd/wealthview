package com.wealthview.app.config;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import static com.wealthview.persistence.entity.TransactionType.BUY;
import static com.wealthview.persistence.entity.TransactionType.SELL;

/**
 * Profile-gated bulk data seeder for the isolated load-test stack.
 *
 * <p>Active only under the {@code loadtest} Spring profile. On startup it seeds N synthetic tenants
 * — each with a login user, a set of investment accounts (of the core account types), bulk
 * transactions and holdings, a slice of price history, and at least one projection scenario — then
 * writes a manifest of tenant logins (plaintext fake passwords) for k6 to consume.
 *
 * <p>High-volume rows (transactions, holdings, prices) are inserted with explicit-column
 * {@link JdbcTemplate#batchUpdate} for throughput. Tenants, users, accounts, and projection
 * scenarios go through their JPA repositories. Seeding is deterministic ({@code Random(42L)}).
 */
@Component
@Profile("loadtest")
@Order(2)
public class LoadTestDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestDataSeeder.class);

    /** Symbols that have seeded price history (R__seed_stock_prices.sql). */
    private static final String[] SYMBOLS = {"AAPL", "MSFT", "VOO", "VTI", "BND", "SCHD"};
    private static final long SEED = 42L;

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final ProjectionScenarioRepository projectionScenarioRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int configuredTenants;
    private final int configuredTxnsPerTenant;
    private final String manifestPath;
    private final String password;

    public LoadTestDataSeeder(UserRepository userRepository,
                              TenantRepository tenantRepository,
                              AccountRepository accountRepository,
                              ProjectionScenarioRepository projectionScenarioRepository,
                              JdbcTemplate jdbcTemplate,
                              PasswordEncoder passwordEncoder,
                              @Value("${loadtest.seed.tenants:25}") int configuredTenants,
                              @Value("${loadtest.seed.transactions-per-tenant:1500}") int configuredTxnsPerTenant,
                              @Value("${loadtest.seed.manifest-path:/loadtest/results/manifest.json}")
                              String manifestPath,
                              @Value("${loadtest.seed.password:LoadTest-Fake-Pw-123}") String password) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.projectionScenarioRepository = projectionScenarioRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.configuredTenants = configuredTenants;
        this.configuredTxnsPerTenant = configuredTxnsPerTenant;
        this.manifestPath = manifestPath;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        var result = seed(configuredTenants, configuredTxnsPerTenant);
        log.info("LoadTestDataSeeder finished: {} tenants seeded, manifest at {}",
                result.tenantIds().size(), manifestPath);
    }

    /**
     * Seeds {@code tenants} synthetic tenants, each with {@code txnsPerTenant} transactions, and
     * writes the login manifest. Returns the created tenant ids. Callable directly by tests.
     */
    @Transactional
    public SeedResult seed(int tenants, int txnsPerTenant) {
        long start = System.currentTimeMillis();
        var rng = new Random(SEED);

        var tenantIds = new ArrayList<UUID>(tenants);
        var manifestTenants = new ArrayList<TenantManifest>(tenants);
        String encodedPassword = passwordEncoder.encode(password);

        long totalTxns = 0;
        long totalHoldings = 0;

        for (int n = 1; n <= tenants; n++) {
            var tenant = tenantRepository.save(new TenantEntity("LoadTest Tenant " + n));
            tenantIds.add(tenant.getId());

            String email = "loadtest-tenant-" + n + "@loadtest.local";
            userRepository.save(new UserEntity(tenant, email, encodedPassword, "admin"));
            manifestTenants.add(new TenantManifest(tenant.getId(), email, password));

            // Core account types — values satisfy the accounts.type CHECK constraint
            // (brokerage, ira, 401k, roth, bank).
            var brokerage = accountRepository.saveAndFlush(
                    new AccountEntity(tenant, "Brokerage", "brokerage", "LoadTest Brokerage"));
            var ira = accountRepository.saveAndFlush(
                    new AccountEntity(tenant, "Traditional IRA", "ira", "LoadTest IRA"));
            var roth = accountRepository.saveAndFlush(
                    new AccountEntity(tenant, "Roth IRA", "roth", "LoadTest Roth"));
            var accounts = List.of(brokerage, ira, roth);

            totalTxns += seedTransactions(tenant.getId(), accounts, txnsPerTenant, rng);
            totalHoldings += seedHoldings(tenant.getId(), accounts);
            seedScenario(tenant, brokerage, ira, roth);
        }

        long totalPrices = seedPrices();

        writeManifest(new SeedManifest(manifestTenants));

        long elapsed = System.currentTimeMillis() - start;
        log.info("Seeded {} tenants: {} transactions, {} holdings, {} price points in {} ms",
                tenants, totalTxns, totalHoldings, totalPrices, elapsed);

        return new SeedResult(tenantIds, txnsPerTenant);
    }

    private long seedTransactions(UUID tenantId, List<AccountEntity> accounts,
                                  int txnsPerTenant, Random rng) {
        var batch = new ArrayList<Object[]>(txnsPerTenant);
        for (int i = 0; i < txnsPerTenant; i++) {
            var account = accounts.get(rng.nextInt(accounts.size()));
            String symbol = SYMBOLS[rng.nextInt(SYMBOLS.length)];
            var type = rng.nextInt(10) == 0 ? SELL : BUY;
            BigDecimal quantity = BigDecimal.valueOf(1 + rng.nextInt(20));
            BigDecimal pricePerShare = BigDecimal.valueOf(50 + rng.nextInt(400));
            BigDecimal amount = quantity.multiply(pricePerShare).setScale(4, RoundingMode.HALF_UP);
            LocalDate date = LocalDate.of(2018, 1, 1).plusDays(rng.nextInt(2900));
            batch.add(new Object[]{
                    UUID.randomUUID(), account.getId(), tenantId,
                    Date.valueOf(date), type.value(), symbol, quantity, amount
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO transactions "
                        + "(id, account_id, tenant_id, date, type, symbol, quantity, amount) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                batch,
                new int[]{Types.OTHER, Types.OTHER, Types.OTHER, Types.DATE, Types.VARCHAR,
                        Types.VARCHAR, Types.NUMERIC, Types.NUMERIC});
        return batch.size();
    }

    private long seedHoldings(UUID tenantId, List<AccountEntity> accounts) {
        var batch = new ArrayList<Object[]>();
        // One holding per (account, symbol) pair — unique on (account_id, symbol).
        for (AccountEntity account : accounts) {
            for (String symbol : SYMBOLS) {
                BigDecimal quantity = BigDecimal.valueOf(10 + symbol.length());
                BigDecimal costBasis = quantity.multiply(BigDecimal.valueOf(150))
                        .setScale(4, RoundingMode.HALF_UP);
                batch.add(new Object[]{
                        UUID.randomUUID(), account.getId(), tenantId, symbol,
                        quantity, costBasis
                });
            }
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO holdings "
                        + "(id, account_id, tenant_id, symbol, quantity, cost_basis) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                batch,
                new int[]{Types.OTHER, Types.OTHER, Types.OTHER, Types.VARCHAR,
                        Types.NUMERIC, Types.NUMERIC});
        return batch.size();
    }

    /**
     * Creates one minimal-but-valid projection scenario per tenant with a projection account for
     * each pool type (traditional, roth, taxable), so {@code GET /api/v1/projections}
     * lists it and a projection run can execute.
     */
    private void seedScenario(TenantEntity tenant, AccountEntity brokerage,
                              AccountEntity ira, AccountEntity roth) {
        var scenario = new ProjectionScenarioEntity(tenant, "LoadTest Scenario",
                LocalDate.of(2045, 1, 1), 95, new BigDecimal("0.0300"), null);
        scenario.addAccount(new ProjectionAccountEntity(scenario, ira,
                new BigDecimal("250000.0000"), new BigDecimal("23000.0000"),
                new BigDecimal("0.0700"), "traditional"));
        scenario.addAccount(new ProjectionAccountEntity(scenario, roth,
                new BigDecimal("75000.0000"), new BigDecimal("7000.0000"),
                new BigDecimal("0.0700"), "roth"));
        scenario.addAccount(new ProjectionAccountEntity(scenario, brokerage,
                new BigDecimal("150000.0000"), new BigDecimal("12000.0000"),
                new BigDecimal("0.0600"), "taxable"));
        projectionScenarioRepository.save(scenario);
    }

    /**
     * Inserts a short slice of recent daily price history for each seed symbol. Idempotent via
     * {@code ON CONFLICT} on the (symbol, date) primary key, so repeated runs are safe and this
     * supplements (not replaces) the migration's seeded prices.
     */
    private long seedPrices() {
        var batch = new ArrayList<Object[]>();
        LocalDate today = LocalDate.now();
        for (String symbol : SYMBOLS) {
            BigDecimal price = BigDecimal.valueOf(100 + symbol.length() * 10L);
            for (int d = 0; d < 30; d++) {
                LocalDate date = today.minusDays(d);
                batch.add(new Object[]{symbol, Date.valueOf(date),
                        price.add(BigDecimal.valueOf(d)).setScale(4, RoundingMode.HALF_UP),
                        "manual"});
            }
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO prices (symbol, date, close_price, source) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (symbol, date) DO NOTHING",
                batch,
                new int[]{Types.VARCHAR, Types.DATE, Types.NUMERIC, Types.VARCHAR});
        return batch.size();
    }

    private void writeManifest(SeedManifest manifest) {
        try {
            var file = new File(manifestPath);
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, manifest);
            log.info("Wrote load-test manifest with {} tenants to {}",
                    manifest.tenants().size(), manifestPath);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to write load-test manifest to " + manifestPath, e);
        }
    }

    public record SeedResult(List<UUID> tenantIds, int transactionsPerTenant) {
    }

    public record SeedManifest(List<TenantManifest> tenants) {
    }

    public record TenantManifest(UUID tenantId, String email, String password) {
    }
}
