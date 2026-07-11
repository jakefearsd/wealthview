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

vi.mock('../utils/styles', () => ({ inputStyle: {}, labelStyle: {}, inputFieldStyle: {} }));

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
import type { Scenario, ProjectionAccount } from '../types/projection';

const mockUseApiQuery = vi.mocked(useApiQuery);

const spendingProfile = { id: 'sp-1', name: 'Base Plan', essential_expenses: 50000, discretionary_expenses: 20000 };

function makeScenario(account: Partial<ProjectionAccount>): Scenario {
    return {
        id: 'sc-1',
        name: 'Existing Plan',
        retirement_date: '2045-01-01',
        end_age: 90,
        inflation_rate: 0.03,
        params_json: null,
        accounts: [{
            id: 'a1', linked_account_id: null, name: 'Brokerage', initial_balance: 100000,
            annual_contribution: 10000, expected_return: null, account_type: 'taxable',
            cost_basis: null, allocation: null, allocation_is_override: false, ...account,
        }],
        spending_profile: null,
        guardrail_profile: null,
        income_sources: [],
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-01T00:00:00Z',
    };
}

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

// The mocked FormField renders its label and children as siblings (no htmlFor), so getByLabelText
// can't resolve it — locate the Override Return input via its unique label text's wrapper instead.
function overrideReturnInput(): HTMLInputElement {
    const label = screen.getByText('Override Return (%)');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Override Return input not found');
    }
    return input as HTMLInputElement;
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

    it('customizes an account allocation and submits it as an override', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.click(screen.getByText(/customize allocation/i));
        fireEvent.change(screen.getByLabelText(/US Stocks/i), { target: { value: '70' } });
        fireEvent.change(screen.getByLabelText(/Intl Stocks/i), { target: { value: '20' } });
        fireEvent.change(screen.getByLabelText(/Bonds/i), { target: { value: '5' } });
        fireEvent.change(screen.getByLabelText(/Cash/i), { target: { value: '5' } });

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.accounts[0].allocation).toEqual({ us_stock: 70, intl_stock: 20, bond: 5, cash: 5 });
    });

    it('sends allocation null for an account left in the derived state', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.accounts[0].allocation).toBeNull();
    });

    it('hydrates a null expected_return to a blank override and omits it on submit', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm initialValues={makeScenario({ expected_return: null })} onSubmit={onSubmit} submitLabel="Save" />);

        expect(overrideReturnInput().value).toBe('');

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.accounts[0].expected_return).toBeUndefined();
    });

    it('round-trips a genuine 0% expected_return override as 0, not dropped', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        // expected_return stored as a decimal 0; toPercent(0) = 0, so the field shows "0".
        render(<ScenarioForm initialValues={makeScenario({ expected_return: 0 })} onSubmit={onSubmit} submitLabel="Save" />);

        expect(overrideReturnInput().value).toBe('0');

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.accounts[0].expected_return).toBe(0);
    });
});
