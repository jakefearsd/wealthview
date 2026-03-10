-- Migrate existing income_streams JSON from spending_profiles into the income_sources table.
-- For each spending profile with non-empty income_streams, create income_source rows
-- and link them to scenarios that reference the spending profile via scenario_income_sources.

-- Step 1: Insert income_sources from spending_profiles.income_streams JSON
INSERT INTO income_sources (tenant_id, name, income_type, annual_amount, start_age, end_age,
                            inflation_rate, one_time, tax_treatment)
SELECT
    sp.tenant_id,
    COALESCE(stream->>'name', 'Migrated Income'),
    'other',
    COALESCE((stream->>'annual_amount')::numeric, (stream->>'annualAmount')::numeric, 0),
    COALESCE((stream->>'start_age')::int, (stream->>'startAge')::int, 0),
    COALESCE((stream->>'end_age')::int, (stream->>'endAge')::int),
    COALESCE((stream->>'inflation_rate')::numeric, (stream->>'inflationRate')::numeric, 0),
    COALESCE((stream->>'one_time')::boolean, (stream->>'oneTime')::boolean, false),
    'taxable'
FROM spending_profiles sp,
     jsonb_array_elements(sp.income_streams) AS stream
WHERE sp.income_streams IS NOT NULL
  AND sp.income_streams != '[]'::jsonb;

-- Step 2: Link migrated income_sources to scenarios that reference those spending profiles
INSERT INTO scenario_income_sources (scenario_id, income_source_id)
SELECT ps.id, isrc.id
FROM projection_scenarios ps
JOIN spending_profiles sp ON ps.spending_profile_id = sp.id
JOIN income_sources isrc ON isrc.tenant_id = sp.tenant_id
    AND isrc.income_type = 'other'
    AND isrc.created_at >= now() - interval '1 minute'
WHERE sp.income_streams IS NOT NULL
  AND sp.income_streams != '[]'::jsonb
  AND NOT EXISTS (
      SELECT 1 FROM scenario_income_sources sis
      WHERE sis.scenario_id = ps.id AND sis.income_source_id = isrc.id
  );
