package com.wealthview.app.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level test of SampleDataInitializer.run() — exercises the demo-data seeding
 * orchestration with mocked collaborators. Verifies:
 *   - Happy path: the tenant + demo user are created and the shared DemoDataSeeder is
 *     asked to seed the three accounts and two properties.
 *   - Idempotency: when the demo user already exists, no seeding occurs (guard preserved).
 *
 * Holdings derivation itself is covered by {@link DemoDataSeederTest}.
 */
class SampleDataInitializerRunTest {

    private UserRepository userRepository;
    private TenantRepository tenantRepository;
    private DemoDataSeeder demoDataSeeder;
    private PasswordEncoder passwordEncoder;
    private SampleDataInitializer initializer;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tenantRepository = mock(TenantRepository.class);
        demoDataSeeder = mock(DemoDataSeeder.class);
        passwordEncoder = mock(PasswordEncoder.class);

        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-demo");

        initializer = new SampleDataInitializer(userRepository, tenantRepository,
                demoDataSeeder, passwordEncoder);
    }

    @Test
    void run_whenDemoUserAlreadyExists_skipsSeeding() {
        when(userRepository.existsByEmail("demo@wealthview.local")).thenReturn(true);

        initializer.run(null);

        // Nothing should be saved or seeded if the demo user was already present.
        verify(tenantRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(demoDataSeeder, never()).seedAccount(any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void run_whenNoDemoUser_seedsTenantUserAccountsAndProperties() {
        when(userRepository.existsByEmail("demo@wealthview.local")).thenReturn(false);

        initializer.run(null);

        // Tenant "Demo Family" saved exactly once.
        verify(tenantRepository, times(1)).save(any(TenantEntity.class));

        // Demo user saved with the hashed password + admin role.
        verify(passwordEncoder).encode("demo123");
        verify(userRepository).save(any(UserEntity.class));

        // Three accounts (brokerage, 401k, bank) and two properties seeded via the shared seeder.
        verify(demoDataSeeder, times(3))
                .seedAccount(any(TenantEntity.class), anyString(), anyString(), anyString(), anyList());
        verify(demoDataSeeder, times(2))
                .seedProperty(any(TenantEntity.class), any(PropertyEntity.class),
                        anyList(), anyList());
    }

    @Test
    void initializer_declaresProfileDevAndDocker() {
        var profile = SampleDataInitializer.class
                .getAnnotation(org.springframework.context.annotation.Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "docker");
    }
}
