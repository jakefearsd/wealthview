import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import type { GuardrailYearlySpending } from '../types/projection';
import NearTermSpendingGuide from './NearTermSpendingGuide';

function makeYear(overrides: Partial<GuardrailYearlySpending> & { year: number; age: number }): GuardrailYearlySpending {
    return {
        recommended: 70000,
        corridor_low: 60000,
        corridor_high: 85000,
        essential_floor: 30000,
        discretionary: 40000,
        income_offset: 5000,
        portfolio_withdrawal: 65000,
        phase_name: 'Early',
        portfolio_balance_median: 2300000,
        portfolio_balance_p10: 1200000,
        portfolio_balance_p25: 1800000,
        ...overrides,
    };
}

const fiveYears: GuardrailYearlySpending[] = [
    makeYear({ year: 2030, age: 62, recommended: 70000, corridor_low: 60000, corridor_high: 85000 }),
    makeYear({ year: 2031, age: 63, recommended: 72000, corridor_low: 61000, corridor_high: 86000 }),
    makeYear({ year: 2032, age: 64, recommended: 71000, corridor_low: 60500, corridor_high: 85500 }),
    makeYear({ year: 2033, age: 65, recommended: 73000, corridor_low: 62000, corridor_high: 87000 }),
    makeYear({ year: 2034, age: 66, recommended: 74000, corridor_low: 63000, corridor_high: 88000 }),
];

describe('NearTermSpendingGuide', () => {
    it('returns null when no yearly spending data', () => {
        const { container } = render(
            <NearTermSpendingGuide yearlySpending={[]} retirementDate="2030-01-01" />,
        );
        expect(container.firstChild).toBeNull();
    });

    it('renders 5 year cards when 5+ years of data exist', () => {
        const sevenYears = [
            ...fiveYears,
            makeYear({ year: 2035, age: 67, recommended: 75000 }),
            makeYear({ year: 2036, age: 68, recommended: 76000 }),
        ];
        render(
            <NearTermSpendingGuide yearlySpending={sevenYears} retirementDate="2020-01-01" />,
        );
        expect(screen.getByTestId('hero-card')).toBeInTheDocument();
        expect(screen.getAllByTestId('compact-card')).toHaveLength(4);
    });

    it('hero card displays recommended spending and breakdown', () => {
        render(
            <NearTermSpendingGuide yearlySpending={fiveYears} retirementDate="2020-01-01" />,
        );
        const hero = screen.getByTestId('hero-card');
        expect(hero).toHaveTextContent('$70,000');
        expect(hero).toHaveTextContent('recommended');
        expect(hero).toHaveTextContent('Age 62');
        expect(hero).toHaveTextContent('Year 1');
    });

    it('p50 uses 4% heuristic when it exceeds recommended', () => {
        // portfolio_balance_median = $2.3M, 4% = $92K > recommended $70K
        render(
            <NearTermSpendingGuide yearlySpending={fiveYears} retirementDate="2020-01-01" />,
        );
        const hero = screen.getByTestId('hero-card');
        expect(hero).toHaveTextContent('$92,000'); // 2,300,000 * 0.04
        expect(hero).toHaveTextContent('4% of portfolio');
    });

    it('p50 uses recommended when 4% rule is lower', () => {
        // portfolio_balance_median = $1M, 4% = $40K < recommended $70K
        const years = [makeYear({ year: 2030, age: 62, recommended: 70000, portfolio_balance_median: 1000000 })];
        render(
            <NearTermSpendingGuide yearlySpending={years} retirementDate="2020-01-01" />,
        );
        // Should show $70,000 (recommended wins) and NOT show "4% rule" label
        const spendingSection = screen.getByTestId('spending-by-portfolio');
        expect(spendingSection).toHaveTextContent('$70,000');
        expect(spendingSection).not.toHaveTextContent('4% of portfolio');
    });

    it('shows spending-by-portfolio on all cards', () => {
        render(
            <NearTermSpendingGuide yearlySpending={fiveYears} retirementDate="2020-01-01" />,
        );
        const sections = screen.getAllByTestId('spending-by-portfolio');
        expect(sections.length).toBe(5);
    });

    it('shows year-over-year delta on compact cards', () => {
        render(
            <NearTermSpendingGuide yearlySpending={fiveYears} retirementDate="2020-01-01" />,
        );
        const compactCards = screen.getAllByTestId('compact-card');
        expect(compactCards[0]).toHaveTextContent('$2,000');
        expect(compactCards[0]).toHaveTextContent('\u2191');
    });

    it('highlights phase transitions between years', () => {
        const years = [
            makeYear({ year: 2030, age: 69, phase_name: 'Early' }),
            makeYear({ year: 2031, age: 70, phase_name: 'Early' }),
            makeYear({ year: 2032, age: 71, phase_name: 'Late' }),
        ];
        render(
            <NearTermSpendingGuide yearlySpending={years} retirementDate="2020-01-01" />,
        );
        expect(screen.getByTestId('phase-transition')).toBeInTheDocument();
    });

    it('shows pre-retirement subtitle when retirement date is in the future', () => {
        const futureYear = new Date().getFullYear() + 3;
        render(
            <NearTermSpendingGuide yearlySpending={fiveYears} retirementDate={`${futureYear}-01-01`} />,
        );
        expect(screen.getByText(/You retire in/)).toBeInTheDocument();
    });

    it('shows post-retirement subtitle when already retired', () => {
        render(
            <NearTermSpendingGuide yearlySpending={fiveYears} retirementDate="2020-01-01" />,
        );
        expect(screen.getByText(/Your spending guide for the next 5 years/)).toBeInTheDocument();
    });
});
