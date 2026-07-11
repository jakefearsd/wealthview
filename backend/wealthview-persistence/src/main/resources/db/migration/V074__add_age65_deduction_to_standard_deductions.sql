-- Adds the IRS age-65+ additional standard deduction (Pub. 501) to standard_deductions, per
-- qualifying person (single/HOH filers get one adder; MFJ filers get one adder PER spouse aged
-- 65+, though this engine only tracks the primary filer's age -- see FederalTaxCalculator's
-- loadStandardDeduction Javadoc). Defaults existing/not-yet-seeded rows to 0 so no historical row
-- needs backfilling before R__seed_standard_deductions.sql re-runs with real per-year figures.
ALTER TABLE standard_deductions
    ADD COLUMN IF NOT EXISTS additional_age65 numeric(19,4) NOT NULL DEFAULT 0;
