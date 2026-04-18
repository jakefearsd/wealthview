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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Unit-level test of SampleDataInitializer.run() — exercises the 100+ lines of
 * demo-data seeding code end-to-end with mocked repositories and a fixed password
 * encoder. Verifies:
 *   - Happy path: repositories save the expected entity types for the demo tenant.
 *   - Idempotency: when the demo user already exists, no seeding occurs.
 *
 * Avoids a full Spring Boot context; each save() is mocked to return its argument
 * so the initializer continues through all its internal phases without NPE.
 */
class SampleDataInitializerRunTest {

    private UserRepository userRepository;
    private TenantRepository tenantRepository;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private HoldingRepository holdingRepository;
    private PropertyRepository propertyRepository;
    private PropertyIncomeRepository incomeRepository;
    private PropertyExpenseRepository expenseRepository;
    private PasswordEncoder passwordEncoder;
    private SampleDataInitializer initializer;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tenantRepository = mock(TenantRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        propertyRepository = mock(PropertyRepository.class);
        incomeRepository = mock(PropertyIncomeRepository.class);
        expenseRepository = mock(PropertyExpenseRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);

        // All save() calls echo back the argument so the code continues through
        // the full seed sequence instead of NPEing on a null return.
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(holdingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(propertyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(incomeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(expenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-demo");

        initializer = new SampleDataInitializer(userRepository, tenantRepository,
                accountRepository, transactionRepository, holdingRepository,
                propertyRepository, incomeRepository, expenseRepository, passwordEncoder);
    }

    @Test
    void run_whenDemoUserAlreadyExists_skipsSeeding() throws Exception {
        when(userRepository.existsByEmail("demo@wealthview.local")).thenReturn(true);

        initializer.run(null);

        // Nothing should be saved if the demo user was already present.
        verify(tenantRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void run_whenNoDemoUser_seedsTenantUserAccountsAndProperties() throws Exception {
        when(userRepository.existsByEmail("demo@wealthview.local")).thenReturn(false);

        initializer.run(null);

        // Tenant "Demo Family" saved exactly once.
        verify(tenantRepository, times(1)).save(any(TenantEntity.class));

        // Demo user saved with the hashed password + admin role.
        verify(passwordEncoder).encode("demo123");
        verify(userRepository).save(any(UserEntity.class));

        // Three accounts: brokerage, 401k, bank.
        verify(accountRepository, times(3)).save(any(AccountEntity.class));

        // Transactions, holdings, properties, income/expenses all get written — the
        // exact counts change with the seed; these lower bounds just guarantee the
        // full run() body executed without early-exiting.
        verify(transactionRepository, atLeast(10)).save(any(TransactionEntity.class));
        verify(holdingRepository, atLeast(7)).save(any(HoldingEntity.class));
        verify(propertyRepository, atLeast(1)).save(any(PropertyEntity.class));
        verify(incomeRepository, atLeast(1)).save(any(PropertyIncomeEntity.class));
        verify(expenseRepository, atLeast(1)).save(any(PropertyExpenseEntity.class));
    }

    @Test
    void initializer_declaresProfileDevAndDocker() {
        var profile = SampleDataInitializer.class
                .getAnnotation(org.springframework.context.annotation.Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "docker");
    }
}
