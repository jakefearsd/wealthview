package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    Page<TransactionEntity> findByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId, Pageable pageable);

    Page<TransactionEntity> findByAccount_IdAndTenant_IdAndSymbol(UUID accountId, UUID tenantId, String symbol, Pageable pageable);

    List<TransactionEntity> findByAccount_IdAndSymbol(UUID accountId, String symbol);

    Optional<TransactionEntity> findByIdAndTenant_Id(UUID id, UUID tenantId);

    List<TransactionEntity> findByTenant_Id(UUID tenantId);

    boolean existsByTenant_IdAndAccount_IdAndImportHash(UUID tenantId, UUID accountId, String importHash);

    void deleteByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId);
}
