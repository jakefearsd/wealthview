-- Seed 2025 IRMAA (Income-Related Monthly Adjustment Amount) tiers -- standard Part B premium
-- $185.00/mo; five surcharge tiers above it, keyed on MAGI. Re-runnable: truncates and re-inserts.
--
-- Sources (verified 2026-07-12):
--   - CMS "2025 Medicare Parts A & B Premiums and Deductibles" fact sheet (cms.gov/newsroom/
--     fact-sheets/2025-medicare-parts-b-premiums-and-deductibles): standard Part B premium
--     $185.00/mo for 2025; first IRMAA threshold $106,000 (single) / $212,000 (MFJ), based on 2023
--     MAGI (the statutory 2-year lookback).
--   - SSA POMS HI 01101.020 (IRMAA sliding-scale tables): the five-tier bracket structure and the
--     "greater than floor, up to and including ceiling" bracket semantics used here.
--   - Kiplinger "Medicare Premiums 2025: IRMAA Brackets and Surcharges for Parts B and D": total
--     Part B premium by tier ($259.00 / $370.00 / $480.90 / $591.90 / $628.90) and Part B surcharge
--     range ($74.00-$443.90), cross-checked against the table below.
--   - Cross-checked against a third aggregator (unitedmedicareadvisors.com 2025 IRMAA summary) for
--     the Part D surcharge figures ($13.70 / $35.30 / $57.00 / $78.60 / $85.80).
--
-- part_b_surcharge below is the tier's total Part B premium MINUS the $185.00 standard premium
-- (259.00-185.00=74.00, 370.00-185.00=185.00, 480.90-185.00=295.90, 591.90-185.00=406.90,
-- 628.90-185.00=443.90) -- see V075's column comment for why it is pre-netted.
--
-- MAGI bracket dollar boundaries differ by filing status; the Part B/Part D surcharge DOLLAR
-- amounts themselves do not (set nationally, independent of filing status).
TRUNCATE TABLE irmaa_tiers;
INSERT INTO irmaa_tiers (tax_year, filing_status, magi_floor, magi_ceiling, part_b_surcharge, part_d_surcharge) VALUES
  (2025, 'single', 0,       106000,  0,      0),
  (2025, 'single', 106000,  133000,  74.00,  13.70),
  (2025, 'single', 133000,  167000,  185.00, 35.30),
  (2025, 'single', 167000,  200000,  295.90, 57.00),
  (2025, 'single', 200000,  500000,  406.90, 78.60),
  (2025, 'single', 500000,  NULL,    443.90, 85.80),
  (2025, 'married_filing_jointly', 0,       212000, 0,      0),
  (2025, 'married_filing_jointly', 212000,  266000, 74.00,  13.70),
  (2025, 'married_filing_jointly', 266000,  334000, 185.00, 35.30),
  (2025, 'married_filing_jointly', 334000,  400000, 295.90, 57.00),
  (2025, 'married_filing_jointly', 400000,  750000, 406.90, 78.60),
  (2025, 'married_filing_jointly', 750000,  NULL,   443.90, 85.80);
