package com.wealthview.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import com.wealthview.persistence.repository.UserRepository;

/**
 * Drives {@link LoadTestDataSeeder} against the shared Testcontainers Postgres from the IT base.
 *
 * <p>The seeder bean is gated on {@code @Profile("loadtest")}, but the IT base activates only the
 * {@code it} profile (and the loadtest profile would repoint the datasource away from this
 * container). So the test constructs the seeder directly with the autowired repositories,
 * {@code JdbcTemplate}, and the real {@code PasswordEncoder}, then calls the public
 * {@code seed(int, int)} entry point. This keeps the test hermetic and runs the real seeding logic
 * against real Postgres.
 */
class LoadTestDataSeederTest extends AbstractApiIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private HoldingRepository holdingRepository;
    @Autowired
    private ProjectionScenarioRepository projectionScenarioRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private LoadTestDataSeeder newSeeder() {
        return new LoadTestDataSeeder(userRepository, tenantRepository, accountRepository,
                transactionRepository, holdingRepository, projectionScenarioRepository,
                jdbcTemplate, passwordEncoder,
                3, 50, "/tmp/loadtest-seeder-test-manifest.json", "LoadTest-Fake-Pw-123");
    }

    @Test
    void seed_threeTenants_createsThreeTenantIds() {
        var seeder = newSeeder();

        var result = seeder.seed(3, 50);

        assertThat(result.tenantIds()).hasSize(3);
    }

    @Test
    void seed_threeTenants_createsLoginUserForFirstTenant() {
        var seeder = newSeeder();

        seeder.seed(3, 50);

        assertThat(userRepository.findByEmail("loadtest-tenant-1@loadtest.local")).isPresent();
    }

    @Test
    void seed_threeTenants_firstTenantHasAtLeastThreeAccounts() {
        var seeder = newSeeder();

        var result = seeder.seed(3, 50);

        List<AccountEntity> accounts = accountRepository.findByTenant_Id(result.tenantIds().get(0));
        assertThat(accounts).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void seed_threeTenants_firstTenantHasExactlyFiftyTransactions() {
        var seeder = newSeeder();

        var result = seeder.seed(3, 50);

        List<TransactionEntity> transactions =
                transactionRepository.findByTenant_Id(result.tenantIds().get(0));
        assertThat(transactions).hasSize(50);
    }

    @Test
    void seed_threeTenants_firstTenantAccountTypesIncludeCoreTypes() {
        var seeder = newSeeder();

        var result = seeder.seed(3, 50);

        List<String> types = accountRepository.findByTenant_Id(result.tenantIds().get(0)).stream()
                .map(AccountEntity::getType)
                .toList();
        assertThat(types).contains("brokerage", "ira", "roth");
    }

    @Test
    void seed_threeTenants_createsAtLeastOneScenarioPerTenant() {
        var seeder = newSeeder();

        var result = seeder.seed(3, 50);

        assertThat(projectionScenarioRepository.findByTenant_IdOrderByCreatedAtDesc(
                result.tenantIds().get(0))).isNotEmpty();
    }
}
