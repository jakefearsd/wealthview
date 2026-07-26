import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import SegmentedControl from './SegmentedControl';

type Choice = 'a' | 'b' | 'c';

const OPTIONS = [
    { value: 'a' as Choice, label: 'Option A' },
    { value: 'b' as Choice, label: 'Option B' },
    { value: 'c' as Choice, label: 'Option C' },
];

describe('SegmentedControl', () => {
    it('renders one button per option, in order', () => {
        render(<SegmentedControl options={OPTIONS} value="a" onChange={vi.fn()} />);

        const buttons = screen.getAllByRole('button');
        expect(buttons.map(b => b.textContent)).toEqual(['Option A', 'Option B', 'Option C']);
    });

    it('marks only the button matching the current value as active', () => {
        render(<SegmentedControl options={OPTIONS} value="b" onChange={vi.fn()} />);

        expect(screen.getByRole('button', { name: 'Option A' })).toHaveStyle({ background: '#fff' });
        expect(screen.getByRole('button', { name: 'Option B' })).toHaveStyle({ background: '#1976d2' });
        expect(screen.getByRole('button', { name: 'Option C' })).toHaveStyle({ background: '#fff' });
    });

    it('calls onChange with the clicked option value', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<SegmentedControl options={OPTIONS} value="a" onChange={onChange} />);

        await user.click(screen.getByRole('button', { name: 'Option C' }));

        expect(onChange).toHaveBeenCalledWith('c');
        expect(onChange).toHaveBeenCalledTimes(1);
    });

    it('still fires onChange when clicking the already-active option', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<SegmentedControl options={OPTIONS} value="a" onChange={onChange} />);

        await user.click(screen.getByRole('button', { name: 'Option A' }));

        expect(onChange).toHaveBeenCalledWith('a');
    });

    it('defaults to the compact variant: auto-width pills with a divider between them', () => {
        render(<SegmentedControl options={OPTIONS} value="a" onChange={vi.fn()} />);

        const button = screen.getByRole('button', { name: 'Option A' });
        expect(button).toHaveStyle({ borderRight: '1px solid #ddd' });
        expect(button.style.flex).toBe('');
    });

    it('the wide variant gives every option equal flex width with no divider', () => {
        render(<SegmentedControl options={OPTIONS} value="a" onChange={vi.fn()} variant="wide" />);

        const button = screen.getByRole('button', { name: 'Option A' });
        expect(button).toHaveStyle({ flex: '1' });
        expect(button.style.borderRightStyle).toBe('none');
    });
});
