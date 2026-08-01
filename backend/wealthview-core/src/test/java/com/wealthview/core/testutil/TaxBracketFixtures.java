package com.wealthview.core.testutil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.wealthview.persistence.entity.IrmaaTierEntity;
import com.wealthview.persistence.entity.LtcgBracketEntity;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.IrmaaTierRepository;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

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

    public static List<LtcgBracketEntity> single2025LtcgBrackets() {
        return List.of(
                new LtcgBracketEntity(2025, "single", bd("0"), bd("48350"), bd("0.0000")),
                new LtcgBracketEntity(2025, "single", bd("48350"), bd("533400"), bd("0.1500")),
                new LtcgBracketEntity(2025, "single", bd("533400"), null, bd("0.2000")));
    }

    public static List<LtcgBracketEntity> mfj2025LtcgBrackets() {
        return List.of(
                new LtcgBracketEntity(2025, "married_filing_jointly", bd("0"), bd("96700"), bd("0.0000")),
                new LtcgBracketEntity(2025, "married_filing_jointly", bd("96700"), bd("600050"), bd("0.1500")),
                new LtcgBracketEntity(2025, "married_filing_jointly", bd("600050"), null, bd("0.2000")));
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

    /**
     * 2025 MFJ deduction carrying the REAL per-qualifying-person age-65 adder ($1,600, from
     * R__seed_standard_deductions.sql). {@link #mfjDeduction2025()} leaves the adder at zero, so a
     * test using it cannot tell one qualifying filer from two — use this fixture whenever the
     * behaviour under test is the per-person multiplier itself (household both-65+ deduction,
     * IRMAA-style per-person rules). The 30,000 base is kept identical to
     * {@link #mfjDeduction2025()} so expectations differ only by the adder.
     */
    public static StandardDeductionEntity mfjDeduction2025WithAge65Adder() {
        return new StandardDeductionEntity(2025, "married_filing_jointly", bd("30000"), bd("1600"));
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

    /** Single-filer counterpart to {@link #mfjDeduction2025WithAge65Adder()} — real $2,000 adder,
     * base kept identical to {@link #singleDeduction2025()}. */
    public static StandardDeductionEntity singleDeduction2025WithAge65Adder() {
        return new StandardDeductionEntity(2025, "single", bd("15000"), bd("2000"));
    }

    /** As {@link #stubSingle2025} but with the real age-65 adder seeded. */
    public static void stubSingle2025WithAge65Adder(TaxBracketRepository taxBracketRepo,
                                                     StandardDeductionRepository deductionRepo) {
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(singleDeduction2025WithAge65Adder()));
    }

    /** As {@link #stubMfj2025} but with the real age-65 adder seeded — see
     * {@link #mfjDeduction2025WithAge65Adder()}. */
    public static void stubMfj2025WithAge65Adder(TaxBracketRepository taxBracketRepo,
                                                  StandardDeductionRepository deductionRepo) {
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(),
                        eq("married_filing_jointly")))
                .thenReturn(mfj2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("married_filing_jointly")))
                .thenReturn(Optional.of(mfjDeduction2025WithAge65Adder()));
    }

    public static void stubSingle2025Ltcg(LtcgBracketRepository ltcgBracketRepo) {
        lenient().when(ltcgBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025LtcgBrackets());
    }

    public static void stubMfj2025Ltcg(LtcgBracketRepository ltcgBracketRepo) {
        lenient().when(ltcgBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(),
                        eq("married_filing_jointly")))
                .thenReturn(mfj2025LtcgBrackets());
    }

    /** 2025 IRMAA tiers for single filers -- see R__seed_irmaa_tiers.sql for sources. */
    public static List<IrmaaTierEntity> single2025IrmaaTiers() {
        return List.of(
                new IrmaaTierEntity(2025, "single", bd("0"), bd("106000"), bd("0"), bd("0")),
                new IrmaaTierEntity(2025, "single", bd("106000"), bd("133000"), bd("74.00"), bd("13.70")),
                new IrmaaTierEntity(2025, "single", bd("133000"), bd("167000"), bd("185.00"), bd("35.30")),
                new IrmaaTierEntity(2025, "single", bd("167000"), bd("200000"), bd("295.90"), bd("57.00")),
                new IrmaaTierEntity(2025, "single", bd("200000"), bd("500000"), bd("406.90"), bd("78.60")),
                new IrmaaTierEntity(2025, "single", bd("500000"), null, bd("443.90"), bd("85.80")));
    }

    /** 2025 IRMAA tiers for married-filing-jointly filers -- see R__seed_irmaa_tiers.sql for sources. */
    public static List<IrmaaTierEntity> mfj2025IrmaaTiers() {
        return List.of(
                new IrmaaTierEntity(2025, "married_filing_jointly", bd("0"), bd("212000"), bd("0"), bd("0")),
                new IrmaaTierEntity(2025, "married_filing_jointly", bd("212000"), bd("266000"), bd("74.00"), bd("13.70")),
                new IrmaaTierEntity(2025, "married_filing_jointly", bd("266000"), bd("334000"), bd("185.00"), bd("35.30")),
                new IrmaaTierEntity(2025, "married_filing_jointly", bd("334000"), bd("400000"), bd("295.90"), bd("57.00")),
                new IrmaaTierEntity(2025, "married_filing_jointly", bd("400000"), bd("750000"), bd("406.90"), bd("78.60")),
                new IrmaaTierEntity(2025, "married_filing_jointly", bd("750000"), null, bd("443.90"), bd("85.80")));
    }

    public static void stubSingle2025Irmaa(IrmaaTierRepository irmaaTierRepo) {
        lenient().when(irmaaTierRepo.findByTaxYearAndFilingStatusOrderByMagiFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025IrmaaTiers());
    }

    public static void stubMfj2025Irmaa(IrmaaTierRepository irmaaTierRepo) {
        lenient().when(irmaaTierRepo.findByTaxYearAndFilingStatusOrderByMagiFloorAsc(anyInt(),
                        eq("married_filing_jointly")))
                .thenReturn(mfj2025IrmaaTiers());
    }
}
