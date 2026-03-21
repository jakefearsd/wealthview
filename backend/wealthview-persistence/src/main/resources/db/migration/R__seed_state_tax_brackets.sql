-- Seed state tax data: brackets, standard deductions, and surcharges
-- Re-runnable: deletes and re-inserts all state tax data
-- Currently includes: California (CA), Arizona (AZ), Oregon (OR) 2024-2025

DELETE FROM state_tax_surcharges;
DELETE FROM state_standard_deductions;
DELETE FROM state_tax_brackets;

-- =============================================================================
-- CALIFORNIA (CA) — 2024
-- =============================================================================

-- 2024 Single
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('CA', 2024, 'single', 0, 10412, 0.0100),
('CA', 2024, 'single', 10412, 24684, 0.0200),
('CA', 2024, 'single', 24684, 38959, 0.0400),
('CA', 2024, 'single', 38959, 54081, 0.0600),
('CA', 2024, 'single', 54081, 68350, 0.0800),
('CA', 2024, 'single', 68350, 349137, 0.0930),
('CA', 2024, 'single', 349137, 418961, 0.1030),
('CA', 2024, 'single', 418961, 698271, 0.1130),
('CA', 2024, 'single', 698271, 1000000, 0.1230),
('CA', 2024, 'single', 1000000, NULL, 0.1230);

-- 2024 Married Filing Jointly
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('CA', 2024, 'married_filing_jointly', 0, 20824, 0.0100),
('CA', 2024, 'married_filing_jointly', 20824, 49368, 0.0200),
('CA', 2024, 'married_filing_jointly', 49368, 77918, 0.0400),
('CA', 2024, 'married_filing_jointly', 77918, 108162, 0.0600),
('CA', 2024, 'married_filing_jointly', 108162, 136700, 0.0800),
('CA', 2024, 'married_filing_jointly', 136700, 698274, 0.0930),
('CA', 2024, 'married_filing_jointly', 698274, 837922, 0.1030),
('CA', 2024, 'married_filing_jointly', 837922, 1396542, 0.1130),
('CA', 2024, 'married_filing_jointly', 1396542, 2000000, 0.1230),
('CA', 2024, 'married_filing_jointly', 2000000, NULL, 0.1230);

-- =============================================================================
-- CALIFORNIA (CA) — 2025
-- =============================================================================

-- 2025 Single (estimated, inflation-adjusted from 2024)
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('CA', 2025, 'single', 0, 10756, 0.0100),
('CA', 2025, 'single', 10756, 25499, 0.0200),
('CA', 2025, 'single', 25499, 40245, 0.0400),
('CA', 2025, 'single', 40245, 55866, 0.0600),
('CA', 2025, 'single', 55866, 70606, 0.0800),
('CA', 2025, 'single', 70606, 360659, 0.0930),
('CA', 2025, 'single', 360659, 432787, 0.1030),
('CA', 2025, 'single', 432787, 721314, 0.1130),
('CA', 2025, 'single', 721314, 1000000, 0.1230),
('CA', 2025, 'single', 1000000, NULL, 0.1230);

-- 2025 Married Filing Jointly (estimated, inflation-adjusted from 2024)
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('CA', 2025, 'married_filing_jointly', 0, 21512, 0.0100),
('CA', 2025, 'married_filing_jointly', 21512, 50998, 0.0200),
('CA', 2025, 'married_filing_jointly', 50998, 80490, 0.0400),
('CA', 2025, 'married_filing_jointly', 80490, 111732, 0.0600),
('CA', 2025, 'married_filing_jointly', 111732, 141212, 0.0800),
('CA', 2025, 'married_filing_jointly', 141212, 721318, 0.0930),
('CA', 2025, 'married_filing_jointly', 721318, 865574, 0.1030),
('CA', 2025, 'married_filing_jointly', 865574, 1442628, 0.1130),
('CA', 2025, 'married_filing_jointly', 1442628, 2000000, 0.1230),
('CA', 2025, 'married_filing_jointly', 2000000, NULL, 0.1230);

-- =============================================================================
-- CALIFORNIA Standard Deductions
-- =============================================================================

