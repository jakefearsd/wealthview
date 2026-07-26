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
import type { Account } from '../types/account';
import { makeAccount, makeScenario as buildScenario } from '../testutil/builders';

const mockUseApiQuery = vi.mocked(useApiQuery);

const spendingProfile = { id: 'sp-1', name: 'Base Plan', essential_expenses: 50000, discretionary_expenses: 20000 };

/** This form only ever exercises a single account, so overrides target that account directly. */
function makeScenario(account: Partial<ProjectionAccount>): Scenario {
    return buildScenario({
        name: 'Existing Plan',
        accounts: [makeAccount({ expected_return: null, ...account })],
    });
}

function setupMocks({ profiles = [spendingProfile], accounts = [] as Account[], incomeSources = [] } = {}) {
    // ScenarioForm calls useApiQuery exactly 3 times per render, always in the same order
    // (profiles, accounts, income sources). Index by position-within-render (call % 3) rather
    // than a raw incrementing counter, so re-renders triggered by fireEvent (which call the
    // hooks again) keep returning the right shaped data instead of drifting into the
    // catch-all branch after the first render.
    let call = 0;
    mockUseApiQuery.mockImplementation(() => {
        const position = call % 3;
        call++;
        if (position === 0) {
            return { data: profiles, loading: false, error: null, refetch: vi.fn() };
        }
        if (position === 1) {
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

function dividendYieldInput(): HTMLInputElement {
    const label = screen.getByText('Dividend Yield (%)');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Dividend Yield input not found');
    }
    return input as HTMLInputElement;
}

function feeRateInput(): HTMLInputElement {
    const label = screen.getByText('Investment Fees (%)');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Investment Fees input not found');
    }
    return input as HTMLInputElement;
}

function interestYieldInput(): HTMLInputElement {
    const label = screen.getByText('Bond Interest Yield (%)');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Bond Interest Yield input not found');
    }
    return input as HTMLInputElement;
}

function includeDepressionYearsCheckbox(): HTMLInputElement {
    const label = screen.getByText('Include 1928–1971 market history');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Include depression years checkbox not found');
    }
    return input as HTMLInputElement;
}

function spouseBirthYearInput(): HTMLInputElement {
    const label = screen.getByText('Spouse Birth Year');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Spouse Birth Year input not found');
    }
    return input as HTMLInputElement;
}

function primaryDeathAgeInput(): HTMLInputElement {
    const label = screen.getByText('Primary Death Age');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Primary Death Age input not found');
    }
    return input as HTMLInputElement;
}

function spouseDeathAgeInput(): HTMLInputElement {
    const label = screen.getByText('Spouse Death Age');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Spouse Death Age input not found');
    }
    return input as HTMLInputElement;
}

function survivorSpendingFactorInput(): HTMLInputElement {
    const label = screen.getByText('Survivor Spending Factor (%)');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Survivor Spending Factor input not found');
    }
    return input as HTMLInputElement;
}

function communityPropertyCheckbox(): HTMLInputElement {
    const label = screen.getByText('Community Property State');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Community Property checkbox not found');
    }
    return input as HTMLInputElement;
}

function stochasticMortalityToggle(): HTMLInputElement {
    const label = screen.getByText('Model Uncertain Lifespans');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Model Uncertain Lifespans toggle not found');
    }
    return input as HTMLInputElement;
}

function primarySexSelect(): HTMLSelectElement {
    const label = screen.getByText('Primary Sex');
    const select = label.parentElement?.querySelector('select');
    if (!select) {
        throw new Error('Primary Sex select not found');
    }
    return select as HTMLSelectElement;
}

function spouseSexSelect(): HTMLSelectElement {
    const label = screen.getByText('Spouse Sex');
    const select = label.parentElement?.querySelector('select');
    if (!select) {
        throw new Error('Spouse Sex select not found');
    }
    return select as HTMLSelectElement;
}

function longevityAgeInput(): HTMLInputElement {
    const label = screen.getByText('Longevity Age');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Longevity Age input not found');
    }
    return input as HTMLInputElement;
}

function ownerSelect(): HTMLSelectElement {
    const label = screen.getByText('Owner');
    const select = label.parentElement?.querySelector('select');
    if (!select) {
        throw new Error('Owner select not found');
    }
    return select as HTMLSelectElement;
}

