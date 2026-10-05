package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.YearTaxPicture;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Phase 1a (spec §1.2): derives one year's "tax space" from its realized {@link YearTaxPicture}.
 * Effective marginal rates come from re-pricing the year with a +$1,000 ordinary or +$1,000 LTCG probe
 * through the SAME calculators the engine uses: Social Security inclusion is recomputed from the new
 * provisional income and LTCG is re-stacked, so the torpedo and the 0%-to-15% push fall out naturally.
 * "Total tax" = ordinary federal + state (via the run's {@link TaxCalculationStrategy}) + LTCG bracket tax
 * + NIIT. IRMAA is a cliff, reported as a tier distance and dollar jump, never blended into a rate.
 */
@Component
public class TaxSpaceCalculator {

    private static final BigDecimal PROBE = new BigDecimal("1000");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final int RATE_SCALE = 4;
    private static final int INCLUSION_SCALE = 2;
    private static final int IRMAA_LOOKBACK_YEARS = 2;

    private final FederalTaxCalculator federal;
    private final CapitalGainsTaxCalculator capitalGains;
    @Nullable
    private final IrmaaSurchargeCalculator irmaa;
    private final SocialSecurityTaxCalculator socialSecurity = new SocialSecurityTaxCalculator();

    public TaxSpaceCalculator(FederalTaxCalculator federal, CapitalGainsTaxCalculator capitalGains,
                              @Nullable IrmaaSurchargeCalculator irmaa) {
        this.federal = federal;
        this.capitalGains = capitalGains;
        this.irmaa = irmaa;
    }

    public TaxSpaceYear compute(YearTaxPicture p, TaxCalculationStrategy strategy) {
        var base = evaluate(p, strategy, BigDecimal.ZERO, BigDecimal.ZERO);
        var plusOrdinary = evaluate(p, strategy, PROBE, BigDecimal.ZERO);
        var plusLtcg = evaluate(p, strategy, BigDecimal.ZERO, PROBE);
        BigDecimal marginalRate = marginalOrdinaryRate(p, base);
        var ltcgRoom = capitalGains.ltcgBandRoom(base.ordinaryTaxable(), p.qualifiedDividendsAndLtcg(),
                p.year(), p.filingStatus());
        var ss = socialSecuritySection(p);
        var irmaaSection = irmaaSection(p);
        BigDecimal niitHeadroom = capitalGains.niitThresholdReal(p.filingStatus(), p.yearsFromBase(),
                p.inflationRate()).subtract(p.magi());
        return new TaxSpaceYear(p.year(), p.primaryAge(), scale(p.magi()), marginalRate,
                bracketRoom(p, strategy, base.grossOrdinary(), marginalRate),
                scale(ltcgRoom.zeroRoom()), scale(ltcgRoom.fifteenRoom()),
                ss.provisionalIncome(), ss.base(), ss.upper(), ss.inclusionRate(),
                scale(niitHeadroom),
                irmaaSection.premiumYear(), irmaaSection.tier(), irmaaSection.room(), irmaaSection.nextCost(),
                rate(plusOrdinary.totalTax().subtract(base.totalTax())),
                rate(plusLtcg.totalTax().subtract(base.totalTax())));
    }

    private record Evaluation(BigDecimal grossOrdinary, BigDecimal deduction, BigDecimal ordinaryTaxable,
                              BigDecimal totalTax) {
    }

    private Evaluation evaluate(YearTaxPicture p, TaxCalculationStrategy strategy,
                                BigDecimal extraOrdinary, BigDecimal extraLtcg) {
        BigDecimal ssTaxable = socialSecurityTaxable(p, extraOrdinary.add(extraLtcg));
        BigDecimal grossOrdinary = nonSocialSecurityOrdinary(p).add(extraOrdinary).add(ssTaxable);
        BigDecimal ltcg = p.qualifiedDividendsAndLtcg().add(extraLtcg);
        var detail = strategy.computeDetailedTax(grossOrdinary, p.year(), p.filingStatus(), ltcg, ssTaxable);
        BigDecimal deduction = detail.usedItemized() ? detail.itemizedDeductions() : standardDeduction(p, strategy);
        BigDecimal ordinaryTaxable = grossOrdinary.subtract(deduction).max(BigDecimal.ZERO);
        BigDecimal ltcgTax = capitalGains.computeLtcgTax(ordinaryTaxable, ltcg, p.year(), p.filingStatus(),
                p.yearsFromBase(), p.inflationRate(), grossOrdinary.add(ltcg), p.netRentalIncome());
        return new Evaluation(grossOrdinary, deduction, ordinaryTaxable, detail.totalTax().add(ltcgTax));
    }

    /**
     * The non-itemized deduction: the run strategy's own age-aware amount (the one its ordinary tax
     * subtracts), falling back to a picture-age lookup only for strategies with no deduction concept.
     */
    private BigDecimal standardDeduction(YearTaxPicture p, TaxCalculationStrategy strategy) {
        return strategy.standardDeduction(p.year(), p.filingStatus()).orElseGet(() -> {
            Integer secondAge = p.filingStatus() == FilingStatus.MARRIED_FILING_JOINTLY ? p.spouseAge() : null;
            return federal.loadStandardDeduction(p.year(), p.filingStatus(), p.primaryAge(), secondAge);
        });
    }

    private static BigDecimal nonSocialSecurityOrdinary(YearTaxPicture p) {
        return p.ordinaryIncomeExSocialSecurity().add(p.traditionalDistributions())
                .add(p.rothConversion()).add(p.ordinaryInterest());
    }

    /** IRS provisional income before the half-benefit: every AGI item except Social Security itself. */
    private static BigDecimal provisionalExSocialSecurity(YearTaxPicture p) {
        return nonSocialSecurityOrdinary(p).add(p.qualifiedDividendsAndLtcg());
    }

    private BigDecimal socialSecurityTaxable(YearTaxPicture p, BigDecimal extraProvisional) {
        if (p.socialSecurityBenefit().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return socialSecurity.computeTaxableAmount(p.socialSecurityBenefit(),
                provisionalExSocialSecurity(p).add(extraProvisional), p.filingStatus().value(),
                p.yearsFromBase(), p.inflationRate());
    }

    private BigDecimal marginalOrdinaryRate(YearTaxPicture p, Evaluation base) {
        if (base.grossOrdinary().compareTo(base.deduction()) < 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE);
        }
        for (var bracket : federal.loadOrdinaryBrackets(p.year(), p.filingStatus())) {
            if (bracket.ceiling() == null || base.ordinaryTaxable().compareTo(bracket.ceiling()) < 0) {
                return bracket.rate().setScale(RATE_SCALE, ROUNDING);
            }
        }
        return BigDecimal.ZERO.setScale(RATE_SCALE);
    }

    private List<TaxSpaceYear.BracketRoom> bracketRoom(YearTaxPicture p, TaxCalculationStrategy strategy,
                                                       BigDecimal grossOrdinary, BigDecimal marginalRate) {
        var rooms = new ArrayList<TaxSpaceYear.BracketRoom>();
        for (var bracket : federal.loadOrdinaryBrackets(p.year(), p.filingStatus())) {
            if (bracket.ceiling() == null || bracket.rate().compareTo(marginalRate) < 0) {
                continue;
            }
            BigDecimal grossCeiling = strategy.computeGrossCeilingForRate(bracket.rate(), p.year(),
                    p.filingStatus());
            rooms.add(new TaxSpaceYear.BracketRoom(bracket.rate().setScale(RATE_SCALE, ROUNDING),
                    scale(grossCeiling), scale(grossCeiling.subtract(grossOrdinary).max(BigDecimal.ZERO))));
        }
        return List.copyOf(rooms);
    }

    private record SsSection(@Nullable BigDecimal provisionalIncome, @Nullable BigDecimal base,
                             @Nullable BigDecimal upper, @Nullable BigDecimal inclusionRate) {
        static final SsSection NONE = new SsSection(null, null, null, null);
    }

    private SsSection socialSecuritySection(YearTaxPicture p) {
        if (p.socialSecurityBenefit().signum() <= 0) {
            return SsSection.NONE;
        }
        BigDecimal provisional = provisionalExSocialSecurity(p).add(p.socialSecurityBenefit().multiply(HALF));
        var thresholds = socialSecurity.thresholds(p.filingStatus(), p.yearsFromBase(), p.inflationRate());
        // Inclusion per extra $1 of income: 0, 0.50 or 0.85 (0 again once the 50% / 85% cap binds).
        BigDecimal inclusion = socialSecurityTaxable(p, BigDecimal.ONE)
                .subtract(socialSecurityTaxable(p, BigDecimal.ZERO))
                .setScale(INCLUSION_SCALE, ROUNDING);
        return new SsSection(scale(provisional), thresholds.base(), thresholds.upper(), inclusion);
    }

    private record IrmaaSection(@Nullable Integer premiumYear, @Nullable Integer tier,
                                @Nullable BigDecimal room, @Nullable BigDecimal nextCost) {
        static final IrmaaSection NONE = new IrmaaSection(null, null, null, null);
    }

    private IrmaaSection irmaaSection(YearTaxPicture p) {
        if (irmaa == null || p.medicareCountInPremiumYear() <= 0) {
            return IrmaaSection.NONE;
        }
        int premiumYear = p.year() + IRMAA_LOOKBACK_YEARS;
        var tiers = irmaa.loadTiers(premiumYear, p.filingStatus());
        if (tiers.isEmpty()) {
            return IrmaaSection.NONE;
        }
        int index = tierIndex(tiers, p.magi());
        var current = tiers.get(index);
        BigDecimal ceiling = current.magiCeiling();
        if (ceiling == null || index == tiers.size() - 1) {
            return new IrmaaSection(premiumYear, index, null, null);
        }
        var next = tiers.get(index + 1);
        BigDecimal enrollees = BigDecimal.valueOf(p.medicareCountInPremiumYear());
        return new IrmaaSection(premiumYear, index,
                scale(ceiling.subtract(p.magi())),
                scale(next.annualSurchargePerPerson().subtract(current.annualSurchargePerPerson())
                        .multiply(enrollees)));
    }

    /** First tier whose ceiling is at or above {@code magi} ("up to and including" semantics). */
    private static int tierIndex(List<IrmaaTier> tiers, BigDecimal magi) {
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal ceiling = tiers.get(i).magiCeiling();
            if (ceiling == null || magi.compareTo(ceiling) <= 0) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    private static BigDecimal rate(BigDecimal taxDelta) {
        return taxDelta.divide(PROBE, RATE_SCALE, ROUNDING);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }
}
