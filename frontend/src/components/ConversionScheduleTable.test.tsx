import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/styles', () => ({
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

import ConversionScheduleTable from './ConversionScheduleTable';

const year = {
    calendar_year: 2030,
    age: 65,
    conversion_amount: 50000,
    estimated_tax: 8500,
    traditional_balance_after: 1_200_000,
    roth_balance_after: 250_000,
    projected_rmd: 0,
    other_income: 0,
    total_taxable_income: 50000,
    bracket_used: '12%',
};

describe('ConversionScheduleTable', () => {
    it('renders an empty-state message when no years provided', () => {
        render(<ConversionScheduleTable years={[]} />);
        expect(screen.getByText(/No conversion schedule/i)).toBeInTheDocument();
    });

    it('renders one row per schedule year', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<ConversionScheduleTable years={[year] as any} />);
        expect(screen.getByText('65')).toBeInTheDocument();
        expect(screen.getByText('2030')).toBeInTheDocument();
        expect(screen.getAllByText('$50,000').length).toBeGreaterThanOrEqual(1);
        expect(screen.getByText('12%')).toBeInTheDocument();
    });

    it('renders dashes for zero-valued cells', () => {
        const zeroYear = { ...year, conversion_amount: 0, estimated_tax: 0 };
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<ConversionScheduleTable years={[zeroYear] as any} />);
        // Two dashes should appear: conversion + tax
        expect(screen.getAllByText('--').length).toBeGreaterThanOrEqual(2);
    });
});
