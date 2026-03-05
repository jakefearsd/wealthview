package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    Page<TransactionEntity> findByAccountIdAndTenantId(UUID accountId, UUID tenantId, Pageable pageable);

    List<TransactionEntity> findByAccountIdAndSymbol(UUID accountId, String symbol);

    Optional<TransactionEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
