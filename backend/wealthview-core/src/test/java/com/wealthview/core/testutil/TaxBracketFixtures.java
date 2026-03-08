package com.wealthview.core.testutil;

import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

public final class TaxBracketFixtures {

    private TaxBracketFixtures() {}

    public static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    public static List<TaxBracketEntity> single2025Brackets() {
        return List.of(
                new TaxBracketEntity(2025, "single", bd("0"), bd("11925"), bd("0.1000")),
                new TaxBracketEntity(2025, "single", bd("11925"), bd("48475"), bd("0.1200")),
                new TaxBracketEntity(2025, "single", bd("48475"), bd("103350"), bd("0.2200")),
                new TaxBracketEntity(2025, "single", bd("103350"), bd("197300"), bd("0.2400")),
                new TaxBracketEntity(2025, "single", bd("197300"), bd("250525"), bd("0.3200")),
                new TaxBracketEntity(2025, "single", bd("250525"), bd("626350"), bd("0.3500")),
                new TaxBracketEntity(2025, "single", bd("626350"), null, bd("0.3700")));
    }

    public static List<TaxBracketEntity> mfj2025Brackets() {
        return List.of(
                new TaxBracketEntity(2025, "married_filing_jointly", bd("0"), bd("23850"), bd("0.1000")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("23850"), bd("96950"), bd("0.1200")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("96950"), bd("206700"), bd("0.2200")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("206700"), bd("394600"), bd("0.2400")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("394600"), bd("501050"), bd("0.3200")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("501050"), bd("751600"), bd("0.3500")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("751600"), null, bd("0.3700")));
    }

    public static List<TaxBracketEntity> single2022Brackets() {
        return List.of(
                new TaxBracketEntity(2022, "single", bd("0"), bd("10275"), bd("0.1000")),
                new TaxBracketEntity(2022, "single", bd("10275"), bd("41775"), bd("0.1200")),
                new TaxBracketEntity(2022, "single", bd("41775"), bd("89075"), bd("0.2200")),
                new TaxBracketEntity(2022, "single", bd("89075"), bd("170050"), bd("0.2400")),
                new TaxBracketEntity(2022, "single", bd("170050"), bd("215950"), bd("0.3200")),
                new TaxBracketEntity(2022, "single", bd("215950"), bd("539900"), bd("0.3500")),
                new TaxBracketEntity(2022, "single", bd("539900"), null, bd("0.3700")));
    }

    public static StandardDeductionEntity singleDeduction2025() {
        return new StandardDeductionEntity(2025, "single", bd("15000"));
    }

    public static StandardDeductionEntity mfjDeduction2025() {
        return new StandardDeductionEntity(2025, "married_filing_jointly", bd("30000"));
    }

    public static StandardDeductionEntity singleDeduction2022() {
        return new StandardDeductionEntity(2022, "single", bd("12950"));
    }

    public static void stubSingle2025(TaxBracketRepository taxBracketRepo,
                                       StandardDeductionRepository deductionRepo) {
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(singleDeduction2025()));
    }

    public static void stubMfj2025(TaxBracketRepository taxBracketRepo,
                                    StandardDeductionRepository deductionRepo) {
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("married_filing_jointly")))
                .thenReturn(mfj2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("married_filing_jointly")))
                .thenReturn(Optional.of(mfjDeduction2025()));
    }
}
