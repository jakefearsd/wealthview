import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../api/accounts', () => ({ listAccounts: vi.fn() }));
vi.mock('../api/spendingProfiles', () => ({ listSpendingProfiles: vi.fn() }));
vi.mock('../api/incomeSources', () => ({ listIncomeSources: vi.fn() }));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    toPercent: (v: number) => v * 100,
    formatCurrencyInput: (v: string | number) => String(v),
    parseCurrencyInput: (v: string) => v.replace(/,/g, ''),
}));

vi.mock('../utils/styles', () => ({ inputStyle: {} }));

vi.mock('./WithdrawalStrategySection', () => ({
    default: () => <div data-testid="withdrawal-strategy" />,
}));
vi.mock('./RothConversionSection', () => ({
    default: () => <div data-testid="roth-conversion" />,
}));
vi.mock('./FormField', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({ label, children }: any) => <div><label>{label}</label>{children}</div>,
}));
vi.mock('./CurrencyInput', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({ value, onChange, style }: any) => (
        <input value={value ?? ''} style={style} onChange={(e) => onChange(e.target.value)} />
    ),
}));

import { useApiQuery } from '../hooks/useApiQuery';
import ScenarioForm from './ScenarioForm';

const mockUseApiQuery = vi.mocked(useApiQuery);

const spendingProfile = { id: 'sp-1', name: 'Base Plan', essential_expenses: 50000, discretionary_expenses: 20000 };

function setupMocks({ profiles = [spendingProfile], accounts = [], incomeSources = [] } = {}) {
    let call = 0;
    mockUseApiQuery.mockImplementation(() => {
        call++;
        if (call === 1) {
            return { data: profiles, loading: false, error: null, refetch: vi.fn() };
        }
        if (call === 2) {
            return { data: { data: accounts, total: accounts.length, page: 0, page_size: 100 }, loading: false, error: null, refetch: vi.fn() };
        }
        return { data: incomeSources, loading: false, error: null, refetch: vi.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;
}

describe('ScenarioForm', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders the scenario name and retirement date fields', () => {
        setupMocks();
        render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);
        expect(screen.getByPlaceholderText('Retirement Plan')).toBeInTheDocument();
        expect(screen.getByText('Retirement Date')).toBeInTheDocument();
    });

    it('lists spending profiles in the Spending Plan dropdown', () => {
        setupMocks();
        render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);
        expect(screen.getByText('Base Plan')).toBeInTheDocument();
        expect(screen.getByText('None (use withdrawal rate)')).toBeInTheDocument();
    });

    it('delegates strategy and conversion UI to child components', () => {
        setupMocks();
        render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);
        expect(screen.getByTestId('withdrawal-strategy')).toBeInTheDocument();
        expect(screen.getByTestId('roth-conversion')).toBeInTheDocument();
    });

    it('submits a scenario payload when Save is clicked', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(screen.getByPlaceholderText('Retirement Plan'), { target: { value: 'My Plan' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.name).toBe('My Plan');
    });
});
