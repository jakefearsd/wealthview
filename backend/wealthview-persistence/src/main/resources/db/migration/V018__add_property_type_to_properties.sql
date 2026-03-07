-- Add property_type column to properties table.
-- Existing properties default to 'primary_residence' (most common use case).
-- Valid values: primary_residence, investment, vacation.
ALTER TABLE properties ADD COLUMN property_type text NOT NULL DEFAULT 'primary_residence';
ALTER TABLE properties ADD CONSTRAINT chk_properties_property_type
    CHECK (property_type IN ('primary_residence', 'investment', 'vacation'));
