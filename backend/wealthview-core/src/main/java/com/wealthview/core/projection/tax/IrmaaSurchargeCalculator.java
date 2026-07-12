package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.IrmaaTierEntity;
import com.wealthview.persistence.repository.IrmaaTierRepository;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Computes the ANNUAL Medicare IRMAA (Income-Related Monthly Adjustment Amount) surcharge --
 * Part B + Part D combined, x12 -- for a given MAGI, filing status, and tax year.
 *
 * <p>Real-terms projection: like {@link FederalTaxCalculator} and {@link CapitalGainsTaxCalculator},
 * IRMAA MAGI tiers are (mostly) IRS/CMS-indexed to inflation, so this engine treats the seeded
 * base-year (2025) dollar thresholds AND dollar surcharge amounts as constant-real and reuses them
 * for every future year via {@link #loadTiersWithFallback}'s year-fallback -- no further deflation
 * is applied, mirroring the LTCG-bracket and standard-deduction precedent. One documented wrinkle:
 * the TOP tier's $500,000 (single) / $750,000 (MFJ) threshold is statutorily FROZEN in nominal
 * terms through 2027 (resumes CPI-indexing in 2028) -- by treating it as constant-real like every
 * other tier, this model is slightly optimistic for top-tier retirees in the next few years (their
 * real threshold is actually shrinking slightly until 2028), a small, short-lived, and documented
 * simplification consistent with how every other bracket/threshold in this codebase is modeled.
 *
 * <p>The per-COVERED-PERSON multiplier is deliberately NOT applied here (always x1, "primary
 * filer only") -- this engine tracks only the primary filer's age/Medicare status, the same
 * simplification {@code StandardDeductionEntity.additionalAge65} documents for the MFJ age-65
 * deduction adder. A married couple where both spouses are 65+ and both enrolled in Medicare would
 * owe this surcharge TWICE (once per spouse); this model reports it once. See
 * {@code DeterministicProjectionEngine}'s IRMAA orchestration for the 2-year MAGI lookback that
 * feeds this calculator's {@code magi} argument.
 */
@Component
public class IrmaaSurchargeCalculator {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private final IrmaaTierRepository irmaaTierRepository;
    // ConcurrentHashMap: this singleton is shared by concurrent projection requests, which
    // populate the cache via computeIfAbsent from multiple threads.
    private final Map<String, List<IrmaaTierEntity>> tierCache = new ConcurrentHashMap<>();

    public IrmaaSurchargeCalculator(IrmaaTierRepository irmaaTierRepository) {
        this.irmaaTierRepository = irmaaTierRepository;
    }

    /**
     * Returns the annual (12-month) IRMAA surcharge for the given MAGI -- zero when {@code magi}
     * is {@code null}, non-positive, or falls in the below-threshold (no-surcharge) tier.
     *
     * @param magi     modified adjusted gross income for the LOOKBACK year (2 years prior to the
     *                 premium year, per the statutory lookback) -- see the class javadoc for what
     *                 "MAGI" approximates in this engine
     * @param taxYear  the premium year's tier table to load (falls back to the latest seeded year)
     * @param status   filing status; selects which MAGI bracket boundaries apply
     */
    public BigDecimal computeAnnualSurcharge(@Nullable BigDecimal magi, int taxYear, FilingStatus status) {
        if (magi == null || magi.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        var tiers = loadTiersWithFallback(taxYear, status);
        for (var tier : tiers) {
            if (inTier(magi, tier)) {
                BigDecimal monthly = tier.getPartBSurcharge().add(tier.getPartDSurcharge());
                return monthly.multiply(MONTHS_PER_YEAR).setScale(SCALE, ROUNDING);
            }
        }
        return BigDecimal.ZERO;
    }

    public void clearCache() {
        tierCache.clear();
    }

    /** Bracket semantics: "greater than floor, up to and including ceiling" (SSA POMS HI 01101.020). */
    private boolean inTier(BigDecimal magi, IrmaaTierEntity tier) {
        boolean aboveFloor = magi.compareTo(tier.getMagiFloor()) > 0;
        boolean atOrBelowCeiling = tier.getMagiCeiling() == null || magi.compareTo(tier.getMagiCeiling()) <= 0;
        return aboveFloor && atOrBelowCeiling;
    }

    private List<IrmaaTierEntity> loadTiers(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return tierCache.computeIfAbsent(key,
                k -> irmaaTierRepository.findByTaxYearAndFilingStatusOrderByMagiFloorAsc(taxYear, status.value()));
    }

    private List<IrmaaTierEntity> loadTiersWithFallback(int taxYear, FilingStatus status) {
        var tiers = loadTiers(taxYear, status);
        if (tiers.isEmpty()) {
            Integer maxYear = irmaaTierRepository.findMaxTaxYear();
            if (maxYear != null) {
                tiers = loadTiers(maxYear, status);
            }
        }
        return tiers;
    }
}
