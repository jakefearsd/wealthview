import { render, screen, within } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import CrashReplayTable from './CrashReplayTable';
import { makeReplay } from '../../testutil/builders';

describe('CrashReplayTable', () => {
    it('renders the five rows with the planned years and checkpoint age', () => {
        render(<CrashReplayTable replay={makeReplay()} />);

        const rowOf = (name: RegExp) => within(screen.getByRole('row', { name })).getAllByRole('cell').map(c => c.textContent);
        expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual(['', 'No tent', 'Plan mix']);
        expect(rowOf(/Lowest balance/)).toEqual(['$800,000', '$850,000']);
        expect(rowOf(/Stocks sold while down/)).toEqual(['$50,000', '$0']);
        expect(rowOf(/Years covered \(planned 25\)/)).toEqual(['—', '4.5']);
        expect(rowOf(/Tent left over at 70/)).toEqual(['—', '$120,000']);
        expect(rowOf(/Balance at 90/)).toEqual(['$900,000', '$950,000']);
    });

    it('shows a dash for figures the horizon never reaches', () => {
        const replay = makeReplay();
        replay.outcomes[1] = { ...replay.outcomes[1], balance_at90: null, tent_left_at_checkpoint: null };
        render(<CrashReplayTable replay={replay} />);

        const row = screen.getByRole('row', { name: /Balance at 90/ });
        expect(within(row).getAllByRole('cell')[1]).toHaveTextContent('—');
        const left = screen.getByRole('row', { name: /Tent left over at 70/ });
        expect(within(left).getAllByRole('cell')[1]).toHaveTextContent('—');
    });
});
