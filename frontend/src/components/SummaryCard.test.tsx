import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import SummaryCard from './SummaryCard';

describe('SummaryCard', () => {
    it('renders label and value', () => {
        render(<SummaryCard label="Net Worth" value="$100,000" />);
        expect(screen.getByText('Net Worth')).toBeInTheDocument();
        expect(screen.getByText('$100,000')).toBeInTheDocument();
    });

    it('applies custom value color', () => {
        render(<SummaryCard label="Balance" value="$50,000" valueColor="#2e7d32" />);
        const valueEl = screen.getByText('$50,000');
        expect(valueEl).toHaveStyle({ color: '#2e7d32' });
    });

    it('renders optional subtext', () => {
        render(<SummaryCard label="Peak" value="$1,000,000" subtext="(year 2045)" />);
        expect(screen.getByText('(year 2045)')).toBeInTheDocument();
    });
});
