package com.wealthview.app.config;

import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminInitializerTest {

    private static final String SUPER_ADMIN_EMAIL = "admin@wealthview.local";
    private static final String SUPER_ADMIN_PASSWORD = "admin123";
    private static final String ENCODED_PASSWORD = "encoded-admin123";

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationArguments applicationArguments;

    private SuperAdminInitializer createInitializer() {
        return new SuperAdminInitializer(
                userRepository,
                tenantRepository,
                passwordEncoder,
                SUPER_ADMIN_EMAIL,
                SUPER_ADMIN_PASSWORD
        );
    }

    @Test
    void run_noExistingAdmin_createsSystemTenantWithCorrectName() {
        when(userRepository.existsByEmail(SUPER_ADMIN_EMAIL)).thenReturn(false);
        var savedTenant = new TenantEntity("System");
        when(tenantRepository.save(any(TenantEntity.class))).thenReturn(savedTenant);
        when(passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        var initializer = createInitializer();

        initializer.run(applicationArguments);

        var tenantCaptor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getName()).isEqualTo("System");
    }

    @Test
    void run_noExistingAdmin_createsSuperAdminUserWithCorrectRoleAndFlag() {
        when(userRepository.existsByEmail(SUPER_ADMIN_EMAIL)).thenReturn(false);
        var savedTenant = new TenantEntity("System");
        when(tenantRepository.save(any(TenantEntity.class))).thenReturn(savedTenant);
        when(passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        var initializer = createInitializer();

        initializer.run(applicationArguments);

        var userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        var savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo(SUPER_ADMIN_EMAIL);
        assertThat(savedUser.getRole()).isEqualTo("admin");
        assertThat(savedUser.isSuperAdmin()).isTrue();
        assertThat(savedUser.getTenant()).isSameAs(savedTenant);
    }

    @Test
    void run_noExistingAdmin_encodesPasswordBeforeSaving() {
        when(userRepository.existsByEmail(SUPER_ADMIN_EMAIL)).thenReturn(false);
        var savedTenant = new TenantEntity("System");
        when(tenantRepository.save(any(TenantEntity.class))).thenReturn(savedTenant);
        when(passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        var initializer = createInitializer();

        initializer.run(applicationArguments);

        verify(passwordEncoder).encode(eq(SUPER_ADMIN_PASSWORD));
        var userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
    }

    @Test
    void run_superAdminAlreadyExists_skipsCreation() {
        when(userRepository.existsByEmail(SUPER_ADMIN_EMAIL)).thenReturn(true);
        var initializer = createInitializer();

        initializer.run(applicationArguments);

        verify(tenantRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void run_superAdminAlreadyExists_doesNotCreateTenant() {
        when(userRepository.existsByEmail(SUPER_ADMIN_EMAIL)).thenReturn(true);
        var initializer = createInitializer();

        initializer.run(applicationArguments);

        verify(tenantRepository, never()).save(any(TenantEntity.class));
    }
}
