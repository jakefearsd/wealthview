-- Add 'yahoo' as a valid price source for Yahoo Finance integration
ALTER TABLE prices DROP CONSTRAINT prices_source_check;
ALTER TABLE prices ADD CONSTRAINT prices_source_check
    CHECK (source = ANY (ARRAY['manual', 'finnhub', 'yahoo']));
