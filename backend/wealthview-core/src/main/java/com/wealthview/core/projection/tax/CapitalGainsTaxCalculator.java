package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.LtcgBracketEntity;
import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Computes federal tax on long-term capital gains (realized FIFO gains + qualified dividends),
 * STACKED on top of ordinary taxable income against the 0%/15%/20% LTCG brackets, plus the 3.8%
 * Net Investment Income Tax (NIIT) surtax.
 *
 * <p>Real-terms projection: the LTCG brackets are IRS-indexed to inflation, so in constant-dollar
 * (real) terms they are used at their seeded base-year values with no further adjustment — mirrors
 * {@link FederalTaxCalculator}'s bracket loading/caching/fallback approach. The NIIT thresholds
 * ($200k single / $250k MFJ), by contrast, are statutorily FIXED NOMINAL (never indexed), so in
 * real terms they erode over the projection horizon: each is deflated by
 * {@code 1/(1+inflationRate)^yearsFromBase}, mirroring {@link SocialSecurityTaxCalculator}.
 */
@Component
public class CapitalGainsTaxCalculator {

    private static final BigDecimal NIIT_RATE = new BigDecimal("0.038");
    private static final BigDecimal NIIT_THRESHOLD_SINGLE = new BigDecimal("200000");
    private static final BigDecimal NIIT_THRESHOLD_MFJ = new BigDecimal("250000");

    private final LtcgBracketRepository ltcgBracketRepository;
    // ConcurrentHashMap: this singleton is shared by concurrent projection requests, which
    // populate the cache via computeIfAbsent from multiple threads.
    private final Map<String, List<LtcgBracketEntity>> bracketCache = new ConcurrentHashMap<>();

    public CapitalGainsTaxCalculator(LtcgBracketRepository ltcgBracketRepository) {
        this.ltcgBracketRepository = ltcgBracketRepository;
    }

