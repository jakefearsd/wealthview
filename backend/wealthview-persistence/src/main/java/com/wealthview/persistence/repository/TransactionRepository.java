package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wealthview.persistence.entity.TransactionEntity;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    Page<TransactionEntity> findByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId, Pageable pageable);

    Page<TransactionEntity> findByAccount_IdAndTenant_IdAndSymbol(
            UUID accountId, UUID tenantId, String symbol, Pageable pageable);

    List<TransactionEntity> findByAccount_IdAndSymbol(UUID accountId, String symbol);

    Optional<TransactionEntity> findByIdAndTenant_Id(UUID id, UUID tenantId);

    List<TransactionEntity> findByTenant_Id(UUID tenantId);

    List<TransactionEntity> findByTenant_IdAndSymbol(UUID tenantId, String symbol);

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

    @Query("""
            SELECT t.account.id, COALESCE(
                SUM(CASE WHEN t.type = 'deposit' THEN t.amount ELSE -t.amount END),
                0)
            FROM TransactionEntity t
            WHERE t.tenant.id = :tenantId
            AND t.account.id IN :accountIds
            GROUP BY t.account.id
            """)
    List<Object[]> computeBalancesByAccountIds(@Param("tenantId") UUID tenantId,
                                               @Param("accountIds") List<UUID> accountIds);

    void deleteByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId);

    @Query("SELECT DISTINCT t.symbol FROM TransactionEntity t WHERE t.symbol IS NOT NULL")
    List<String> findDistinctSymbolsAcrossAllTenants();

    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.symbol = :symbol
              AND t.date <= :asOf
            """)
    List<TransactionEntity> findBySymbolAndDateOnOrBefore(@Param("symbol") String symbol,
                                                          @Param("asOf") java.time.LocalDate asOf);

    @Query("""
            SELECT MIN(t.date) FROM TransactionEntity t
            WHERE t.symbol = :symbol
            """)
    Optional<java.time.LocalDate> findEarliestDateBySymbol(@Param("symbol") String symbol);

    @Query("""
            SELECT DISTINCT t.tenant.id FROM TransactionEntity t
            WHERE t.symbol = :symbol
            """)
    List<UUID> findDistinctTenantIdsBySymbol(@Param("symbol") String symbol);
}
