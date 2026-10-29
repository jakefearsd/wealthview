import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../../api/projections', () => ({ sizeBondTent: vi.fn() }));

import { sizeBondTent } from '../../api/projections';
import ScenarioTentSection from './ScenarioTentSection';
import {
    DEFAULT_BUILD_UP_START_YEARS,
    DEFAULT_COVERAGE_YEARS,
    DEFAULT_TENT_MIX,
    DEFAULT_TENT_YIELDS,
} from './bondTentDefaults';
import type { BondTentConfig, BondTentSizing } from '../../types/projection';

const mockSize = vi.mocked(sizeBondTent);

function defaultConfig(): BondTentConfig {
    return {
        coverage_years: DEFAULT_COVERAGE_YEARS,
        build_up_start_years: DEFAULT_BUILD_UP_START_YEARS,
        mixes: [structuredClone(DEFAULT_TENT_MIX)],
        yields: { ...DEFAULT_TENT_YIELDS },
    };
}

function sizing(overrides: Partial<BondTentSizing> = {}): BondTentSizing {
    return {
        projected_portfolio_at_retirement: 2_000_000,
        coverage_years: 5,
        mixes: [
            {
                name: 'Balanced', real_yield: 0.01, target: 240_000, percent_of_portfolio: 0.12,
                rule_of_thumb: 400_000, reason: 'INCOME', reason_amount: -160_000,
            },
            {
                name: 'Cash', real_yield: 0.005, target: 250_000, percent_of_portfolio: 0.125,
                rule_of_thumb: 400_000, reason: 'SPENDING_LEVEL', reason_amount: 0,
            },
        ],
        covered_years: [
            { year: 2040, withdrawal: 80_000, fraction: 1 },
            { year: 2041, withdrawal: 40_000, fraction: 0.5 },
        ],
        ...overrides,
    };
}

function renderSection(config: BondTentConfig | null, props: { planAvailable?: boolean; scenarioId?: string | null; savedPlanMatches?: boolean } = {}) {
    const onChange = vi.fn();
    render(
        <ScenarioTentSection
            config={config}
            onChange={onChange}
            planAvailable={props.planAvailable ?? true}
            scenarioId={props.scenarioId === undefined ? null : props.scenarioId}
            savedPlanMatches={props.savedPlanMatches ?? true}
        />,
    );
    return onChange;
}

