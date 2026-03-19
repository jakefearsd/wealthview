package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    Page<TransactionEntity> findByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId, Pageable pageable);

    Page<TransactionEntity> findByAccount_IdAndTenant_IdAndSymbol(UUID accountId, UUID tenantId, String symbol, Pageable pageable);

    List<TransactionEntity> findByAccount_IdAndSymbol(UUID accountId, String symbol);

    Optional<TransactionEntity> findByIdAndTenant_Id(UUID id, UUID tenantId);

    List<TransactionEntity> findByTenant_Id(UUID tenantId);

    boolean existsByTenant_IdAndAccount_IdAndImportHash(UUID tenantId, UUID accountId, String importHash);

    @Query("""
            SELECT t.importHash FROM TransactionEntity t
            WHERE t.tenant.id = :tenantId AND t.account.id = :accountId
            AND t.importHash IN :hashes
            """)
    Set<String> findExistingImportHashes(@Param("tenantId") UUID tenantId,
                                         @Param("accountId") UUID accountId,
                                         @Param("hashes") Set<String> hashes);

    @Query("""
            SELECT COALESCE(
                SUM(CASE WHEN t.type = 'deposit' THEN t.amount ELSE -t.amount END),
                0)
            FROM TransactionEntity t
            WHERE t.account.id = :accountId AND t.tenant.id = :tenantId
            """)
    BigDecimal computeBalance(@Param("accountId") UUID accountId, @Param("tenantId") UUID tenantId);

    void deleteByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId);
}
