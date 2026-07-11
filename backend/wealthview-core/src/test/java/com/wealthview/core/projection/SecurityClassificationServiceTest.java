package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.SecurityAssetClassEntity;
import com.wealthview.persistence.entity.SecurityClassOverrideEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.SecurityAssetClassRepository;
import com.wealthview.persistence.repository.SecurityClassOverrideRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityClassificationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock
    private SecurityAssetClassRepository seedRepository;

    @Mock
    private SecurityClassOverrideRepository overrideRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private SecurityClassificationService service;

    private UUID tenantId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    @Test
    void setOverride_newSymbol_persistsUpperCaseClass() {
        when(overrideRepository.findByTenantIdAndSymbol(TENANT, "SPAXX")).thenReturn(Optional.empty());

        var result = service.setOverride(TENANT, "SPAXX", AssetClass.CASH);

        assertThat(result).isEqualTo(AssetClass.CASH);
        verify(overrideRepository).save(argThat(e ->
                e.getSymbol().equals("SPAXX") && e.getAssetClass().equals("cash") && e.getTenantId().equals(TENANT)));
    }

    @Test
    void setOverride_existingSymbol_updatesInPlace() {
        var existing = new SecurityClassOverrideEntity(TENANT, "SPAXX", "us_stock");
        when(overrideRepository.findByTenantIdAndSymbol(TENANT, "SPAXX")).thenReturn(Optional.of(existing));

        service.setOverride(TENANT, "SPAXX", AssetClass.CASH);

        assertThat(existing.getAssetClass()).isEqualTo("cash");
        verify(overrideRepository).save(existing);
    }

    @Test
    void classify_tenantOverrideExists_beatsSeed() {
        when(overrideRepository.findByTenantIdAndSymbol(tenantId, "BND"))
                .thenReturn(Optional.of(new SecurityClassOverrideEntity(tenantId, "BND", "us_stock")));

        var result = service.classify(tenantId, "BND");

        assertThat(result).isEqualTo(AssetClass.US_STOCK);
        verify(seedRepository, never()).findBySymbol(any());
    }

    @Test
    void classify_noOverride_fallsBackToSeed() {
        when(overrideRepository.findByTenantIdAndSymbol(tenantId, "BND")).thenReturn(Optional.empty());
        when(seedRepository.findBySymbol("BND"))
                .thenReturn(Optional.of(new SecurityAssetClassEntity("BND", "bond")));

        var result = service.classify(tenantId, "BND");

        assertThat(result).isEqualTo(AssetClass.BOND);
    }

    @Test
    void classify_noOverrideNoSeed_defaultsToUsStock() {
        when(overrideRepository.findByTenantIdAndSymbol(eq(tenantId), any())).thenReturn(Optional.empty());
        when(seedRepository.findBySymbol("ZZZZ")).thenReturn(Optional.empty());

        var result = service.classify(tenantId, "ZZZZ");

        assertThat(result).isEqualTo(AssetClass.US_STOCK);
    }

    @Test
    void deriveAllocation_noHoldings_returnsAllUsWithEmptyUnclassifiedSet() {
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId)).thenReturn(List.of());

        var result = service.deriveAllocation(tenantId, accountId);

        assertThat(result.allocation().weights()).containsOnlyKeys(AssetClass.US_STOCK);
        assertThat(result.allocation().weights().get(AssetClass.US_STOCK)).isEqualByComparingTo("1");
        assertThat(result.unclassifiedSymbols()).isEmpty();
    }

    @Test
    void deriveAllocation_valueWeightsHoldingsAndFlagsUnknownSymbol() {
        // $6000 BND (seeded as bond), $4000 ZZZZ (no override/seed -> us_stock, flagged unclassified)
        var bnd = holding("BND", "60");
        var zzzz = holding("ZZZZ", "40");
        var bndPrice = price("BND", "100");
        var zzzzPrice = price("ZZZZ", "100");
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(bnd, zzzz));
        when(priceRepository.findLatestBySymbolIn(anyList())).thenReturn(List.of(bndPrice, zzzzPrice));
        when(overrideRepository.findByTenantIdAndSymbol(eq(tenantId), any())).thenReturn(Optional.empty());
        when(seedRepository.findBySymbol("BND"))
                .thenReturn(Optional.of(new SecurityAssetClassEntity("BND", "bond")));
        when(seedRepository.findBySymbol("ZZZZ")).thenReturn(Optional.empty());

        var result = service.deriveAllocation(tenantId, accountId);

        assertThat(result.allocation().weights().get(AssetClass.BOND)).isEqualByComparingTo("0.6");
        assertThat(result.allocation().weights().get(AssetClass.US_STOCK)).isEqualByComparingTo("0.4");
        assertThat(result.unclassifiedSymbols()).containsExactly("ZZZZ");
    }

    @Test
    void deriveAllocation_moneyMarketHolding_classifiesAsCashAndNotFlaggedUnclassified() {
        var spaxx = moneyMarketHolding("SPAXX", "500");
        var spaxxPrice = price("SPAXX", "1");
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId)).thenReturn(List.of(spaxx));
        when(priceRepository.findLatestBySymbolIn(anyList())).thenReturn(List.of(spaxxPrice));

        var result = service.deriveAllocation(tenantId, accountId);

        assertThat(result.allocation().weights()).containsOnlyKeys(AssetClass.CASH);
        assertThat(result.unclassifiedSymbols()).isEmpty();
        verify(overrideRepository, never()).findByTenantIdAndSymbol(any(), any());
        verify(seedRepository, never()).findBySymbol(any());
    }

    @Test
    void deriveAllocation_moneyMarketHoldingWithNoPriceRow_classifiesAsCashUsingQuantity() {
        // SPAXX (and other money-market sweep funds) have no row in the prices table at all --
        // findLatestBySymbolIn() returns nothing for them. That must NOT cause the holding to be
        // dropped: money-market holdings are valued at their quantity (stable $1.00 NAV) and
        // classified as CASH regardless of price data.
        var spaxx = moneyMarketHolding("SPAXX", "500");
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId)).thenReturn(List.of(spaxx));
        when(priceRepository.findLatestBySymbolIn(anyList())).thenReturn(List.of());

        var result = service.deriveAllocation(tenantId, accountId);

        assertThat(result.allocation().weights()).containsOnlyKeys(AssetClass.CASH);
        assertThat(result.allocation().weights().get(AssetClass.CASH)).isEqualByComparingTo("1");
        assertThat(result.unclassifiedSymbols()).isEmpty();
        verify(overrideRepository, never()).findByTenantIdAndSymbol(any(), any());
        verify(seedRepository, never()).findBySymbol(any());
    }

    @Test
    void deriveAllocation_holdingWithNoPrice_excludedFromAllocationTotal() {
        var unpriced = unpricedHolding("UNPRICED");
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId)).thenReturn(List.of(unpriced));
        when(priceRepository.findLatestBySymbolIn(anyList())).thenReturn(List.of());

        var result = service.deriveAllocation(tenantId, accountId);

        assertThat(result.allocation().weights()).containsOnlyKeys(AssetClass.US_STOCK);
        assertThat(result.allocation().weights().get(AssetClass.US_STOCK)).isEqualByComparingTo("1");
        assertThat(result.unclassifiedSymbols()).isEmpty();
    }

    private static HoldingEntity holding(String symbol, String qty) {
        var h = mock(HoldingEntity.class);
        when(h.getSymbol()).thenReturn(symbol);
        when(h.getQuantity()).thenReturn(new BigDecimal(qty));
        return h;
    }

    /**
     * A holding whose symbol has no entry in the latest-price map. The service short-circuits
     * before reading quantity, so only {@code getSymbol()} is stubbed here (stubbing quantity
     * too would trip Mockito's strict unnecessary-stubbing check).
     */
    private static HoldingEntity unpricedHolding(String symbol) {
        var h = mock(HoldingEntity.class);
        when(h.getSymbol()).thenReturn(symbol);
        return h;
    }

    private static HoldingEntity moneyMarketHolding(String symbol, String qty) {
        var h = mock(HoldingEntity.class);
        when(h.getSymbol()).thenReturn(symbol);
        when(h.getQuantity()).thenReturn(new BigDecimal(qty));
        when(h.isMoneyMarket()).thenReturn(true);
        return h;
    }

    private static PriceEntity price(String symbol, String closePrice) {
        return new PriceEntity(symbol, LocalDate.now(), new BigDecimal(closePrice), "test");
    }
}
