package com.wealthview.persistence.repository;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private TenantEntity tenantA;
    private TenantEntity tenantB;

    @BeforeEach
    void setUp() {
        tenantA = tenantRepository.save(new TenantEntity("Tenant A"));
        tenantB = tenantRepository.save(new TenantEntity("Tenant B"));
    }

    // -------------------------------------------------------------------------
    // findByTenant_Id (paginated)
    // -------------------------------------------------------------------------

    @Test
    void findByTenantId_paginated_returnsPagedResults() {
        accountRepository.save(new AccountEntity(tenantA, "Brokerage", "brokerage", "Fidelity"));
        accountRepository.save(new AccountEntity(tenantA, "Retirement", "ira", "Vanguard"));
        accountRepository.save(new AccountEntity(tenantA, "Savings", "bank", "Chase"));

        var page = accountRepository.findByTenant_Id(tenantA.getId(), PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void findByTenantId_paginated_secondPage_returnsRemainder() {
        accountRepository.save(new AccountEntity(tenantA, "Brokerage", "brokerage", "Fidelity"));
        accountRepository.save(new AccountEntity(tenantA, "Retirement", "ira", "Vanguard"));
        accountRepository.save(new AccountEntity(tenantA, "Savings", "bank", "Chase"));

        var page = accountRepository.findByTenant_Id(tenantA.getId(), PageRequest.of(1, 2));

        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void findByTenantId_paginated_emptyResult_whenNoAccounts() {
        var page = accountRepository.findByTenant_Id(tenantA.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    // -------------------------------------------------------------------------
    // Multi-tenant isolation — paginated findByTenant_Id
    // -------------------------------------------------------------------------

    @Test
    void findByTenantId_paginated_isolatesTenantAFromTenantB() {
        accountRepository.save(new AccountEntity(tenantA, "A Account", "brokerage", "Fidelity"));
        accountRepository.save(new AccountEntity(tenantB, "B Account", "brokerage", "Schwab"));

        var pageA = accountRepository.findByTenant_Id(tenantA.getId(), PageRequest.of(0, 10));
        var pageB = accountRepository.findByTenant_Id(tenantB.getId(), PageRequest.of(0, 10));

        assertThat(pageA.getContent()).hasSize(1);
        assertThat(pageA.getContent().get(0).getName()).isEqualTo("A Account");
        assertThat(pageB.getContent()).hasSize(1);
        assertThat(pageB.getContent().get(0).getName()).isEqualTo("B Account");
    }

    // -------------------------------------------------------------------------
    // findByTenant_Id (list overload)
    // -------------------------------------------------------------------------

    @Test
    void findByTenantId_list_isolatesTenantAFromTenantB() {
        accountRepository.save(new AccountEntity(tenantA, "A Only", "brokerage", "Fidelity"));
        accountRepository.save(new AccountEntity(tenantB, "B Only", "brokerage", "Schwab"));

        var listA = accountRepository.findByTenant_Id(tenantA.getId());

        assertThat(listA).hasSize(1);
        assertThat(listA.get(0).getName()).isEqualTo("A Only");
    }

    // -------------------------------------------------------------------------
    // findByTenant_IdAndId
    // -------------------------------------------------------------------------

    @Test
    void findByTenantIdAndId_correctTenant_returnsAccount() {
        var saved = accountRepository.save(new AccountEntity(tenantA, "My Account", "brokerage", "Fidelity"));

        var found = accountRepository.findByTenant_IdAndId(tenantA.getId(), saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("My Account");
    }

    @Test
    void findByTenantIdAndId_wrongTenant_returnsEmpty() {
        var saved = accountRepository.save(new AccountEntity(tenantA, "My Account", "brokerage", "Fidelity"));

        // Tenant B should NOT be able to find Tenant A's account
        var found = accountRepository.findByTenant_IdAndId(tenantB.getId(), saved.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findByTenantIdAndId_nonExistentId_returnsEmpty() {
        var found = accountRepository.findByTenant_IdAndId(tenantA.getId(), UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // countByTenant_Id
    // -------------------------------------------------------------------------

    @Test
    void countByTenantId_returnsCorrectCount() {
        accountRepository.save(new AccountEntity(tenantA, "Acc1", "brokerage", "Fidelity"));
        accountRepository.save(new AccountEntity(tenantA, "Acc2", "ira", "Vanguard"));
        accountRepository.save(new AccountEntity(tenantB, "AccB", "brokerage", "Schwab"));

        assertThat(accountRepository.countByTenant_Id(tenantA.getId())).isEqualTo(2);
        assertThat(accountRepository.countByTenant_Id(tenantB.getId())).isEqualTo(1);
    }

    @Test
    void countByTenantId_noAccounts_returnsZero() {
        assertThat(accountRepository.countByTenant_Id(tenantA.getId())).isZero();
    }
}
