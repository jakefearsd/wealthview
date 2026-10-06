import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import OptionCard from './OptionCard';

describe('OptionCard', () => {
    it('is exposed as a radio carrying its checked state and its text as the name', () => {
        render(
            <div role="radiogroup" aria-label="Choice">
                <OptionCard selected onSelect={vi.fn()}>Fixed Amount</OptionCard>
                <OptionCard selected={false} onSelect={vi.fn()}>Fill Bracket</OptionCard>
            </div>,
        );

        expect(screen.getByRole('radio', { name: 'Fixed Amount' })).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByRole('radio', { name: 'Fill Bracket' })).toHaveAttribute('aria-checked', 'false');
    });

    it('is reachable with the keyboard', () => {
        render(<OptionCard selected={false} onSelect={vi.fn()}>Option</OptionCard>);

        expect(screen.getByRole('radio')).toHaveAttribute('tabindex', '0');
    });

    it.each(['Enter', ' '])('selects on the %j key', (key) => {
        const onSelect = vi.fn();
        render(<OptionCard selected={false} onSelect={onSelect}>Option</OptionCard>);

        fireEvent.keyDown(screen.getByRole('radio'), { key });

        expect(onSelect).toHaveBeenCalledTimes(1);
    });

    it('ignores other keys', () => {
        const onSelect = vi.fn();
        render(<OptionCard selected={false} onSelect={onSelect}>Option</OptionCard>);

        fireEvent.keyDown(screen.getByRole('radio'), { key: 'a' });

        expect(onSelect).not.toHaveBeenCalled();
    });

    it('selects on click', () => {
        const onSelect = vi.fn();
        render(<OptionCard selected={false} onSelect={onSelect}>Option</OptionCard>);

        fireEvent.click(screen.getByRole('radio'));

        expect(onSelect).toHaveBeenCalledTimes(1);
    });
});
