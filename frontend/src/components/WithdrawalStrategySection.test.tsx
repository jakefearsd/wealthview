import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('./HelpText', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));
vi.mock('./InfoSection', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('../utils/styles', () => ({
    inputStyle: {},
    labelStyle: {},
}));

import WithdrawalStrategySection from './WithdrawalStrategySection';

const baseProps = {
    withdrawalStrategy: 'fixed_percentage',
    onWithdrawalStrategyChange: vi.fn(),
    dynamicCeiling: 5,
    onDynamicCeilingChange: vi.fn(),
    dynamicFloor: -2.5,
    onDynamicFloorChange: vi.fn(),
    withdrawalOrder: 'taxable_first',
    onWithdrawalOrderChange: vi.fn(),
    dynamicSequencingBracketRate: 0.12,
    onDynamicSequencingBracketRateChange: vi.fn(),
};

describe('WithdrawalStrategySection', () => {
    it('renders all three strategy cards', () => {
        render(<WithdrawalStrategySection {...baseProps} />);
        expect(screen.getByText(/Fixed Percentage/)).toBeInTheDocument();
        expect(screen.getByText(/Dynamic Percentage/)).toBeInTheDocument();
        expect(screen.getByText(/Vanguard Dynamic Spending/)).toBeInTheDocument();
    });

    it('shows ceiling/floor inputs only under vanguard strategy', () => {
        const { rerender } = render(<WithdrawalStrategySection {...baseProps} />);
        expect(screen.queryByText(/Ceiling \(max increase/)).not.toBeInTheDocument();

        rerender(<WithdrawalStrategySection {...baseProps} withdrawalStrategy="vanguard_dynamic_spending" />);
        expect(screen.getByText(/Ceiling \(max increase/)).toBeInTheDocument();
        expect(screen.getByText(/Floor \(max decrease/)).toBeInTheDocument();
    });

    it('shows the bracket ceiling when dynamic_sequencing is selected', () => {
        render(<WithdrawalStrategySection {...baseProps} withdrawalOrder="dynamic_sequencing" />);
        expect(screen.getByText('Traditional Withdrawal Bracket Ceiling')).toBeInTheDocument();
    });

    it('clicking a withdrawal-order card fires the change callback', () => {
        const onChange = vi.fn();
        render(<WithdrawalStrategySection {...baseProps} onWithdrawalOrderChange={onChange} />);
        fireEvent.click(screen.getByText(/Dynamic Sequencing/));
        expect(onChange).toHaveBeenCalledWith('dynamic_sequencing');
    });
});
