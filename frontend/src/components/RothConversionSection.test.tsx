import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('./HelpText', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));
vi.mock('./InfoSection', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('./CurrencyInput', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({ value, onChange, style }: any) => (
        <input
            data-testid="currency-input"
            value={value ?? ''}
            style={style}
            onChange={(e) => onChange(e.target.value)}
        />
    ),
}));
vi.mock('../utils/styles', () => ({
    inputStyle: {},
    labelStyle: {},
}));

import RothConversionSection from './RothConversionSection';

const baseProps = {
    rothConversionStrategy: 'fixed_amount',
    onRothConversionStrategyChange: vi.fn(),
    annualRothConversion: 25000,
    onAnnualRothConversionChange: vi.fn(),
    targetBracketRate: 22,
    onTargetBracketRateChange: vi.fn(),
    rothConversionStartYear: null,
    onRothConversionStartYearChange: vi.fn(),
    filingStatus: 'single',
    onFilingStatusChange: vi.fn(),
    otherIncome: 0,
    onOtherIncomeChange: vi.fn(),
};

describe('RothConversionSection', () => {
    it('renders both strategy cards', () => {
        render(<RothConversionSection {...baseProps} />);
        expect(screen.getByText('Fixed Amount')).toBeInTheDocument();
        expect(screen.getByText('Fill Tax Bracket')).toBeInTheDocument();
    });

    it('shows the fixed-amount input when fixed_amount is selected', () => {
        render(<RothConversionSection {...baseProps} />);
        expect(screen.getByText('Annual Roth Conversion')).toBeInTheDocument();
    });

    it('shows the target-bracket select when fill_bracket is selected', () => {
        render(<RothConversionSection {...baseProps} rothConversionStrategy="fill_bracket" />);
        expect(screen.getByText('Target Tax Bracket')).toBeInTheDocument();
    });

    it('invokes strategy change when a card is clicked', () => {
        const onChange = vi.fn();
        render(<RothConversionSection {...baseProps} onRothConversionStrategyChange={onChange} />);
        fireEvent.click(screen.getByText('Fill Tax Bracket'));
        expect(onChange).toHaveBeenCalledWith('fill_bracket');
    });

    it('hides extra inputs when annualRothConversion is 0 under fixed_amount', () => {
        render(<RothConversionSection {...baseProps} annualRothConversion={0} />);
        // When no conversion is happening, the advanced fields should be hidden
        expect(screen.queryByText('Filing Status')).not.toBeInTheDocument();
    });
});
