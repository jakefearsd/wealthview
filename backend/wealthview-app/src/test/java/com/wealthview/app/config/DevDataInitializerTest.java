package com.wealthview.app.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level test for DevDataInitializer.run(). The initializer owns the dev tenant, its
 * two users, an invite code, and manual prices, and delegates account + property seeding
 * (including holdings derivation) to the shared {@link DemoDataSeeder}. Each collaborator is
 * mocked so run() can traverse every branch. Holdings derivation is covered separately in
 * {@link DemoDataSeederTest}.
 */
class DevDataInitializerTest {

    private TenantRepository tenantRepository;
    private UserRepository userRepository;
    private DemoDataSeeder demoDataSeeder;
    private TransactionRepository transactionRepository;
    private PriceRepository priceRepository;
    private InviteCodeRepository inviteCodeRepository;
    private PasswordEncoder passwordEncoder;
    private DevDataInitializer initializer;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        demoDataSeeder = mock(DemoDataSeeder.class);
        transactionRepository = mock(TransactionRepository.class);
        priceRepository = mock(PriceRepository.class);
        inviteCodeRepository = mock(InviteCodeRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);

        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(priceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inviteCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-demo");

        initializer = new DevDataInitializer(tenantRepository, userRepository, demoDataSeeder,
                transactionRepository, priceRepository, inviteCodeRepository, passwordEncoder);
    }

    @Test
    void run_whenDemoAdminExists_skipsSeeding() {
        when(userRepository.existsByEmail("demo-admin@wealthview.local")).thenReturn(true);

        initializer.run(null);

        verify(tenantRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(demoDataSeeder, never()).seedAccount(any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void run_whenNoDemoAdmin_seedsFullDemoEnvironment() {
        when(userRepository.existsByEmail("demo-admin@wealthview.local")).thenReturn(false);

        initializer.run(null);

        // Exactly one tenant saved — "Demo Family"
        verify(tenantRepository, times(1)).save(any(TenantEntity.class));

        // Demo admin + demo member = 2 users.
        verify(userRepository, times(2)).save(any(UserEntity.class));

        // Passwords hashed for both demo users.
        verify(passwordEncoder, atLeast(2)).encode("demo123");

        // Invite code seeded so registrations can be demoed.
        verify(inviteCodeRepository, times(1)).save(any(InviteCodeEntity.class));

        // Five accounts and two properties seeded via the shared seeder.
        verify(demoDataSeeder, times(5))
                .seedAccount(any(TenantEntity.class), anyString(), anyString(), anyString(), anyList());
        verify(demoDataSeeder, times(2))
                .seedProperty(any(TenantEntity.class), any(PropertyEntity.class), anyList(), anyList());

        // Manual prices exercised.
        verify(priceRepository, atLeast(1)).save(any(PriceEntity.class));
    }

    @Test
    void initializer_declaresProfileDevOnly() {
        var profile = DevDataInitializer.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev");
    }
}
