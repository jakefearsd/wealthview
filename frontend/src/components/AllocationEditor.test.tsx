import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AllocationEditor, { isAllocationValid } from './AllocationEditor';

describe('AllocationEditor', () => {
    it('shows the running total and flags a non-100 sum', () => {
        render(<AllocationEditor value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 10 }} onChange={vi.fn()} />);
        expect(screen.getByText(/105\s*%/)).toBeInTheDocument();
        expect(screen.getByText(/must sum to 100/i)).toBeInTheDocument();
    });

    it('emits the edited allocation on change', () => {
        const onChange = vi.fn();
        render(<AllocationEditor value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 }} onChange={onChange} />);
        fireEvent.change(screen.getByLabelText(/US Stocks/i), { target: { value: '70' } });
        expect(onChange).toHaveBeenCalledWith({ us_stock: 70, intl_stock: 20, bond: 15, cash: 5 });
    });

    it('does not flag a sum of exactly 100', () => {
        render(<AllocationEditor value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 }} onChange={vi.fn()} />);
        expect(screen.getByText(/100\s*%/)).toBeInTheDocument();
        expect(screen.queryByText(/must sum to 100/i)).not.toBeInTheDocument();
    });

    it('calls onReset when the reset-to-derived button is clicked', () => {
        const onReset = vi.fn();
        render(
            <AllocationEditor
                value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 }}
                onChange={vi.fn()}
                onReset={onReset}
            />
        );
        fireEvent.click(screen.getByText(/reset to derived/i));
        expect(onReset).toHaveBeenCalled();
    });

    it('does not render a reset button when onReset is not provided', () => {
        render(<AllocationEditor value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 }} onChange={vi.fn()} />);
        expect(screen.queryByText(/reset to derived/i)).not.toBeInTheDocument();
    });

    it('namespaces input ids with idPrefix so multiple editors do not collide', () => {
        const { container } = render(
            <AllocationEditor
                value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 }}
                onChange={vi.fn()}
                idPrefix="acct-1-"
            />
        );
        expect(container.querySelector('#acct-1-allocation-us_stock')).not.toBeNull();
        expect(container.querySelector('#allocation-us_stock')).toBeNull();
        // getByLabelText still resolves the label via the prefixed htmlFor/id pair.
        expect(screen.getByLabelText(/US Stocks/i)).toBe(container.querySelector('#acct-1-allocation-us_stock'));
    });

    describe('isAllocationValid', () => {
        it('treats null as valid (derive from holdings)', () => {
            expect(isAllocationValid(null)).toBe(true);
        });

        it('treats a sum of 100 as valid', () => {
            expect(isAllocationValid({ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 })).toBe(true);
        });

        it('treats a sum within 0.01 of 100 as valid', () => {
            expect(isAllocationValid({ us_stock: 60.005, intl_stock: 20, bond: 15, cash: 5 })).toBe(true);
        });

        it('treats a sum far from 100 as invalid', () => {
            expect(isAllocationValid({ us_stock: 60, intl_stock: 20, bond: 15, cash: 10 })).toBe(false);
        });
    });
});
