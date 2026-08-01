import { screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import type { IncomeSource } from '../types/projection';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../api/incomeSources', () => ({
    listIncomeSources: vi.fn(),
    createIncomeSource: vi.fn().mockResolvedValue(undefined),
    updateIncomeSource: vi.fn().mockResolvedValue(undefined),
    deleteIncomeSource: vi.fn(),
}));

vi.mock('../api/properties', () => ({
    listProperties: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    toPercent: (v: number) => v * 100,
    formatCurrencyInput: (v: string | number) => String(v),
    parseCurrencyInput: (v: string) => v.replace(/,/g, ''),
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    inputStyle: {},
    labelStyle: {},
}));

vi.mock('../components/PropertyIncomeChart', () => ({
    default: () => <div data-testid="property-income-chart" />,
}));

vi.mock('../components/InfoSection', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('../components/HelpText', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { createIncomeSource, updateIncomeSource } from '../api/incomeSources';
import IncomeSourcesPage from './IncomeSourcesPage';

const mockUseApiQuery = vi.mocked(useApiQuery);
const mockCreateIncomeSource = vi.mocked(createIncomeSource);
const mockUpdateIncomeSource = vi.mocked(updateIncomeSource);

const ssSource: IncomeSource = {
    id: 'inc-1',
    name: 'My Social Security',
    income_type: 'social_security',
    annual_amount: 30000,
    start_age: 67,
    end_age: null,
    inflation_rate: 0.02,
    one_time: false,
    tax_treatment: 'partially_taxable',
    property_id: null,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
} as any;

const pensionSource: IncomeSource = {
    id: 'inc-2',
    name: 'My Pension',
    income_type: 'pension',
    annual_amount: 12000,
    start_age: 62,
    end_age: null,
    inflation_rate: 0,
    one_time: false,
    tax_treatment: 'taxable',
    property_id: null,
    property_address: null,
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
    owner: 'spouse',
    survivor_percent: 0.6,
};

function ownerSelect(): HTMLSelectElement {
    const label = screen.getByText('Owner');
    const select = label.parentElement?.querySelector('select');
    if (!select) {
        throw new Error('Owner select not found');
    }
    return select as HTMLSelectElement;
}

function survivorPercentInput(): HTMLInputElement {
    const label = screen.getByText('Survivor % (%)');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Survivor % input not found');
    }
    return input as HTMLInputElement;
}

function incomeTypeSelect(): HTMLSelectElement {
    const label = screen.getByText('Income Type');
    const select = label.parentElement?.querySelector('select');
    if (!select) {
        throw new Error('Income Type select not found');
    }
    return select as HTMLSelectElement;
}

function annualAmountInput(): HTMLInputElement {
    const label = screen.getByText('Annual Amount');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Annual Amount input not found');
    }
    return input as HTMLInputElement;
}


function startAgeInput(): HTMLInputElement {
    const label = screen.getByText('Start Age');
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Start Age input not found');
    }
    return input as HTMLInputElement;
}

function inflationRateInput(): HTMLInputElement {
    const label = screen.getByText(/Inflation Rate/);
    const input = label.parentElement?.querySelector('input');
    if (!input) {
        throw new Error('Inflation Rate input not found');
    }
    return input as HTMLInputElement;
}

/**
 * Tax treatment is not a <select> — it is a grid of clickable cards where the chosen one is
 * highlighted. Returns the label of whichever card is currently selected.
 */
function selectedTaxTreatment(): string {
    const grid = screen.getByText('Tax Treatment').nextElementSibling;
    const cards = Array.from(grid?.children ?? []) as HTMLElement[];
    const chosen = cards.find(c => c.style.background === 'rgb(227, 242, 253)');
    return chosen?.querySelector('div')?.textContent ?? '';
}