describe('ScenarioTentSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('shows the converted notice when the tent came from a cash reserve', () => {
        renderSection({ ...defaultConfig(), coverage_years: 2, converted_from_cash_reserve: true });

        expect(screen.getByRole('status')).toHaveTextContent(/Your 2-year cash reserve is now a 2-year bond tent/);
    });

    it('shows no converted notice for an ordinary tent', () => {
        renderSection(defaultConfig());

        expect(screen.queryByRole('status')).not.toBeInTheDocument();
    });

    it('turns the tent on with the defaults', () => {
        const onChange = renderSection(null);

        fireEvent.click(screen.getByLabelText('Use a bond tent'));

        expect(onChange).toHaveBeenCalledWith(defaultConfig());
    });

    it('renders defaults when turned on', () => {
        renderSection(defaultConfig());

        expect(screen.getByLabelText('Years of spending to cover')).toHaveValue('5');
        expect(screen.getByLabelText('Start building this many years before retirement')).toHaveValue(5);
        expect(screen.getByLabelText('Ultrashort (SGOV)')).toHaveValue(20);
        expect(screen.getByLabelText('Short (VGSH)')).toHaveValue(30);
        expect(screen.getByLabelText('Intermediate (VGIT)')).toHaveValue(50);
        expect(screen.getByLabelText('Long (TLT)')).toHaveValue(0);
        expect(screen.getByLabelText('Ultrashort yield (%)')).toHaveValue(3.94);
        expect(screen.getByLabelText('Long yield (%)')).toHaveValue(5.39);
        expect(screen.getByText('Shares add up to 100%')).toBeInTheDocument();
        expect(screen.getByText(/never refilled, and whatever is left stays in bonds/)).toBeInTheDocument();
    });

    it('disables the tent with a note when there is no spending plan', () => {
        renderSection(null, { planAvailable: false });

        expect(screen.getByLabelText('Use a bond tent')).toBeDisabled();
        expect(screen.getByText(
            'A bond tent needs a spending plan. Choose a spending profile or an optimized plan above.',
        )).toBeInTheDocument();
    });

    it('flags shares that do not add up to 100%', () => {
        const config = defaultConfig();
        config.mixes[0].shares.long = 0.1;
        renderSection(config);

        expect(screen.getByRole('alert')).toHaveTextContent('Shares add up to 110%');
    });

    it('reports an edited share as a fraction', () => {
        const onChange = renderSection(defaultConfig());

        fireEvent.change(screen.getByLabelText('Long (TLT)'), { target: { value: '10' } });

        expect(onChange.mock.calls[0][0].mixes[0].shares.long).toBeCloseTo(0.1, 6);
    });

    it('shows the cash-reserve hint for a 100% ultrashort mix', () => {
        const config = defaultConfig();
        config.mixes[0].shares = { ultrashort: 1, short: 0, intermediate: 0, long: 0 };
        renderSection(config);

        expect(screen.getByText(
            '100% ultrashort is a classic cash reserve. Moving part of it into short or intermediate bonds '
            + 'usually earns more, at the cost of some price swings.',
        )).toBeInTheDocument();
    });

    it('does not show the hint for the default mix', () => {
        renderSection(defaultConfig());

        expect(screen.queryByText(/classic cash reserve/)).not.toBeInTheDocument();
    });

    it('limits mixes to three', () => {
        const config = defaultConfig();
        config.mixes.push({ ...structuredClone(DEFAULT_TENT_MIX), name: 'B' });
        config.mixes.push({ ...structuredClone(DEFAULT_TENT_MIX), name: 'C' });
        renderSection(config);

        expect(screen.queryByRole('button', { name: 'Add mix' })).not.toBeInTheDocument();
        expect(screen.getByText('(plan)')).toBeInTheDocument();
    });

    it('adds a mix', () => {
        const onChange = renderSection(defaultConfig());

        fireEvent.click(screen.getByRole('button', { name: 'Add mix' }));

        expect(onChange.mock.calls[0][0].mixes).toHaveLength(2);
    });

    it('removes a non-plan mix but never the plan mix', () => {
        const config = defaultConfig();
        config.mixes.push({ ...structuredClone(DEFAULT_TENT_MIX), name: 'Other' });
        const onChange = renderSection(config);

        expect(screen.getAllByRole('button', { name: /Remove mix/ })).toHaveLength(1);
        fireEvent.click(screen.getByRole('button', { name: 'Remove mix Other' }));

        expect(onChange.mock.calls[0][0].mixes.map((m: { name: string }) => m.name)).toEqual(['Balanced']);
    });

    it('calls sizeBondTent for a saved scenario after the debounce and renders the sizing lines', async () => {
        vi.useFakeTimers();
        mockSize.mockResolvedValue(sizing());
        renderSection(defaultConfig(), { scenarioId: 'sc-1' });

        expect(mockSize).not.toHaveBeenCalled();
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });

        expect(mockSize).toHaveBeenCalledTimes(1);
        expect(mockSize).toHaveBeenCalledWith('sc-1', defaultConfig());
        expect(screen.getByText(
            'Tent size needed: $240,000 · 12.0% of your projected $2,000,000 at retirement',
        )).toBeInTheDocument();
        expect(screen.getByText(
            'Cash: Tent size needed: $250,000 · 12.5% of your projected $2,000,000 at retirement',
        )).toBeInTheDocument();
        expect(screen.getByText('Why Balanced differs from the rule of thumb ($400,000)')).toBeInTheDocument();
        expect(screen.getByText(
            'Income such as Social Security, a pension or rent pays for about $160,000 of these years.',
        )).toBeInTheDocument();
        expect(screen.getByText(/2040/)).toBeInTheDocument();
        expect(screen.getByText(/2041.*partial/)).toBeInTheDocument();
    });

    it('explainsRuleOfThumb_twoMixes_showsEachMixsOwnReason', async () => {
        vi.useFakeTimers();
        const base = sizing();
        mockSize.mockResolvedValue(sizing({
            mixes: [
                { ...base.mixes[0], rule_of_thumb: 400_000 },
                { ...base.mixes[1], rule_of_thumb: 450_000, reason: 'WITHDRAWAL_TAX', reason_amount: 30_000 },
            ],
        }));
        renderSection(defaultConfig(), { scenarioId: 'sc-1' });

        await act(async () => { await vi.advanceTimersByTimeAsync(500); });

        expect(screen.getByText('Why Balanced differs from the rule of thumb ($400,000)')).toBeInTheDocument();
        expect(screen.getByText(/pays for about \$160,000 of these years/)).toBeInTheDocument();
        expect(screen.getByText('Why Cash differs from the rule of thumb ($450,000)')).toBeInTheDocument();
        expect(screen.getByText('About $30,000 of the withdrawals go to income tax.')).toBeInTheDocument();
    });

    it('does not call the api before the debounce elapses', async () => {
        vi.useFakeTimers();
        mockSize.mockResolvedValue(sizing());
        renderSection(defaultConfig(), { scenarioId: 'sc-1' });

        await act(async () => { await vi.advanceTimersByTimeAsync(499); });

        expect(mockSize).not.toHaveBeenCalled();
    });

    it('explains the other reasons', async () => {
        vi.useFakeTimers();
        const base = sizing().mixes[0];
        mockSize.mockResolvedValue(sizing({
            mixes: [{ ...base, reason: 'SPENDING_LEVEL', reason_amount: 5 }],
        }));
        renderSection(defaultConfig(), { scenarioId: 'sc-1' });

        await act(async () => { await vi.advanceTimersByTimeAsync(500); });

        expect(screen.getByText('Your planned spending in these years is higher than 4% of your portfolio.'))
            .toBeInTheDocument();
    });

    it.each([
        ['WITHDRAWAL_TAX', -30_000, 'About $30,000 of the withdrawals go to income tax.'],
        ['MIX_YIELDS', 12_000, "This mix's yields earn about $12,000 while the tent is spent down."],
        ['SPENDING_LEVEL', -5, 'Your planned spending in these years is lower than 4% of your portfolio.'],
    ] as const)('words the %s reason', async (reason, amount, text) => {
        vi.useFakeTimers();
        mockSize.mockResolvedValue(sizing({ mixes: [{ ...sizing().mixes[0], reason, reason_amount: amount }] }));
        renderSection(defaultConfig(), { scenarioId: 'sc-1' });

        await act(async () => { await vi.advanceTimersByTimeAsync(500); });

        expect(screen.getByText(text)).toBeInTheDocument();
    });

    it('shows the server message when sizing is rejected', async () => {
        vi.useFakeTimers();
        mockSize.mockRejectedValue({
            isAxiosError: true,
            response: { data: { error: 'BAD_REQUEST', message: 'Spending plan has no data', status: 400 } },
        });
        renderSection(defaultConfig(), { scenarioId: 'sc-1' });

        await act(async () => { await vi.advanceTimersByTimeAsync(500); });

        expect(screen.getByText('Spending plan has no data')).toBeInTheDocument();
    });

    it('does not size an invalid config', async () => {
        vi.useFakeTimers();
        const config = defaultConfig();
        config.mixes[0].shares.long = 0.1;
        renderSection(config, { scenarioId: 'sc-1' });

        await act(async () => { await vi.advanceTimersByTimeAsync(1000); });

        expect(mockSize).not.toHaveBeenCalled();
    });

    it('asks to save first for an unsaved scenario', async () => {
        vi.useFakeTimers();
        renderSection(defaultConfig(), { scenarioId: null });

        await act(async () => { await vi.advanceTimersByTimeAsync(1000); });

        expect(screen.getByText('Save the scenario to see how big the tent needs to be.')).toBeInTheDocument();
        expect(mockSize).not.toHaveBeenCalled();
    });

    it('asks to save first, without calling the api, when the plan differs from the saved one', async () => {
        vi.useFakeTimers();
        renderSection(defaultConfig(), { scenarioId: 'sc-1', savedPlanMatches: false });

        await act(async () => { await vi.advanceTimersByTimeAsync(1000); });

        expect(screen.getByText('Save the scenario to see how big the tent needs to be.')).toBeInTheDocument();
        expect(mockSize).not.toHaveBeenCalled();
    });

    it('says the sizing is based on the saved scenario', async () => {
        vi.useFakeTimers();
        mockSize.mockResolvedValue(sizing());
        renderSection(defaultConfig(), { scenarioId: 'sc-1' });

        await act(async () => { await vi.advanceTimersByTimeAsync(500); });

        expect(screen.getByText(
            "Based on your saved scenario. Save to include any changes you've made since.",
        )).toBeInTheDocument();
    });

    it('marks a mix that is not selected when its shares are wrong', () => {
        const config = defaultConfig();
        config.mixes.push({ name: 'Other', shares: { ultrashort: 0.5, short: 0.4, intermediate: 0, long: 0 } });
        renderSection(config);

        expect(screen.getByLabelText('Mix 2 name')).toHaveAttribute('aria-invalid', 'true');
        expect(screen.getByLabelText('Mix 1 name')).not.toHaveAttribute('aria-invalid', 'true');
    });

    it('clears a stale sizing result when the config becomes invalid', async () => {
        vi.useFakeTimers();
        mockSize.mockResolvedValue(sizing());
        const onChange = vi.fn();
        const props = { onChange, planAvailable: true, scenarioId: 'sc-1', savedPlanMatches: true };
        const { rerender } = render(<ScenarioTentSection config={defaultConfig()} {...props} />);
        await act(async () => { await vi.advanceTimersByTimeAsync(500); });
        expect(screen.getByText(/Tent size needed: \$240,000/)).toBeInTheDocument();

        const bad = defaultConfig();
        bad.mixes[0].shares.long = 0.1;
        rerender(<ScenarioTentSection config={bad} {...props} />);

        expect(screen.queryByText(/Tent size needed/)).not.toBeInTheDocument();
        expect(screen.getByText('Fix the tent settings above to see its size.')).toBeInTheDocument();
    });

    it('names a new mix without colliding with existing names', () => {
        const config = defaultConfig();
        config.mixes[0].name = 'Mix 2';
        const onChange = renderSection(config);

        fireEvent.click(screen.getByRole('button', { name: 'Add mix' }));

        expect(onChange.mock.calls[0][0].mixes[1].name).toBe('Mix 3');
    });
});
