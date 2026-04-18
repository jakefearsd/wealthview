package com.wealthview.app.config;

import com.wealthview.core.holding.HoldingsComputationService;
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
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * Unit-level test for DevDataInitializer.run(). Like SampleDataInitializer, the
 * run() body is mostly a long sequence of repository.save() calls seeding the
 * demo-admin tenant used in dev. Each repo is mocked to echo back the argument
 * so run() can traverse every branch without NPEing on null returns.
 */
class DevDataInitializerTest {

    private TenantRepository tenantRepository;
    private UserRepository userRepository;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private HoldingsComputationService holdingsComputationService;
    private PriceRepository priceRepository;
    private PropertyRepository propertyRepository;
    private PropertyIncomeRepository propertyIncomeRepository;
    private PropertyExpenseRepository propertyExpenseRepository;
    private InviteCodeRepository inviteCodeRepository;
    private PasswordEncoder passwordEncoder;
    private DevDataInitializer initializer;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        holdingsComputationService = mock(HoldingsComputationService.class);
        priceRepository = mock(PriceRepository.class);
        propertyRepository = mock(PropertyRepository.class);
        propertyIncomeRepository = mock(PropertyIncomeRepository.class);
        propertyExpenseRepository = mock(PropertyExpenseRepository.class);
        inviteCodeRepository = mock(InviteCodeRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);

        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(priceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(propertyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(propertyIncomeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(propertyExpenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inviteCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-demo");

        initializer = new DevDataInitializer(tenantRepository, userRepository,
                accountRepository, transactionRepository, holdingsComputationService,
                priceRepository, propertyRepository, propertyIncomeRepository,
                propertyExpenseRepository, inviteCodeRepository, passwordEncoder);
    }

    @Test
    void run_whenDemoAdminExists_skipsSeeding() {
        when(userRepository.existsByEmail("demo-admin@wealthview.local")).thenReturn(true);

        initializer.run(null);

        verify(tenantRepository, never()).save(any());
        verify(userRepository, never()).save(any());
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

        // Five accounts: brokerage, ira, 401k, roth, bank.
        verify(accountRepository, times(5)).save(any(AccountEntity.class));

        // Transactions, properties, income/expenses, prices all exercised.
        verify(transactionRepository, atLeast(10)).save(any(TransactionEntity.class));
        verify(propertyRepository, atLeast(1)).save(any(PropertyEntity.class));
        verify(propertyIncomeRepository, atLeast(1)).save(any(PropertyIncomeEntity.class));
        verify(propertyExpenseRepository, atLeast(1)).save(any(PropertyExpenseEntity.class));
        verify(priceRepository, atLeast(1)).save(any(PriceEntity.class));
    }

    @Test
    void run_invokesHoldingsRecomputeForAllAccounts() {
        when(userRepository.existsByEmail("demo-admin@wealthview.local")).thenReturn(false);

        initializer.run(null);

        // Holdings are computed from transactions, not inserted manually, so the
        // computation service must be called for each seeded account + symbol pair.
        verify(holdingsComputationService, atLeast(1))
                .recomputeForAccountAndSymbol(any(), any(), anyString());
    }

    @Test
    void initializer_declaresProfileDevOnly() {
        var profile = DevDataInitializer.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev");
    }
}
