import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('./CurrencyInput', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({ value, onChange, placeholder, style }: any) => (
        <input
            data-testid="currency-input"
            placeholder={placeholder}
            value={value ?? ''}
            style={style}
            onChange={(e) => onChange(e.target.value)}
        />
    ),
}));

import PhaseEditor from './PhaseEditor';

type Phase = {
    name: string;
    start_age: number;
    end_age: number | null;
    priority_weight: number;
    target_spending: number | null;
};

const phase1: Phase = { name: 'Go-Go', start_age: 62, end_age: 70, priority_weight: 2, target_spending: 80000 };

describe('PhaseEditor', () => {
    it('renders one row per phase', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PhaseEditor phases={[phase1] as any} onPhasesChange={vi.fn()} />);
        expect(screen.getByDisplayValue('Go-Go')).toBeInTheDocument();
        expect(screen.getByDisplayValue('62')).toBeInTheDocument();
        expect(screen.getByDisplayValue('70')).toBeInTheDocument();
    });

    it('adds a phase via the + Add Phase button', () => {
        const onPhasesChange = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PhaseEditor phases={[phase1] as any} onPhasesChange={onPhasesChange} />);
        fireEvent.click(screen.getByText('+ Add Phase'));
        expect(onPhasesChange).toHaveBeenCalled();
        const newPhases = onPhasesChange.mock.calls[0][0];
        expect(newPhases.length).toBe(2);
        expect(newPhases[1].start_age).toBe(71); // last end + 1
    });

    it('removes a phase', () => {
        const onPhasesChange = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PhaseEditor phases={[phase1] as any} onPhasesChange={onPhasesChange} />);
        fireEvent.click(screen.getByText('Remove'));
        expect(onPhasesChange).toHaveBeenCalledWith([]);
    });

    it('updates the phase name', () => {
        const onPhasesChange = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PhaseEditor phases={[phase1] as any} onPhasesChange={onPhasesChange} />);
        fireEvent.change(screen.getByDisplayValue('Go-Go'), { target: { value: 'Slow-Go' } });
        expect(onPhasesChange).toHaveBeenCalledWith([expect.objectContaining({ name: 'Slow-Go' })]);
    });
});
