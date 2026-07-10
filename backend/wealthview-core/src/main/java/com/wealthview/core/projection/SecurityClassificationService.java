package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.SecurityAssetClassRepository;
import com.wealthview.persistence.repository.SecurityClassOverrideRepository;

/**
 * Classifies securities into {@link AssetClass}es and derives value-weighted account allocations.
 *
 * <p>Classification precedence: a tenant-scoped manual override wins over the global seed
 * table, which wins over the {@link AssetClass#US_STOCK} default.
 */
@Service
public class SecurityClassificationService {

    private final SecurityAssetClassRepository seedRepository;
    private final SecurityClassOverrideRepository overrideRepository;
    private final HoldingRepository holdingRepository;
    private final PriceRepository priceRepository;

    public SecurityClassificationService(SecurityAssetClassRepository seedRepository,
                                         SecurityClassOverrideRepository overrideRepository,
                                         HoldingRepository holdingRepository,
                                         PriceRepository priceRepository) {
        this.seedRepository = seedRepository;
        this.overrideRepository = overrideRepository;
        this.holdingRepository = holdingRepository;
        this.priceRepository = priceRepository;
    }

    /**
     * Resolves the asset class for a single symbol: tenant override, then the global seed
     * table, then {@link AssetClass#US_STOCK} as the default.
     */
    @Transactional(readOnly = true)
    public AssetClass classify(UUID tenantId, String symbol) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");

        return resolveExplicitClass(tenantId, symbol).orElse(AssetClass.US_STOCK);
    }

    /**
     * Derives the value-weighted asset-class mix of the account's current holdings.
     *
     * <p>Money-market holdings are always classified as {@link AssetClass#CASH}. Any other
     * symbol with neither a tenant override nor a seed classification falls back to
     * {@link AssetClass#US_STOCK} and is collected into {@code unclassifiedSymbols} so callers
     * can prompt for a manual classification. Holdings with no known price, or with a
     * zero/negative value, do not contribute to the allocation. An account with no priced value
     * (no holdings, or all holdings unpriced/zero) resolves to {@link AssetAllocation#ALL_US}
     * with an empty unclassified set.
     */
    @Transactional(readOnly = true)
    public AllocationResult deriveAllocation(UUID tenantId, UUID accountId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");

        var holdings = holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId);
        if (holdings.isEmpty()) {
            return new AllocationResult(AssetAllocation.ALL_US, Set.of());
        }

        var latestPrices = latestPricesBySymbol(holdings);
        var valueByClass = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
        var unclassified = new LinkedHashSet<String>();
        var total = BigDecimal.ZERO;

        for (var holding : holdings) {
            var price = latestPrices.get(holding.getSymbol());
            if (price == null) {
                continue;
            }
            var value = holding.getQuantity().multiply(price);
            if (value.signum() <= 0) {
                continue;
            }

            var assetClass = classifyHolding(tenantId, holding, unclassified);
            valueByClass.merge(assetClass, value, BigDecimal::add);
            total = total.add(value);
        }

        if (total.signum() == 0) {
            return new AllocationResult(AssetAllocation.ALL_US, Set.of());
        }
        return new AllocationResult(new AssetAllocation(valueByClass), Collections.unmodifiableSet(unclassified));
    }

    private AssetClass classifyHolding(UUID tenantId, HoldingEntity holding, Set<String> unclassified) {
        if (holding.isMoneyMarket()) {
            return AssetClass.CASH;
        }
        var explicit = resolveExplicitClass(tenantId, holding.getSymbol());
        if (explicit.isEmpty()) {
            unclassified.add(holding.getSymbol());
        }
        return explicit.orElse(AssetClass.US_STOCK);
    }

    private Optional<AssetClass> resolveExplicitClass(UUID tenantId, String symbol) {
        var override = overrideRepository.findByTenantIdAndSymbol(tenantId, symbol);
        if (override.isPresent()) {
            return Optional.of(AssetClass.fromKey(override.get().getAssetClass()));
        }
        return seedRepository.findBySymbol(symbol).map(seed -> AssetClass.fromKey(seed.getAssetClass()));
    }

    private Map<String, BigDecimal> latestPricesBySymbol(List<HoldingEntity> holdings) {
        var symbols = holdings.stream().map(HoldingEntity::getSymbol).distinct().toList();
        return priceRepository.findLatestBySymbolIn(symbols).stream()
                .collect(Collectors.toMap(PriceEntity::getSymbol, PriceEntity::getClosePrice));
    }

    /**
     * The value-weighted asset mix for an account, plus any symbols that fell back to the
     * {@link AssetClass#US_STOCK} default because they have neither a tenant override nor a
     * seed classification.
     */
    public record AllocationResult(AssetAllocation allocation, Set<String> unclassifiedSymbols) {
    }
}