function setupMocks({ sources, properties }: { sources?: IncomeSource[]; properties?: unknown[] } = {}) {
    let call = 0;
    // Two queries per render, always sources then properties. Cycling with modulo (rather than a
    // monotonic counter) keeps the mapping correct across RE-renders — otherwise the first
    // interaction serves the properties array as the income-source list.
    mockUseApiQuery.mockImplementation(() => {
        const idx = call % 2;
        call++;
        if (idx === 0) {
            return { data: sources ?? [], loading: false, error: null, refetch: vi.fn() };
        }
        return { data: properties ?? [], loading: false, error: null, refetch: vi.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;
}

describe('IncomeSourcesPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders with no income sources and shows empty-form toggle', () => {
        setupMocks();
        renderWithRouter(<IncomeSourcesPage />);
        expect(screen.getByText('Income Sources')).toBeInTheDocument();
        expect(screen.getByText('New Income Source')).toBeInTheDocument();
    });

    it('renders an existing income source', () => {
        setupMocks({ sources: [ssSource] });
        renderWithRouter(<IncomeSourcesPage />);
        expect(screen.getByText('My Social Security')).toBeInTheDocument();
    });

    it('shows loading state when sources are loading', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() });
        renderWithRouter(<IncomeSourcesPage />);
        expect(screen.getByText(/Loading income sources/i)).toBeInTheDocument();
    });

    it('opens the create form when New Income Source is clicked', () => {
        setupMocks();
        renderWithRouter(<IncomeSourcesPage />);
        fireEvent.click(screen.getByText('New Income Source'));
        expect(screen.getByPlaceholderText('e.g., Social Security')).toBeInTheDocument();
    });

    describe('household / survivor modeling', () => {
        it('shows the statutory survivor note (not a Survivor % input) for social_security, the default type', () => {
            setupMocks();
            renderWithRouter(<IncomeSourcesPage />);
            fireEvent.click(screen.getByText('New Income Source'));

            expect(screen.getByText(/Statutory survivor rule applies automatically/i)).toBeInTheDocument();
            expect(screen.queryByText('Survivor % (%)')).not.toBeInTheDocument();
            // Owner is not SS-gated -- it's always available.
            expect(ownerSelect()).toBeInTheDocument();
        });

        it('shows a Survivor % input (not the statutory note) once the type is changed off social_security', () => {
            setupMocks();
            renderWithRouter(<IncomeSourcesPage />);
            fireEvent.click(screen.getByText('New Income Source'));

            fireEvent.change(incomeTypeSelect(), { target: { value: 'pension' } });

            expect(screen.getByText('Survivor % (%)')).toBeInTheDocument();
            expect(screen.queryByText(/Statutory survivor rule applies automatically/i)).not.toBeInTheDocument();
            expect(survivorPercentInput().value).toBe('100');
        });

        it('submits owner and a decimal survivor_percent for a non-SS income source', async () => {
            setupMocks();
            renderWithRouter(<IncomeSourcesPage />);
            fireEvent.click(screen.getByText('New Income Source'));

            fireEvent.change(screen.getByPlaceholderText('e.g., Social Security'), { target: { value: 'My Pension' } });
            fireEvent.change(incomeTypeSelect(), { target: { value: 'pension' } });
            fireEvent.change(annualAmountInput(), { target: { value: '12000' } });
            fireEvent.change(ownerSelect(), { target: { value: 'spouse' } });
            fireEvent.change(survivorPercentInput(), { target: { value: '50' } });

            fireEvent.click(screen.getByRole('button', { name: 'Create Income Source' }));

            await waitFor(() => {
                expect(mockCreateIncomeSource).toHaveBeenCalled();
            });
            const request = mockCreateIncomeSource.mock.calls[0][0];
            expect(request.owner).toBe('spouse');
            expect(request.survivor_percent).toBeCloseTo(0.5);
        });

        it('submits owner but nulls survivor_percent for a social_security income source', async () => {
            setupMocks();
            renderWithRouter(<IncomeSourcesPage />);
            fireEvent.click(screen.getByText('New Income Source'));

            fireEvent.change(screen.getByPlaceholderText('e.g., Social Security'), { target: { value: 'My SS' } });
            fireEvent.change(annualAmountInput(), { target: { value: '30000' } });
            fireEvent.change(ownerSelect(), { target: { value: 'spouse' } });

            fireEvent.click(screen.getByRole('button', { name: 'Create Income Source' }));

            await waitFor(() => {
                expect(mockCreateIncomeSource).toHaveBeenCalled();
            });
            const request = mockCreateIncomeSource.mock.calls[0][0];
            expect(request.owner).toBe('spouse');
            expect(request.survivor_percent).toBeNull();
        });

        it('hydrates owner and survivor_percent (as a percent) when editing an existing income source', async () => {
            setupMocks({ sources: [pensionSource] });
            renderWithRouter(<IncomeSourcesPage />);

            fireEvent.click(screen.getByText('Edit'));

            expect(ownerSelect().value).toBe('spouse');
            expect(survivorPercentInput().value).toBe('60');

            fireEvent.click(screen.getByText('Update Income Source'));

            await waitFor(() => {
                expect(mockUpdateIncomeSource).toHaveBeenCalled();
            });
            const [, request] = mockUpdateIncomeSource.mock.calls[0];
            expect(request.owner).toBe('spouse');
            expect(request.survivor_percent).toBeCloseTo(0.6);
        });
    });

    // === changing the income type rewrites dependent fields ===
    //
    // handleTypeChange is the page's one piece of real branching: switching type resets the tax
    // treatment to that type's first legal option and, for two types, seeds age and inflation
    // defaults. Leaving a stale tax treatment behind is not a cosmetic bug — it changes how the
    // projection engine taxes that stream (e.g. a pension left on 'partially_taxable' would be run
    // through the Social Security provisional-income formula).

    describe('income type changes', () => {
        const openForm = () => {
            setupMocks();
            renderWithRouter(<IncomeSourcesPage />);
            fireEvent.click(screen.getByText('New Income Source'));
        };

        it('resets the tax treatment to the new type\'s first legal option', () => {
            openForm();
            expect(selectedTaxTreatment()).toBe('Partially Taxable');   // social_security default

            fireEvent.change(incomeTypeSelect(), { target: { value: 'pension' } });

            expect(selectedTaxTreatment()).toBe('Fully Taxable');
        });

        it('picks the passive option when switching to rental property', () => {
            openForm();

            fireEvent.change(incomeTypeSelect(), { target: { value: 'rental_property' } });

            expect(selectedTaxTreatment()).toBe('Passive');
        });

        it('picks the self-employment treatment for part-time work', () => {
            openForm();

            fireEvent.change(incomeTypeSelect(), { target: { value: 'part_time_work' } });

            expect(selectedTaxTreatment()).toBe('Self-Employment');
        });

        it('seeds age 67 and 2% inflation when switching to social security', () => {
            openForm();
            fireEvent.change(incomeTypeSelect(), { target: { value: 'pension' } });
            fireEvent.change(startAgeInput(), { target: { value: '55' } });

            fireEvent.change(incomeTypeSelect(), { target: { value: 'social_security' } });

            expect(startAgeInput().value).toBe('67');
            expect(inflationRateInput().value).toBe('2');
        });

        it('seeds 2% inflation for rental property but leaves the start age alone', () => {
            openForm();
            fireEvent.change(incomeTypeSelect(), { target: { value: 'pension' } });
            fireEvent.change(startAgeInput(), { target: { value: '55' } });

            fireEvent.change(incomeTypeSelect(), { target: { value: 'rental_property' } });

            expect(inflationRateInput().value).toBe('2');
            expect(startAgeInput().value).toBe('55');
        });

        it('leaves age and inflation untouched for every other type', () => {
            openForm();
            fireEvent.change(incomeTypeSelect(), { target: { value: 'pension' } });
            fireEvent.change(startAgeInput(), { target: { value: '58' } });
            fireEvent.change(inflationRateInput(), { target: { value: '3.5' } });

            fireEvent.change(incomeTypeSelect(), { target: { value: 'annuity' } });

            expect(startAgeInput().value).toBe('58');
            expect(inflationRateInput().value).toBe('3.5');
        });

        it('drops any linked property when the type is no longer rental', () => {
            setupMocks({ properties: [{ id: 'prop-1', address: '123 Main St', current_value: 450000 }] });
            renderWithRouter(<IncomeSourcesPage />);
            fireEvent.click(screen.getByText('New Income Source'));
            fireEvent.change(incomeTypeSelect(), { target: { value: 'rental_property' } });

            // The property picker is only offered for rental income.
            expect(screen.getByText(/Link to Property/i)).toBeInTheDocument();

            fireEvent.change(incomeTypeSelect(), { target: { value: 'pension' } });

            expect(screen.queryByText(/Link to Property/i)).not.toBeInTheDocument();
        });
    });
});
