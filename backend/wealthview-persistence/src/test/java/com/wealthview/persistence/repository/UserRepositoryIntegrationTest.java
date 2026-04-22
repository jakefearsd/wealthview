package com.wealthview.persistence.repository;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(new TenantEntity("Test Tenant"));
    }

    @Test
    void save_validUser_persistsAndGeneratesId() {
        var user = new UserEntity(tenant, "test@example.com", "hashedpass", "admin");

        var saved = userRepository.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void findByEmail_existingUser_returnsUser() {
        userRepository.save(new UserEntity(tenant, "find@example.com", "hash", "member"));

        var found = userRepository.findByEmail("find@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo("member");
    }

    @Test
    void findByEmail_nonExistent_returnsEmpty() {
        var found = userRepository.findByEmail("nobody@example.com");

        assertThat(found).isEmpty();
    }

    @Test
    void findByTenantId_returnsUsersForTenant() {
        userRepository.save(new UserEntity(tenant, "user1@example.com", "hash", "admin"));
        userRepository.save(new UserEntity(tenant, "user2@example.com", "hash", "member"));

        var users = userRepository.findByTenant_Id(tenant.getId());

        assertThat(users).hasSize(2);
    }

    @Test
    void existsByEmail_existingEmail_returnsTrue() {
        userRepository.save(new UserEntity(tenant, "exists@example.com", "hash", "viewer"));

        assertThat(userRepository.existsByEmail("exists@example.com")).isTrue();
    }

    @Test
    void existsByEmail_nonExistentEmail_returnsFalse() {
        assertThat(userRepository.existsByEmail("nope@example.com")).isFalse();
    }

    @Test
    void save_staleVersion_throwsOptimisticLockingFailure() {
        // A racing refresh (another transaction) bumps the version while we hold a
        // managed entity referencing the now-stale version. Our save must surface as
        // ObjectOptimisticLockingFailureException rather than silently overwriting.
        var managed = userRepository.saveAndFlush(
                new UserEntity(tenant, "race@example.com", "hash", "member"));
        assertThat(managed.getVersion()).isZero();

        entityManager.createNativeQuery(
                        "UPDATE users SET version = version + 1, token_generation = 1 WHERE id = :id")
                .setParameter("id", managed.getId())
                .executeUpdate();

        managed.setTokenGeneration(2);

        assertThatThrownBy(() -> userRepository.saveAndFlush(managed))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
