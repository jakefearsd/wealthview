-- Add structured cost segregation inputs to properties.
-- These fields allow auto-computation of cost seg depreciation schedules
-- from asset class allocations + bonus depreciation rate.
ALTER TABLE properties
  ADD COLUMN cost_seg_allocations jsonb NOT NULL DEFAULT '[]',
  ADD COLUMN bonus_depreciation_rate numeric(5,4) NOT NULL DEFAULT 1.0000,
  ADD COLUMN cost_seg_study_year integer;