    /**
     * Computes the tax owed on LTCG income, stacked on top of ordinary income, plus NIIT.
     *
     * @param ordinaryTaxableIncome ordinary (non-LTCG) taxable income; fills the LTCG brackets first
     * @param ltcgIncome            realized long-term capital gains + qualified dividends
     * @param taxYear               LTCG bracket year to load (falls back to the latest seeded year)
     * @param status                filing status; selects the bracket set and the NIIT threshold
     * @param yearsFromBase         years past the projection base year, for NIIT threshold deflation
     * @param inflationRate         annual real-terms inflation rate used to deflate the NIIT threshold
     * @param magi                  modified adjusted gross income, for the NIIT threshold comparison
     */
    public BigDecimal computeLtcgTax(BigDecimal ordinaryTaxableIncome, BigDecimal ltcgIncome, int taxYear,
                                      FilingStatus status, int yearsFromBase, BigDecimal inflationRate,
                                      BigDecimal magi) {
        if (ltcgIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ordinary = ordinaryTaxableIncome.max(BigDecimal.ZERO);

        var brackets = loadBracketsWithFallback(taxYear, status);
        BigDecimal bracketTax = stackOnBrackets(ordinary, ltcgIncome, brackets);
        BigDecimal niit = computeNiit(ltcgIncome, magi, status, yearsFromBase, inflationRate);

        return bracketTax.add(niit).setScale(SCALE, ROUNDING);
    }

    public void clearCache() {
        bracketCache.clear();
    }

    /**
     * Returns the LTCG brackets for {@code (taxYear, status)}, floor-ascending, using the SAME
     * year-fallback {@link #computeLtcgTax} applies internally ({@link #loadBracketsWithFallback}).
     * A {@code null} ceiling on the last entry is the top, uncapped bracket.
     *
     * <p>Exposed for the Monte Carlo engine's per-year exact-tax precompute (audit C5), which
     * builds an allocation-free primitive-array lookup table from this raw data instead of
     * repeating BigDecimal {@link #computeLtcgTax} calls inside the hot trial loop.
     */
    public List<BracketPoint> loadLtcgBrackets(int taxYear, FilingStatus status) {
        return loadBracketsWithFallback(taxYear, status).stream()
                .map(b -> new BracketPoint(b.getBracketFloor(), b.getBracketCeiling(), b.getRate()))
                .toList();
    }

    /**
     * Deflates the fixed-nominal NIIT threshold for {@code status} onto the projection's
     * real-terms clock -- the SAME deflation {@link #computeLtcgTax} applies internally via
     * {@link #thresholdDeflator}.
     *
     * <p>Exposed for the Monte Carlo engine's per-year exact-tax precompute (audit C5), which
     * needs the deflated threshold once per year (outside the hot loop) to evaluate NIIT with
     * primitive arithmetic instead of repeating BigDecimal {@link #computeLtcgTax} calls.
     */
    public BigDecimal niitThresholdReal(FilingStatus status, int yearsFromBase, BigDecimal inflationRate) {
        BigDecimal threshold = status == FilingStatus.MARRIED_FILING_JOINTLY
                ? NIIT_THRESHOLD_MFJ : NIIT_THRESHOLD_SINGLE;
        BigDecimal deflator = thresholdDeflator(yearsFromBase, inflationRate);
        return deflator.compareTo(BigDecimal.ONE) != 0
                ? threshold.multiply(deflator).setScale(SCALE, ROUNDING)
                : threshold;
    }

    /**
     * Walks the floor-ascending LTCG brackets, taxing the overlap of
     * {@code [max(bracketFloor, ordinary + gainTaxedSoFar), bracketCeiling]} with the remaining gain
     * at each bracket's rate — i.e. the LTCG amount stacks on top of ordinary income.
     */
    private BigDecimal stackOnBrackets(BigDecimal ordinary, BigDecimal ltcgIncome, List<LtcgBracketEntity> brackets) {
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal remainingGain = ltcgIncome;
        BigDecimal gainTaxedSoFar = BigDecimal.ZERO;
        for (var bracket : brackets) {
            if (remainingGain.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal stackedFloor = ordinary.add(gainTaxedSoFar).max(bracket.getBracketFloor());
            BigDecimal capacity = bracket.getBracketCeiling() != null
                    ? bracket.getBracketCeiling().subtract(stackedFloor)
                    : remainingGain;
            if (capacity.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal taxedInBracket = remainingGain.min(capacity);
            totalTax = totalTax.add(taxedInBracket.multiply(bracket.getRate()));
            remainingGain = remainingGain.subtract(taxedInBracket);
            gainTaxedSoFar = gainTaxedSoFar.add(taxedInBracket);
        }
        return totalTax;
    }

    /** NIIT = 3.8% x max(0, min(ltcgIncome, magi - deflatedThreshold)). */
    private BigDecimal computeNiit(BigDecimal ltcgIncome, BigDecimal magi, FilingStatus status, int yearsFromBase,
                                    BigDecimal inflationRate) {
        BigDecimal threshold = status == FilingStatus.MARRIED_FILING_JOINTLY
                ? NIIT_THRESHOLD_MFJ : NIIT_THRESHOLD_SINGLE;
        BigDecimal deflator = thresholdDeflator(yearsFromBase, inflationRate);
        if (deflator.compareTo(BigDecimal.ONE) != 0) {
            threshold = threshold.multiply(deflator).setScale(SCALE, ROUNDING);
        }
        BigDecimal excess = magi.subtract(threshold);
        BigDecimal niitBase = ltcgIncome.min(excess).max(BigDecimal.ZERO);
        return niitBase.multiply(NIIT_RATE);
    }

    private static BigDecimal thresholdDeflator(int yearsFromBase, BigDecimal inflationRate) {
        if (yearsFromBase <= 0 || inflationRate == null || inflationRate.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal growth = BigDecimal.ONE.add(inflationRate).pow(yearsFromBase);
        return BigDecimal.ONE.divide(growth, SCALE + 6, ROUNDING);
    }

    private List<LtcgBracketEntity> loadBrackets(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return bracketCache.computeIfAbsent(key,
                k -> ltcgBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                        taxYear, status.value()));
    }

    private List<LtcgBracketEntity> loadBracketsWithFallback(int taxYear, FilingStatus status) {
        var brackets = loadBrackets(taxYear, status);
        if (brackets.isEmpty()) {
            Integer maxYear = ltcgBracketRepository.findMaxTaxYear();
            if (maxYear != null) {
                brackets = loadBrackets(maxYear, status);
            }
        }
        return brackets;
    }
}