INSERT INTO state_standard_deductions (state_code, tax_year, filing_status, amount) VALUES
('CA', 2024, 'single', 5540.0000),
('CA', 2024, 'married_filing_jointly', 11080.0000),
('CA', 2025, 'single', 5722.0000),
('CA', 2025, 'married_filing_jointly', 11444.0000);

-- =============================================================================
-- CALIFORNIA Surcharges — Mental Health Services Tax (Prop 63)
-- 1% on taxable income over $1M (all filing statuses)
-- =============================================================================

INSERT INTO state_tax_surcharges (state_code, tax_year, filing_status, surcharge_name, income_threshold, rate) VALUES
('CA', 2024, 'single', 'Mental Health Services Tax', 1000000, 0.0100),
('CA', 2024, 'married_filing_jointly', 'Mental Health Services Tax', 1000000, 0.0100),
('CA', 2025, 'single', 'Mental Health Services Tax', 1000000, 0.0100),
('CA', 2025, 'married_filing_jointly', 'Mental Health Services Tax', 1000000, 0.0100);

-- =============================================================================
-- ARIZONA (AZ) — 2024
-- Flat 2.5% rate (since 2023). Standard deduction conforms to federal amounts.
-- =============================================================================

INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('AZ', 2024, 'single', 0, NULL, 0.0250),
('AZ', 2024, 'married_filing_jointly', 0, NULL, 0.0250);

-- =============================================================================
-- ARIZONA (AZ) — 2025
-- =============================================================================

INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('AZ', 2025, 'single', 0, NULL, 0.0250),
('AZ', 2025, 'married_filing_jointly', 0, NULL, 0.0250);

-- =============================================================================
-- ARIZONA Standard Deductions (federal-conforming)
-- =============================================================================

INSERT INTO state_standard_deductions (state_code, tax_year, filing_status, amount) VALUES
('AZ', 2024, 'single', 14600.0000),
('AZ', 2024, 'married_filing_jointly', 29200.0000),
('AZ', 2025, 'single', 15000.0000),
('AZ', 2025, 'married_filing_jointly', 30000.0000);

-- =============================================================================
-- OREGON (OR) — 2024
-- Progressive 4-bracket system. No sales tax; among the highest income tax rates.
-- =============================================================================

-- 2024 Single
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('OR', 2024, 'single', 0, 4050, 0.0475),
('OR', 2024, 'single', 4050, 10200, 0.0675),
('OR', 2024, 'single', 10200, 125000, 0.0875),
('OR', 2024, 'single', 125000, NULL, 0.0990);

-- 2024 Married Filing Jointly
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('OR', 2024, 'married_filing_jointly', 0, 8100, 0.0475),
('OR', 2024, 'married_filing_jointly', 8100, 20400, 0.0675),
('OR', 2024, 'married_filing_jointly', 20400, 250000, 0.0875),
('OR', 2024, 'married_filing_jointly', 250000, NULL, 0.0990);

-- =============================================================================
-- OREGON (OR) — 2025
-- =============================================================================

-- 2025 Single
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('OR', 2025, 'single', 0, 4300, 0.0475),
('OR', 2025, 'single', 4300, 10750, 0.0675),
('OR', 2025, 'single', 10750, 125000, 0.0875),
('OR', 2025, 'single', 125000, NULL, 0.0990);

-- 2025 Married Filing Jointly
INSERT INTO state_tax_brackets (state_code, tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
('OR', 2025, 'married_filing_jointly', 0, 8550, 0.0475),
('OR', 2025, 'married_filing_jointly', 8550, 21500, 0.0675),
('OR', 2025, 'married_filing_jointly', 21500, 250000, 0.0875),
('OR', 2025, 'married_filing_jointly', 250000, NULL, 0.0990);

-- =============================================================================
-- OREGON Standard Deductions
-- =============================================================================

INSERT INTO state_standard_deductions (state_code, tax_year, filing_status, amount) VALUES
('OR', 2024, 'single', 2745.0000),
('OR', 2024, 'married_filing_jointly', 5495.0000),
('OR', 2025, 'single', 2895.0000),
('OR', 2025, 'married_filing_jointly', 5790.0000);
