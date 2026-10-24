-- V085: the optimizer's cash reserve is retired in favor of the bond tent. Every scenario whose guardrail profile
-- held a cash reserve (cash_reserve_years > 0) and that has an active spending plan but no bond tent yet gets an
-- equivalent tent: coverage = the reserve years (capped at 10), no build-up, one "Cash reserve" mix that is 100%
-- ultrashort, the default yields, and the converted_from_cash_reserve marker that drives the one-time notice.
-- cash_return_rate is not carried over (the tent earns historical T-bill returns) and include_depression_years is
-- left as stored. The converted scenarios' profiles are marked stale so the next optimize certifies them with the
-- tent in place. Idempotent: a scenario that already has a bond_tent is skipped, so a second run changes nothing.
-- The cash_reserve_years / cash_return_rate columns stay, unread, and new rows stop claiming a 2-year reserve.
WITH converted AS (
    UPDATE projection_scenarios s
    SET params_json = COALESCE(s.params_json, '{}'::jsonb) || jsonb_build_object(
            'bond_tent', jsonb_build_object(
                'coverage_years', LEAST(g.cash_reserve_years, 10),
                'build_up_start_years', 0,
                'mixes', jsonb_build_array(jsonb_build_object(
                    'name', 'Cash reserve',
                    'shares', jsonb_build_object('ultrashort', 1, 'short', 0, 'intermediate', 0, 'long', 0))),
                'yields', jsonb_build_object(
                    'ultrashort', 0.0394, 'short', 0.0465, 'intermediate', 0.048, 'long', 0.0539),
                'converted_from_cash_reserve', true)),
        updated_at = now()
    FROM guardrail_spending_profiles g
    WHERE g.scenario_id = s.id
      AND g.cash_reserve_years > 0
      AND (s.spending_profile_id IS NOT NULL OR s.guardrail_profile_id IS NOT NULL)
      AND COALESCE(s.params_json -> 'bond_tent', 'null'::jsonb) = 'null'::jsonb
    RETURNING s.id
)
UPDATE guardrail_spending_profiles
SET is_stale = true
WHERE scenario_id IN (SELECT id FROM converted);

ALTER TABLE guardrail_spending_profiles ALTER COLUMN cash_reserve_years SET DEFAULT 0;
