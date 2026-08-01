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

    // === add-phase age derivation ===
    //
    // A new phase must start the year after the previous one ends, otherwise the optimiser sees a
    // gap (or an overlap) in the spending timeline. The derivation has three cases and none was
    // covered — the existing add test only asserted a row appeared.

    it('starts the first phase at 63', () => {
        // Characterises current behaviour rather than endorsing it. The 62 in addPhase is a
        // synthetic "the previous phase ended at 62", and every branch then adds 1 — so the FIRST
        // phase opens at 63, not at the 62 the constant reads as. Possibly an off-by-one, but it
        // is existing behaviour and changing it would silently shift every new plan's first
        // spending year, so it is pinned here and raised separately.
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[]} onPhasesChange={onPhasesChange} />);

        fireEvent.click(screen.getByRole('button', { name: /Add Phase/i }));

        expect(onPhasesChange).toHaveBeenCalledWith([
            expect.objectContaining({ name: 'Phase 1', start_age: 63, end_age: null }),
        ]);
    });

    it('starts a new phase the year after the previous one ends', () => {
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[phase1]} onPhasesChange={onPhasesChange} />);

        fireEvent.click(screen.getByRole('button', { name: /Add Phase/i }));

        expect(onPhasesChange).toHaveBeenCalledWith([
            phase1,
            expect.objectContaining({ name: 'Phase 2', start_age: 71 }),
        ]);
    });

    it('assumes a ten-year span when the previous phase is open-ended', () => {
        const openEnded = { ...phase1, end_age: null };
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[openEnded]} onPhasesChange={onPhasesChange} />);

        fireEvent.click(screen.getByRole('button', { name: /Add Phase/i }));

        // 62 + 10 = 72, so the next phase opens at 73.
        expect(onPhasesChange).toHaveBeenCalledWith([
            openEnded,
            expect.objectContaining({ start_age: 73 }),
        ]);
    });

    // === field editing ===

    it('clears the end age to null when the field is emptied', () => {
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[phase1]} onPhasesChange={onPhasesChange} />);

        fireEvent.change(screen.getByDisplayValue('70'), { target: { value: '' } });

        expect(onPhasesChange).toHaveBeenCalledWith([
            expect.objectContaining({ end_age: null }),
        ]);
    });

    it('clears the target spending to null when the field is emptied', () => {
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[phase1]} onPhasesChange={onPhasesChange} />);

        fireEvent.change(screen.getByTestId('currency-input'), { target: { value: '' } });

        expect(onPhasesChange).toHaveBeenCalledWith([
            expect.objectContaining({ target_spending: null }),
        ]);
    });

    it('coerces the start age to a number rather than a string', () => {
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[phase1]} onPhasesChange={onPhasesChange} />);

        fireEvent.change(screen.getByDisplayValue('62'), { target: { value: '65' } });

        expect(onPhasesChange).toHaveBeenCalledWith([
            expect.objectContaining({ start_age: 65 }),
        ]);
    });

    // === drag to reorder ===
    //
    // Phase ORDER is meaningful — it is the spending timeline — so reordering is a real edit, not
    // a cosmetic one. None of the drag handlers were executed by any test.

    const phase2: Phase = { name: 'Slow-Go', start_age: 71, end_age: 80, priority_weight: 2, target_spending: 60000 };
    const rows = () => screen.getAllByTitle('Drag to reorder').map((el) => el.parentElement!);

    it('reorders phases when one is dropped on another', () => {
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[phase1, phase2]} onPhasesChange={onPhasesChange} />);
        const [first, second] = rows();

        fireEvent.dragStart(first);
        fireEvent.dragOver(second);
        fireEvent.drop(second);

        expect(onPhasesChange).toHaveBeenCalledWith([phase2, phase1]);
    });

    it('does nothing when a phase is dropped back on itself', () => {
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[phase1, phase2]} onPhasesChange={onPhasesChange} />);
        const [first] = rows();

        fireEvent.dragStart(first);
        fireEvent.drop(first);

        expect(onPhasesChange).not.toHaveBeenCalled();
    });

    it('ignores a drop that was never preceded by a drag', () => {
        const onPhasesChange = vi.fn();
        render(<PhaseEditor phases={[phase1, phase2]} onPhasesChange={onPhasesChange} />);
        const [, second] = rows();

        fireEvent.drop(second);

        expect(onPhasesChange).not.toHaveBeenCalled();
    });

    it('highlights the row being dragged over, and clears it when the drag ends', () => {
        render(<PhaseEditor phases={[phase1, phase2]} onPhasesChange={vi.fn()} />);
        const [first, second] = rows();

        fireEvent.dragStart(first);
        fireEvent.dragOver(second);
        expect(second).toHaveStyle({ background: '#e3f2fd' });

        fireEvent.dragEnd(first);
        expect(second).not.toHaveStyle({ background: '#e3f2fd' });
    });
});
