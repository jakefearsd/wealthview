import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { makeReplay, makeSimulation } from '../../testutil/builders';

vi.mock('recharts');
vi.mock('../../api/projections', () => ({
    getBondTentSimulation: vi.fn(),
    getBondTentReplay: vi.fn(),
}));

import { getBondTentSimulation, getBondTentReplay } from '../../api/projections';
import BondTentTab from './BondTentTab';

const mockSim = vi.mocked(getBondTentSimulation);
const mockReplay = vi.mocked(getBondTentReplay);

describe('BondTentTab', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mockSim.mockResolvedValue(makeSimulation());
        mockReplay.mockResolvedValue(makeReplay());
    });

    it('prompts to set up a tent when none is configured', () => {
        render(<BondTentTab scenarioId="s1" hasTent={false} />);

        expect(screen.getByText(
            'Set up a bond tent in this scenario to compare it with no tent here: '
            + 'edit the scenario and turn on Use a bond tent.')).toBeInTheDocument();
        expect(mockSim).not.toHaveBeenCalled();
        expect(mockReplay).not.toHaveBeenCalled();
    });

    it('loads the simulation once and the replay on each control change', async () => {
        const user = userEvent.setup();
        render(<BondTentTab scenarioId="s1" hasTent />);

        expect(await screen.findByRole('heading', { name: /Across 5,000 simulations/ })).toBeInTheDocument();
        await screen.findByRole('heading', { name: 'Historical crash replay' });
        await waitFor(() => expect(mockReplay).toHaveBeenCalledWith('s1', { crash: 'oil_shock_1973' }, 0));
        expect(screen.getByText(/Replays use actual inflation-adjusted returns/)).toBeInTheDocument();

        await user.selectOptions(screen.getByLabelText('Crash'), 'financial_crisis_2008');
        await waitFor(() => expect(mockReplay).toHaveBeenLastCalledWith('s1', { crash: 'financial_crisis_2008' }, 0));
        await user.click(screen.getByRole('button', { name: '+2' }));
        await waitFor(() => expect(mockReplay).toHaveBeenLastCalledWith('s1', { crash: 'financial_crisis_2008' }, 2));

        expect(mockSim).toHaveBeenCalledTimes(1);
    });

    it('defaults the timing to the first available offset when at-retirement is unavailable', async () => {
        mockReplay.mockImplementation(async (_id, _choice, offset) =>
            makeReplay({ offset, available_offsets: [1, 2, 3] }));
        render(<BondTentTab scenarioId="s1" hasTent />);

        await waitFor(() => expect(mockReplay).toHaveBeenLastCalledWith('s1', { crash: 'oil_shock_1973' }, 1));
        expect(screen.getByRole('button', { name: '+1' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('disables timings before today', async () => {
        mockReplay.mockResolvedValue(makeReplay({ available_offsets: [0, 1, 2, 3] }));
        render(<BondTentTab scenarioId="s1" hasTent />);

        await waitFor(() => expect(screen.getByRole('button', { name: '−3' })).toBeDisabled());
        expect(screen.getByRole('button', { name: '−1' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'At retirement' })).toBeEnabled();
        expect(screen.getByRole('button', { name: '+3' })).toBeEnabled();
    });

    it('asks for a start year when Any start year is chosen', async () => {
        const user = userEvent.setup();
        render(<BondTentTab scenarioId="s1" hasTent />);
        await screen.findByRole('heading', { name: 'Historical crash replay' });

        await user.selectOptions(screen.getByLabelText('Crash'), 'any');
        const input = screen.getByLabelText('Start year');
        expect(input).toHaveAttribute('min', '1954');
        expect(input).toHaveAttribute('max', '2025');
        await user.clear(input);
        await user.type(input, '1987');

        await waitFor(() => expect(mockReplay).toHaveBeenLastCalledWith('s1', { start_year: 1987 }, 0));
    });

    it('rejects a start year outside the history without requesting a replay', async () => {
        const user = userEvent.setup();
        render(<BondTentTab scenarioId="s1" hasTent />);
        await screen.findByRole('heading', { name: 'Historical crash replay' });
        await user.selectOptions(screen.getByLabelText('Crash'), 'any');
        await waitFor(() => expect(mockReplay).toHaveBeenLastCalledWith('s1', { start_year: 2000 }, 0));
        mockReplay.mockClear();

        const input = screen.getByLabelText('Start year');
        await user.clear(input);
        await user.type(input, '1900');

        expect(await screen.findByText('Start year must be between 1954 and 2025')).toBeInTheDocument();
        expect(mockReplay).not.toHaveBeenCalled();
    });

    it('says there is nothing to simulate when the plan has no years left', async () => {
        mockSim.mockResolvedValue({ trial_count: 0, planned_years: 0, columns: [] });
        render(<BondTentTab scenarioId="s1" hasTent />);

        expect(await screen.findByText(
            'Nothing to simulate: retirement is at or past the end of the plan.')).toBeInTheDocument();
        expect(screen.queryByRole('heading', { name: /Across/ })).not.toBeInTheDocument();
    });

    it('keeps the controls usable when a replay fails', async () => {
        mockReplay.mockRejectedValue({ response: { data: { message: 'That timing would place the crash before today' } } });
        render(<BondTentTab scenarioId="s1" hasTent />);

        expect(await screen.findByText('That timing would place the crash before today')).toBeInTheDocument();
        expect(screen.getByLabelText('Crash')).toBeEnabled();
        expect(screen.getByRole('button', { name: '+1' })).toBeEnabled();
    });

    it('shows an error when the simulation fails', async () => {
        mockSim.mockRejectedValue(new Error('boom'));
        render(<BondTentTab scenarioId="s1" hasTent />);

        expect(await screen.findByText('boom')).toBeInTheDocument();
    });
});
