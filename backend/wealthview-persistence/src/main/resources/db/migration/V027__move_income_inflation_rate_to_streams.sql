-- Move income_inflation_rate from profile-level column into each income stream's JSON as inflationRate.
-- Streams without an explicit rate will default to 0 in the engine.
UPDATE spending_profiles
SET income_streams = (
    SELECT COALESCE(jsonb_agg(elem || jsonb_build_object('inflationRate', income_inflation_rate)), '[]'::jsonb)
    FROM jsonb_array_elements(income_streams) AS elem
)
WHERE income_inflation_rate != 0
  AND income_streams IS NOT NULL
  AND income_streams != '[]'::jsonb;

ALTER TABLE spending_profiles DROP COLUMN income_inflation_rate;
