package com.wealthview.core.holding.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HoldingResponse#from(HoldingEntity, BigDecimal)} is where a holding's market value and
 * gain/loss are actually computed — the two figures the portfolio and dashboard views are built
 * from — yet the whole computation block was uncovered (the package sat at 16.7% branch).
 *
 * <p>The interesting part is not the happy path but the absence rules. Price and cost basis are
 * independently optional, and the DTO's contract is "null when not derivable" rather than zero:
 * a holding with no price must report an ABSENT market value, not $0.00, because a zero would be
 * summed into portfolio totals as a real number and silently understate the portfolio.
 */
class HoldingResponseTest {

    private static final BigDecimal QUANTITY = new BigDecimal("10");
    private static final BigDecimal COST_BASIS = new BigDecimal("1500");

    private static HoldingEntity holding(BigDecimal quantity, BigDecimal costBasis) {
        var tenant = new TenantEntity("Test Tenant");
        var account = new AccountEntity(tenant, "Brokerage", "taxable", "Fidelity");
        return new HoldingEntity(account, tenant, "AAPL", quantity, costBasis);
    }

    @Test
    void from_withPriceAndCostBasis_computesMarketValueAndGainLoss() {
        var response = HoldingResponse.from(holding(QUANTITY, COST_BASIS), new BigDecimal("185.50"));

        assertThat(response.currentPrice()).isEqualByComparingTo("185.50");
        assertThat(response.marketValue())
                .as("10 shares x 185.50")
                .isEqualByComparingTo("1855.00");
        assertThat(response.gainLoss())
                .as("market value less cost basis, not a percentage")
                .isEqualByComparingTo("355.00");
    }

    @Test
    void from_withPriceBelowCostBasis_reportsANegativeGainLoss() {
        var response = HoldingResponse.from(holding(QUANTITY, COST_BASIS), new BigDecimal("120.00"));

        assertThat(response.marketValue()).isEqualByComparingTo("1200.00");
        assertThat(response.gainLoss()).isEqualByComparingTo("-300.00");
    }

    @Test
    void from_marketValue_isRoundedToTheMoneyScale() {
        // 3 x 10.33335 = 31.000050 -> HALF_UP at 4dp -> 31.0001
        var response = HoldingResponse.from(
                holding(new BigDecimal("3"), null), new BigDecimal("10.33335"));

        assertThat(response.marketValue()).isEqualByComparingTo("31.0001");
        assertThat(response.marketValue().scale())
                .as("money values carry the shared 4dp scale, not the multiplication's natural scale")
                .isEqualTo(4);
    }

    // === absence rules ===

    @Test
    void from_withoutAPrice_leavesMarketValueAndGainLossAbsentRatherThanZero() {
        var response = HoldingResponse.from(holding(QUANTITY, COST_BASIS), null);

        assertThat(response.currentPrice()).isNull();
        assertThat(response.marketValue())
                .as("a zero here would be summed into portfolio totals as a real value")
                .isNull();
        assertThat(response.gainLoss()).isNull();
    }

    @Test
    void from_singleArgOverload_behavesAsIfNoPriceWereKnown() {
        var response = HoldingResponse.from(holding(QUANTITY, COST_BASIS));

        assertThat(response.currentPrice()).isNull();
        assertThat(response.marketValue()).isNull();
        assertThat(response.gainLoss()).isNull();
    }

    @Test
    void from_withoutAQuantity_leavesMarketValueAbsentEvenWhenPriced() {
        var response = HoldingResponse.from(holding(null, COST_BASIS), new BigDecimal("185.50"));

        assertThat(response.quantity()).isNull();
        assertThat(response.marketValue()).isNull();
        assertThat(response.gainLoss()).isNull();
    }

    @Test
    void from_withoutACostBasis_stillReportsMarketValueButNoGainLoss() {
        // Imported positions frequently arrive without a basis; the position is still worth
        // something, but the gain is genuinely unknowable rather than zero.
        var response = HoldingResponse.from(holding(QUANTITY, null), new BigDecimal("185.50"));

        assertThat(response.marketValue()).isEqualByComparingTo("1855.00");
        assertThat(response.gainLoss()).isNull();
    }

    @Test
    void from_copiesTheIdentifyingAndFlagFieldsFromTheEntity() {
        var entity = holding(QUANTITY, COST_BASIS);
        entity.setManualOverride(true);
        entity.setMoneyMarket(true);
        entity.setMoneyMarketRate(new BigDecimal("0.0425"));
        entity.setAsOfDate(LocalDate.of(2026, 3, 5));

        var response = HoldingResponse.from(entity, new BigDecimal("1.00"));

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.accountId()).isEqualTo(entity.getAccountId());
        assertThat(response.quantity()).isEqualByComparingTo(QUANTITY);
        assertThat(response.costBasis()).isEqualByComparingTo(COST_BASIS);
        assertThat(response.isManualOverride()).isTrue();
        assertThat(response.isMoneyMarket()).isTrue();
        assertThat(response.moneyMarketRate()).isEqualByComparingTo("0.0425");
        assertThat(response.asOfDate()).isEqualTo(LocalDate.of(2026, 3, 5));
    }
}
