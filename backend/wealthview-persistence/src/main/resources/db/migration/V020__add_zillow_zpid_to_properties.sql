-- Add zillow_zpid column to properties table.
-- Stores the Zillow property ID (zpid) for direct lookups on future valuation refreshes,
-- avoiding repeated address-based search disambiguation.

ALTER TABLE properties
    ADD COLUMN zillow_zpid text;
