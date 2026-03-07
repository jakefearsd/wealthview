-- Add frequency column to property_income and property_expenses tables.
-- Frequency indicates whether an entry is monthly (single month) or annual (spread across 12 months).
-- Default 'monthly' preserves existing behavior for all current rows.

ALTER TABLE property_income
    ADD COLUMN frequency text NOT NULL DEFAULT 'monthly';

ALTER TABLE property_income
    ADD CONSTRAINT chk_property_income_frequency CHECK (frequency IN ('monthly', 'annual'));

ALTER TABLE property_expenses
    ADD COLUMN frequency text NOT NULL DEFAULT 'monthly';

ALTER TABLE property_expenses
    ADD CONSTRAINT chk_property_expenses_frequency CHECK (frequency IN ('monthly', 'annual'));
