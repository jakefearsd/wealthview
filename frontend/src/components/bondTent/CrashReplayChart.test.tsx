import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { makeReplay, makeReplayOutcome, makeReplayYear } from '../../testutil/builders';

vi.mock('recharts');

import CrashReplayChart from './CrashReplayChart';

describe('CrashReplayChart', () => {
    it('draws one line per outcome and marks retirement and the crash', () => {
        render(<CrashReplayChart replay={makeReplay()} />);

        const lines = screen.getAllByTestId('line');
        expect(lines.map(l => l.getAttribute('data-name'))).toEqual(['No tent', 'Plan mix']);
        const marker = screen.getByTestId('reference-line');
        expect(marker).toHaveAttribute('data-label', 'Retire 2045');
        const area = screen.getByTestId('reference-area');
        expect(area).toHaveAttribute('data-x1', '2044.5');
        expect(area).toHaveAttribute('data-x2', '2047.5');
        expect(area).toHaveAttribute('data-label', '2000–02');
        expect(screen.getAllByTestId('reference-line')).toHaveLength(1);
    });

    it('gives a one-year crash window a visible width', () => {
        render(<CrashReplayChart replay={makeReplay({ crash_first_year: 2022, crash_last_year: 2022 })} />);

        const area = screen.getByTestId('reference-area');
        expect(area).toHaveAttribute('data-x1', '2044.5');
        expect(area).toHaveAttribute('data-x2', '2045.5');
    });

    it('keeps two mixes with the same name as distinct series', () => {
        const same = (balance: number) => makeReplayOutcome({
            name: 'year',
            years: [makeReplayYear({ year: 2045, balance }), makeReplayYear({ year: 2046, balance })],
        });
        render(<CrashReplayChart replay={makeReplay({ outcomes: [same(100), same(200)] })} />);

        const lines = screen.getAllByTestId('line');
        expect(lines.map(l => l.getAttribute('data-key'))).toEqual(['s0', 's1']);
        expect(lines.map(l => l.getAttribute('data-name'))).toEqual(['year', 'year']);
    });

    it('marks where history ends', () => {
        const years = [
            makeReplayYear({ year: 2045 }),
            makeReplayYear({ year: 2048, history_year: 2003 }),
            makeReplayYear({ year: 2049, history_year: null }),
            makeReplayYear({ year: 2050, history_year: null }),
        ];
        const replay = makeReplay({ outcomes: [makeReplayOutcome({ years })] });
        render(<CrashReplayChart replay={replay} />);

        const labels = screen.getAllByTestId('reference-line').map(l => l.getAttribute('data-label'));
        expect(labels).toEqual(['Retire 2045', 'History ends']);
    });
});
