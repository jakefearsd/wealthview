-- Seed federal standard deduction amounts for 2022-2025, plus the IRS age-65+ additional
-- standard deduction (Pub. 501, per qualifying person -- see additional_age65 column comment).
--
-- 2025 uses the POST-OBBBA (One Big Beautiful Bill Act, H.R. 1, enacted 2025) base amounts
-- ($15,750 single / $31,500 MFJ), not the pre-OBBBA inflation-indexed figures ($15,000 / $30,000)
-- that were seeded before this fix. Since FederalTaxCalculator.loadStandardDeduction falls back to
-- the LATEST seeded year for any year beyond it, this 2025 row is also the constant-real value
-- applied to every later projection year -- so this single row fixes the whole projection horizon.
--
-- Age-65+ additional amounts (IRS Rev. Proc. figures, single/HOH vs married-per-spouse):
--   2022: 1,750 / 1,400 (Rev. Proc. 2021-45)
--   2023: 1,850 / 1,500 (Rev. Proc. 2022-38)
--   2024: 1,950 / 1,550 (Rev. Proc. 2023-34)
--   2025: 2,000 / 1,600 (Rev. Proc. 2024-40; OBBBA did not change this figure -- OBBBA's separate
--         temporary $6,000/filer "senior bonus deduction" for 2025-2028 is a DIFFERENT mechanism
--         and is intentionally not modeled here, out of this fix's scope)
--
-- Re-runnable: deletes and re-inserts all data
DELETE FROM standard_deductions;

INSERT INTO standard_deductions (tax_year, filing_status, amount, additional_age65) VALUES
(2022, 'single', 12950.0000, 1750.0000),
(2022, 'married_filing_jointly', 25900.0000, 1400.0000),
(2023, 'single', 13850.0000, 1850.0000),
(2023, 'married_filing_jointly', 27700.0000, 1500.0000),
(2024, 'single', 14600.0000, 1950.0000),
(2024, 'married_filing_jointly', 29200.0000, 1550.0000),
(2025, 'single', 15750.0000, 2000.0000),
(2025, 'married_filing_jointly', 31500.0000, 1600.0000);
