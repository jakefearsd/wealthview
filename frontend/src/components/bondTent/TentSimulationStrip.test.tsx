import { render, screen, within } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import TentSimulationStrip from './TentSimulationStrip';
import { makeSimulation } from '../../testutil/builders';

describe('TentSimulationStrip', () => {
    it('renders one column per choice with success, coverage and balance', () => {
        render(<TentSimulationStrip simulation={makeSimulation()} />);

        expect(screen.getByRole('heading', { name: 'Across 5,000 simulations (starting today)' })).toBeInTheDocument();
        const headers = screen.getAllByRole('columnheader').map(h => h.textContent);
        expect(headers).toEqual(['', 'No tent', 'Plan mix$300,000 tent']);
        const success = screen.getByRole('row', { name: /Success rate/ });
        expect(within(success).getAllByRole('cell').map(c => c.textContent)).toEqual(['82%', '90%']);
        const covered = screen.getByRole('row', { name: /Years covered, typical \/ worst 10%/ });
        expect(within(covered).getAllByRole('cell')[1]).toHaveTextContent('4.3 / 2.0');
        const balance = screen.getByRole('row', { name: /Balance at 90, typical/ });
        expect(within(balance).getAllByRole('cell').map(c => c.textContent)).toEqual(['$400,000', '$450,000']);
        expect(screen.getByText("Success means every year's planned spending is paid in full.")).toBeInTheDocument();
    });

    it('shows a dash for figures the horizon never reaches', () => {
        const sim = makeSimulation();
        sim.columns[1] = { ...sim.columns[1], years_covered_median: null, balance_at90_median: null };
        render(<TentSimulationStrip simulation={sim} />);

        const covered = screen.getByRole('row', { name: /Years covered, typical/ });
        expect(within(covered).getAllByRole('cell')[1]).toHaveTextContent('— / 2.0');
        const balance = screen.getByRole('row', { name: /Balance at 90, typical/ });
        expect(within(balance).getAllByRole('cell')[1]).toHaveTextContent('—');
    });

    it('renders a column for each of two mixes that share a name', () => {
        const sim = makeSimulation();
        sim.columns = [
            { ...sim.columns[1], name: 'Mix', success_rate: 0.5 },
            { ...sim.columns[1], name: 'Mix', success_rate: 0.7 },
        ];
        render(<TentSimulationStrip simulation={sim} />);

        const success = screen.getByRole('row', { name: /Success rate/ });
        expect(within(success).getAllByRole('cell').map(c => c.textContent)).toEqual(['50%', '70%']);
    });
});
