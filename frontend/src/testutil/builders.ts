import type {
    GuardrailProfileResponse,
    ProjectionAccount,
    RothConversionScheduleResponse,
    Scenario,
} from '../types/projection';

/**
 * Shared test-fixture builders for the `Scenario` / projection object graph.
 *
 * Each builder returns a fully-populated, realistic default value and accepts
 * `Partial<T>` overrides for the fields a given test actually cares about —
 * replacing hand-rolled object literals that previously restated every field
 * (including ones irrelevant to the test) across many `*.test.ts(x)` files.
 */

export function makeAccount(overrides: Partial<ProjectionAccount> = {}): ProjectionAccount {
    return {
        id: 'a1',
        linked_account_id: null,
        name: 'Brokerage',
        initial_balance: 100000,
        annual_contribution: 10000,
        expected_return: 0.07,
        account_type: 'taxable',
        cost_basis: null,
        allocation: null,
        allocation_is_override: false,
        owner: 'primary',
        ...overrides,
    };
}

export function makeScenario(overrides: Partial<Scenario> = {}): Scenario {
    return {
        id: 'sc-1',
        name: 'Test Scenario',
        retirement_date: '2045-01-01',
        end_age: 90,
        inflation_rate: 0.03,
        params_json: null,
        accounts: [],
        spending_profile: null,
        guardrail_profile: null,
        income_sources: [],
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-01T00:00:00Z',
        ...overrides,
    };
}

export function makeSchedule(
    overrides: Partial<RothConversionScheduleResponse> = {},
): RothConversionScheduleResponse {
    return {
        lifetime_tax_with_conversions: 100000,
        lifetime_tax_without: 150000,
        tax_savings: 50000,
        exhaustion_age: 85,
        exhaustion_target_met: true,
        conversion_bracket_rate: 0.24,
        rmd_target_bracket_rate: 0.12,
        traditional_exhaustion_buffer: 3,
        mc_exhaustion_pct: null,
        target_traditional_balance: null,
        rmd_bracket_headroom: 0.15,
        years: [],
        ...overrides,
    };
}

export function makeProfile(overrides: Partial<GuardrailProfileResponse> = {}): GuardrailProfileResponse {
    return {
        id: 'g1',
        scenario_id: 's1',
        name: 'My Plan',
        essential_floor: 45000,
        terminal_balance_target: 250000,
        return_mean: 0.07,
        trial_count: 2500,
        confidence_level: 0.7,
        phases: [
            { name: 'Go-go', start_age: 62, end_age: 74, priority_weight: 3, target_spending: 90000 },
        ],
        yearly_spending: [],
        median_final_balance: 1000000,
        failure_rate: 0.05,
        success_probability: 0.9,
        success_probability_with_rules: null,
        gated_on: 'no_adaptation',
        floor_reduced: false,
        original_floor_success_probability: null,
        fixed_return_share: null,
        percentile10_final: 400000,
        stale: false,
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
        portfolio_floor: 100000,
        max_annual_adjustment_rate: 0.08,
        phase_blend_years: 2,
        risk_tolerance: 'aggressive',
        cash_reserve_years: 3,
        cash_return_rate: 0.045,
        conversion_schedule: null,
        stochastic_mortality: null,
        ...overrides,
    };
}