function accountTypeSelect(): HTMLSelectElement {
    const label = screen.getByText('Account Type');
    const select = label.parentElement?.querySelector('select');
    if (!select) {
        throw new Error('Account Type select not found');
    }
    return select as HTMLSelectElement;
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

    it('shows a caveat that account comparisons do not model the pre-tax wage deduction', () => {
        setupMocks();
        render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);
        expect(screen.getByText(/pre-tax wage deduction/i)).toBeInTheDocument();
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

    it('defaults a brand-new account to no override, omitting expected_return on submit', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        expect(overrideReturnInput().value).toBe('');

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.accounts[0].expected_return).toBeUndefined();
    });

    it('serializes a user-entered override on a new account as a decimal', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(overrideReturnInput(), { target: { value: '5' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.accounts[0].expected_return).toBeCloseTo(0.05);
    });

    it('resets expected_return override when linking an existing account', async () => {
        const existingAccount = { id: 'ext-1', name: 'Fidelity 401k', type: '401k', institution: 'Fidelity', currency: 'USD', balance: 200000, created_at: '2024-01-01T00:00:00Z' };
        setupMocks({ accounts: [existingAccount] });
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(overrideReturnInput(), { target: { value: '9' } });
        expect(overrideReturnInput().value).toBe('9');

        const linkSelect = screen.getByText('Link Existing Account').parentElement?.querySelector('select');
        if (!linkSelect) {
            throw new Error('Link Existing Account select not found');
        }
        fireEvent.change(linkSelect, { target: { value: 'ext-1' } });

        expect(overrideReturnInput().value).toBe('');

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.accounts[0].expected_return).toBeUndefined();
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

    it('submits dividend_yield converted from percent to decimal', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(dividendYieldInput(), { target: { value: '2.1' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.dividend_yield).toBeCloseTo(0.021);
    });

    it('omits dividend_yield when the field is cleared to blank', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(dividendYieldInput(), { target: { value: '' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.dividend_yield).toBeUndefined();
    });

    it('submits fee_rate converted from percent to decimal', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(feeRateInput(), { target: { value: '0.5' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.fee_rate).toBeCloseTo(0.005);
    });

    it('defaults fee_rate to 0.25% when no initial value is present', () => {
        setupMocks();
        render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

        expect(feeRateInput().value).toBe('0.25');
    });

    it('hydrates fee_rate from an existing scenario\'s params_json', () => {
        setupMocks();
        const scenario = makeScenario({});
        scenario.params_json = JSON.stringify({ fee_rate: 0.01 });
        render(<ScenarioForm initialValues={scenario} onSubmit={vi.fn()} submitLabel="Save" />);

        expect(feeRateInput().value).toBe('1');
    });

    it('omits fee_rate when the field is cleared to blank', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(feeRateInput(), { target: { value: '' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.fee_rate).toBeUndefined();
    });

    it('round-trips a genuine 0% fee_rate override as 0, not dropped', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(feeRateInput(), { target: { value: '0' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.fee_rate).toBe(0);
    });

    it('defaults interest_yield to 4.0% when no initial value is present', () => {
        setupMocks();
        render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

        expect(interestYieldInput().value).toBe('4');
    });

    it('hydrates interest_yield from an existing scenario\'s params_json', () => {
        setupMocks();
        const scenario = makeScenario({});
        scenario.params_json = JSON.stringify({ interest_yield: 0.06 });
        render(<ScenarioForm initialValues={scenario} onSubmit={vi.fn()} submitLabel="Save" />);

        expect(interestYieldInput().value).toBe('6');
    });

    it('submits interest_yield converted from percent to decimal', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(interestYieldInput(), { target: { value: '5.5' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.interest_yield).toBeCloseTo(0.055);
    });

    it('omits interest_yield when the field is cleared to blank', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(interestYieldInput(), { target: { value: '' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.interest_yield).toBeUndefined();
    });

    it('round-trips a genuine 0% interest_yield override as 0, not dropped', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.change(interestYieldInput(), { target: { value: '0' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.interest_yield).toBe(0);
    });

    it('submits include_depression_years as false by default', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        expect(includeDepressionYearsCheckbox().checked).toBe(false);

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.include_depression_years).toBe(false);
    });

    it('submits include_depression_years as true when checked', async () => {
        setupMocks();
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

        fireEvent.click(includeDepressionYearsCheckbox());
        expect(includeDepressionYearsCheckbox().checked).toBe(true);

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalled();
        });
        const call = onSubmit.mock.calls[0][0];
        expect(call.include_depression_years).toBe(true);
    });

    it('hydrates include_depression_years from an existing scenario\'s params_json', () => {
        setupMocks();
        const scenario = makeScenario({});
        scenario.params_json = JSON.stringify({ include_depression_years: true });
        render(<ScenarioForm initialValues={scenario} onSubmit={vi.fn()} submitLabel="Save" />);

        expect(includeDepressionYearsCheckbox().checked).toBe(true);
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

    describe('allocation derived summary', () => {
        it('shows the derived mix in the summary for a non-override account', () => {
            setupMocks();
            const scenario = makeScenario({
                allocation: { us_stock: 55, intl_stock: 25, bond: 15, cash: 5 },
                allocation_is_override: false,
            });
            render(<ScenarioForm initialValues={scenario} onSubmit={vi.fn()} submitLabel="Save" />);

            expect(screen.getByText(/Derived from holdings/)).toBeInTheDocument();
            expect(screen.getByText(/55\.0% US \/ 25\.0% Intl \/ 15\.0% Bond \/ 5\.0% Cash/)).toBeInTheDocument();
        });

        it('does not echo a former override as the derived mix after reset-to-derived', () => {
            setupMocks();
            const scenario = makeScenario({
                allocation: { us_stock: 61, intl_stock: 19, bond: 12, cash: 8 },
                allocation_is_override: true,
            });
            render(<ScenarioForm initialValues={scenario} onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.click(screen.getByText(/reset to derived/i));

            // The true holdings-derived mix is unknown until a projection run, so the summary
            // must not present the just-removed override values as if they were derived.
            expect(screen.getByText(/Derived from holdings/)).toBeInTheDocument();
            expect(screen.queryByText(/61\.0% US/)).not.toBeInTheDocument();
        });
    });

    describe('household / survivor modeling', () => {
        it('hides household death-age, survivor, and community-property fields by default (no spouse)', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            expect(spouseBirthYearInput().value).toBe('');
            expect(screen.queryByText('Primary Death Age')).not.toBeInTheDocument();
            expect(screen.queryByText('Spouse Death Age')).not.toBeInTheDocument();
            expect(screen.queryByText('Survivor Spending Factor (%)')).not.toBeInTheDocument();
            expect(screen.queryByText('Community Property State')).not.toBeInTheDocument();
            expect(screen.queryByText('Owner')).not.toBeInTheDocument();
        });

        it('reveals the dependent household fields once a spouse birth year is entered', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });

            expect(screen.getByText('Primary Death Age')).toBeInTheDocument();
            expect(screen.getByText('Spouse Death Age')).toBeInTheDocument();
            expect(screen.getByText('Survivor Spending Factor (%)')).toBeInTheDocument();
            expect(screen.getByText('Community Property State')).toBeInTheDocument();
            expect(survivorSpendingFactorInput().value).toBe('75');
            expect(communityPropertyCheckbox().checked).toBe(false);
        });

        it('clears (nulls) the dependent household fields, hiding them again, when spouse birth year is cleared', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.change(primaryDeathAgeInput(), { target: { value: '85' } });
            fireEvent.change(spouseDeathAgeInput(), { target: { value: '88' } });
            fireEvent.change(survivorSpendingFactorInput(), { target: { value: '60' } });
            fireEvent.click(communityPropertyCheckbox());

            fireEvent.change(spouseBirthYearInput(), { target: { value: '' } });

            expect(screen.queryByText('Primary Death Age')).not.toBeInTheDocument();

            // Re-adding a spouse shows fresh (not stale) defaults, not the previously entered values.
            fireEvent.change(spouseBirthYearInput(), { target: { value: '1972' } });
            expect(primaryDeathAgeInput().value).toBe('');
            expect(spouseDeathAgeInput().value).toBe('');
            expect(survivorSpendingFactorInput().value).toBe('75');
            expect(communityPropertyCheckbox().checked).toBe(false);
        });

        it('submits every household field as null for a single-person scenario', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.spouse_birth_year).toBeNull();
            expect(call.primary_death_age).toBeNull();
            expect(call.spouse_death_age).toBeNull();
            expect(call.survivor_spending_factor).toBeNull();
            expect(call.community_property).toBeNull();
        });

        it('submits entered household fields, converting the survivor spending factor to a decimal', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.change(primaryDeathAgeInput(), { target: { value: '85' } });
            fireEvent.change(spouseDeathAgeInput(), { target: { value: '88' } });
            fireEvent.change(survivorSpendingFactorInput(), { target: { value: '70' } });
            fireEvent.click(communityPropertyCheckbox());

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.spouse_birth_year).toBe(1970);
            expect(call.primary_death_age).toBe(85);
            expect(call.spouse_death_age).toBe(88);
            expect(call.survivor_spending_factor).toBeCloseTo(0.70);
            expect(call.community_property).toBe(true);
        });

        it('serializes death ages as null (not omitted) when left blank with a spouse set, using a != null guard', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            // Leave primary/spouse death age blank -- they must serialize as null, not 0 or undefined.
            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.primary_death_age).toBeNull();
            expect(call.spouse_death_age).toBeNull();
            expect(call.survivor_spending_factor).toBeCloseTo(0.75);
            expect(call.community_property).toBe(false);
        });

        it('bounds the death-age inputs to 50-120', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });

            expect(primaryDeathAgeInput().min).toBe('50');
            expect(primaryDeathAgeInput().max).toBe('120');
            expect(spouseDeathAgeInput().min).toBe('50');
            expect(spouseDeathAgeInput().max).toBe('120');
        });

        it('hydrates household fields from an existing scenario\'s params_json and round-trips them unchanged', async () => {
            setupMocks();
            const scenario = makeScenario({});
            scenario.params_json = JSON.stringify({
                spouse_birth_year: 1958,
                primary_death_age: 85,
                spouse_death_age: 90,
                survivor_spending_factor: 0.70,
                community_property: true,
            });
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm initialValues={scenario} onSubmit={onSubmit} submitLabel="Save" />);

            expect(spouseBirthYearInput().value).toBe('1958');
            expect(primaryDeathAgeInput().value).toBe('85');
            expect(spouseDeathAgeInput().value).toBe('90');
            expect(survivorSpendingFactorInput().value).toBe('70');
            expect(communityPropertyCheckbox().checked).toBe(true);

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.spouse_birth_year).toBe(1958);
            expect(call.primary_death_age).toBe(85);
            expect(call.spouse_death_age).toBe(90);
            expect(call.survivor_spending_factor).toBeCloseTo(0.70);
            expect(call.community_property).toBe(true);
        });

        it('hydrates a single-person scenario (no spouse_birth_year in params_json) with household fields hidden', () => {
            setupMocks();
            const scenario = makeScenario({});
            scenario.params_json = JSON.stringify({ primary_death_age: 85 });
            render(<ScenarioForm initialValues={scenario} onSubmit={vi.fn()} submitLabel="Save" />);

            expect(spouseBirthYearInput().value).toBe('');
            expect(screen.queryByText('Primary Death Age')).not.toBeInTheDocument();
        });

        it('does not render an account Owner select for a single-person scenario', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            expect(screen.queryByText('Owner')).not.toBeInTheDocument();
        });

        it('round-trips an account owner selection once household is enabled', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            const scenario = makeScenario({ owner: 'spouse' });
            scenario.params_json = JSON.stringify({ spouse_birth_year: 1970 });
            render(<ScenarioForm initialValues={scenario} onSubmit={onSubmit} submitLabel="Save" />);

            expect(ownerSelect().value).toBe('spouse');

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.accounts[0].owner).toBe('spouse');
        });

        it('disables the joint owner option for a non-taxable account and resets a stale joint selection to primary', () => {
            setupMocks();
            const scenario = makeScenario({ owner: 'joint', account_type: 'taxable' });
            scenario.params_json = JSON.stringify({ spouse_birth_year: 1970 });
            render(<ScenarioForm initialValues={scenario} onSubmit={vi.fn()} submitLabel="Save" />);

            expect(ownerSelect().value).toBe('joint');
            const jointOptionBefore = Array.from(ownerSelect().options).find(o => o.value === 'joint');
            expect(jointOptionBefore?.disabled).toBe(false);

            fireEvent.change(accountTypeSelect(), { target: { value: 'traditional' } });

            expect(ownerSelect().value).toBe('primary');
            const jointOptionAfter = Array.from(ownerSelect().options).find(o => o.value === 'joint');
            expect(jointOptionAfter?.disabled).toBe(true);
        });
    });

    describe('stochastic mortality (sub-project B)', () => {
        it('does not render the "Model Uncertain Lifespans" toggle for a single-person scenario', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            expect(screen.queryByText('Model Uncertain Lifespans')).not.toBeInTheDocument();
        });

        it('shows the toggle (off, sex/longevity hidden) once a spouse birth year is entered', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });

            expect(stochasticMortalityToggle().checked).toBe(false);
            expect(screen.queryByText('Primary Sex')).not.toBeInTheDocument();
            expect(screen.queryByText('Spouse Sex')).not.toBeInTheDocument();
            expect(screen.queryByText('Longevity Age')).not.toBeInTheDocument();
        });

        it('reveals the Sex selects and longevity-age input when the toggle is switched on', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(stochasticMortalityToggle());

            expect(screen.getByText('Primary Sex')).toBeInTheDocument();
            expect(screen.getByText('Spouse Sex')).toBeInTheDocument();
            expect(screen.getByText('Longevity Age')).toBeInTheDocument();
            expect(longevityAgeInput().value).toBe('95');
        });

        it('hides the Sex selects and longevity-age input again when the toggle is switched back off', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(stochasticMortalityToggle());
            expect(screen.getByText('Primary Sex')).toBeInTheDocument();

            fireEvent.click(stochasticMortalityToggle());

            expect(screen.queryByText('Primary Sex')).not.toBeInTheDocument();
            expect(screen.queryByText('Spouse Sex')).not.toBeInTheDocument();
            expect(screen.queryByText('Longevity Age')).not.toBeInTheDocument();
        });

        it('clears the toggle and dependent fields, hiding them, when spouse birth year is cleared', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(stochasticMortalityToggle());
            fireEvent.change(spouseBirthYearInput(), { target: { value: '' } });

            expect(screen.queryByText('Model Uncertain Lifespans')).not.toBeInTheDocument();

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1972' } });
            expect(stochasticMortalityToggle().checked).toBe(false);
        });

        it('round-trips a Primary Sex selection into the serialized request', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(stochasticMortalityToggle());
            fireEvent.change(primarySexSelect(), { target: { value: 'male' } });
            fireEvent.change(spouseSexSelect(), { target: { value: 'female' } });

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.stochastic_mortality).toBe(true);
            expect(call.primary_sex).toBe('male');
            expect(call.spouse_sex).toBe('female');
        });

        it('submits a custom longevity conditional age', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(stochasticMortalityToggle());
            fireEvent.change(longevityAgeInput(), { target: { value: '90' } });

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.longevity_conditional_age).toBe(90);
        });

        it('omits stochastic_mortality, primary_sex, spouse_sex, and longevity_conditional_age for a single-person scenario', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.stochastic_mortality).toBeUndefined();
            expect(call.primary_sex).toBeUndefined();
            expect(call.spouse_sex).toBeUndefined();
            expect(call.longevity_conditional_age).toBeUndefined();
        });

        it('omits stochastic_mortality and dependent fields when a household has the toggle left off', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.stochastic_mortality).toBeUndefined();
            expect(call.primary_sex).toBeUndefined();
            expect(call.spouse_sex).toBeUndefined();
            expect(call.longevity_conditional_age).toBeUndefined();
        });

        it('omits primary_sex/spouse_sex when left unset (blended) but still sends the toggle and longevity age', async () => {
            setupMocks();
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm onSubmit={onSubmit} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(stochasticMortalityToggle());

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.stochastic_mortality).toBe(true);
            expect(call.primary_sex).toBeUndefined();
            expect(call.spouse_sex).toBeUndefined();
            expect(call.longevity_conditional_age).toBe(95);
        });

        it('hydrates stochastic-mortality fields from an existing scenario\'s params_json and round-trips them unchanged', async () => {
            setupMocks();
            const scenario = makeScenario({});
            scenario.params_json = JSON.stringify({
                spouse_birth_year: 1958,
                stochastic_mortality: true,
                primary_sex: 'male',
                spouse_sex: 'female',
                longevity_conditional_age: 92,
            });
            const onSubmit = vi.fn().mockResolvedValue(undefined);
            render(<ScenarioForm initialValues={scenario} onSubmit={onSubmit} submitLabel="Save" />);

            expect(stochasticMortalityToggle().checked).toBe(true);
            expect(primarySexSelect().value).toBe('male');
            expect(spouseSexSelect().value).toBe('female');
            expect(longevityAgeInput().value).toBe('92');

            fireEvent.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() => {
                expect(onSubmit).toHaveBeenCalled();
            });
            const call = onSubmit.mock.calls[0][0];
            expect(call.stochastic_mortality).toBe(true);
            expect(call.primary_sex).toBe('male');
            expect(call.spouse_sex).toBe('female');
            expect(call.longevity_conditional_age).toBe(92);
        });

        it('bounds the longevity-age input to 80-110', () => {
            setupMocks();
            render(<ScenarioForm onSubmit={vi.fn()} submitLabel="Save" />);

            fireEvent.change(spouseBirthYearInput(), { target: { value: '1970' } });
            fireEvent.click(stochasticMortalityToggle());

            expect(longevityAgeInput().min).toBe('80');
            expect(longevityAgeInput().max).toBe('110');
        });
    });
});
