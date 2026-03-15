-- Guardrail spending profiles: Monte Carlo optimized spending plans bound to a projection scenario.
-- Each profile stores optimization parameters, per-year spending recommendations with confidence corridors,
-- and summary statistics from the Monte Carlo simulation.

CREATE TABLE guardrail_spending_profiles (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenants(id),
    scenario_id             uuid NOT NULL REFERENCES projection_scenarios(id) ON DELETE CASCADE,
    name                    text NOT NULL,
    essential_floor         numeric(19,4) NOT NULL,
    terminal_balance_target numeric(19,4) NOT NULL DEFAULT 0,
    return_mean             numeric(7,4) NOT NULL DEFAULT 0.10,
    return_stddev           numeric(7,4) NOT NULL DEFAULT 0.15,
    trial_count             integer NOT NULL DEFAULT 5000,
    confidence_level        numeric(5,4) NOT NULL DEFAULT 0.95,
    phases                  jsonb NOT NULL DEFAULT '[]',
    yearly_spending         jsonb NOT NULL DEFAULT '[]',
    median_final_balance    numeric(19,4),
    failure_rate            numeric(7,4),
    percentile_10_final     numeric(19,4),
    percentile_90_final     numeric(19,4),
    scenario_hash           text NOT NULL,
    is_stale                boolean NOT NULL DEFAULT false,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_guardrail_profiles_scenario ON guardrail_spending_profiles(scenario_id);
CREATE INDEX idx_guardrail_profiles_tenant_id ON guardrail_spending_profiles(tenant_id);
