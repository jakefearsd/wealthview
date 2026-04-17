import { screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import type { Property } from '../types/property';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: () => ({ role: 'admin' }),
}));

vi.mock('../api/properties', () => ({
    listProperties: vi.fn(),
    createProperty: vi.fn(),
    updateProperty: vi.fn(),
    deleteProperty: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    toPercent: (v: number) => v * 100,
    formatCurrencyInput: (v: string | number) => String(v),
    parseCurrencyInput: (v: string) => v.replace(/,/g, ''),
}));

vi.mock('../components/PropertyForm', () => ({
    default: ({ heading, onCancel }: { heading: string; onCancel: () => void }) => (
        <div data-testid="property-form">
            <span>{heading}</span>
            <button onClick={onCancel}>X</button>
        </div>
    ),
    // Re-export the types we consume in PropertiesListPage.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    PropertyFormValues: undefined as any,
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import PropertiesListPage from './PropertiesListPage';

const mockUseApiQuery = vi.mocked(useApiQuery);

const sampleProperty: Property = {
    id: 'prop-1',
    address: '123 Oak Street',
    purchase_price: 400000,
    purchase_date: '2020-01-01',
    current_value: 500000,
    mortgage_balance: 300000,
    equity: 200000,
    loan_amount: null,
    annual_interest_rate: null,
    loan_term_months: null,
    loan_start_date: null,
    has_loan_details: false,
    use_computed_balance: false,
    property_type: 'primary_residence',
    annual_appreciation_rate: null,
    annual_property_tax: null,
    annual_insurance_cost: null,
    annual_maintenance_cost: null,
    in_service_date: null,
    land_value: null,
    depreciation_method: 'none',
    useful_life_years: 27.5,
    cost_seg_allocations: [],
    bonus_depreciation_rate: 1,
    cost_seg_study_year: null,
};

function mockReturn(overrides: Partial<ReturnType<typeof useApiQuery>> = {}) {
    mockUseApiQuery.mockReturnValue({
        data: [sampleProperty],
        loading: false,
        error: null,
        refetch: vi.fn(),
        ...overrides,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);
}

describe('PropertiesListPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders the list of properties', () => {
        mockReturn();
        renderWithRouter(<PropertiesListPage />);
        expect(screen.getByText('123 Oak Street')).toBeInTheDocument();
    });

    it('shows loading state', () => {
        mockReturn({ loading: true, data: null });
        renderWithRouter(<PropertiesListPage />);
        expect(screen.getByText(/Loading properties/i)).toBeInTheDocument();
    });

    it('opens the create form', () => {
        mockReturn();
        renderWithRouter(<PropertiesListPage />);
        fireEvent.click(screen.getByText('New Property'));
        expect(screen.getByTestId('property-form')).toBeInTheDocument();
        expect(screen.getByText('Create Property')).toBeInTheDocument();
    });
});
