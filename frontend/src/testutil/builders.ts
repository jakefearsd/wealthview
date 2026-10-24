import type {
    BondTentReplay,
    BondTentReplayOutcome,
    BondTentReplayYear,
    BondTentSimulation,
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
        bond_tent_target: null,
        conversion_schedule: null,
        stochastic_mortality: null,
        ...overrides,
    };
}

export function makeReplayYear(overrides: Partial<BondTentReplayYear> = {}): BondTentReplayYear {
    return {
        year: 2045,
        age: 65,
        balance: 1000000,
        tent_balance: 200000,
        tent_drawn: 0,
        stocks_sold_while_down: 0,
        stocks_below_high: false,
        history_year: 2000,
        ...overrides,
    };
}

export function makeReplayOutcome(overrides: Partial<BondTentReplayOutcome> = {}): BondTentReplayOutcome {
    return {
        name: 'No tent',
        tent_target: null,
        lowest_balance: 800000,
        stocks_sold_while_down: 50000,
        years_covered: null,
        tent_left_at_checkpoint: null,
        balance_at90: 900000,
        years: [makeReplayYear({ year: 2045 }), makeReplayYear({ year: 2046, age: 66, balance: 900000 })],
        ...overrides,
    };
}

export function makeReplay(overrides: Partial<BondTentReplay> = {}): BondTentReplay {
    return {
        crash_label: '2000–02',
        crash_first_year: 2000,
        crash_last_year: 2002,
        crash_calendar_year: 2045,
        offset: 0,
        retirement_year: 2045,
        planned_years: 25,
        checkpoint_age: 70,
        available_offsets: [-3, -2, -1, 0, 1, 2, 3],
        first_start_year: 1954,
        last_start_year: 2025,
        outcomes: [
            makeReplayOutcome(),
            makeReplayOutcome({
                name: 'Plan mix',
                tent_target: 300000,
                lowest_balance: 850000,
                stocks_sold_while_down: 0,
                years_covered: 4.5,
                tent_left_at_checkpoint: 120000,
                balance_at90: 950000,
            }),
        ],
        ...overrides,
    };
}

export function makeSimulation(overrides: Partial<BondTentSimulation> = {}): BondTentSimulation {
    return {
        trial_count: 5000,
        planned_years: 30,
        columns: [
            {
                name: 'No tent', tent_target: null, success_rate: 0.82,
                years_covered_median: null, years_covered_worst10: null, balance_at90_median: 400000,
            },
            {
                name: 'Plan mix', tent_target: 300000, success_rate: 0.9,
                years_covered_median: 4.25, years_covered_worst10: 2, balance_at90_median: 450000,
            },
        ],
        ...overrides,
    };
}
