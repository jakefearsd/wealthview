import { screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import type { IncomeSource } from '../types/projection';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../api/incomeSources', () => ({
    listIncomeSources: vi.fn(),
    createIncomeSource: vi.fn(),
    updateIncomeSource: vi.fn(),
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
import IncomeSourcesPage from './IncomeSourcesPage';

const mockUseApiQuery = vi.mocked(useApiQuery);

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

function setupMocks({ sources, properties }: { sources?: IncomeSource[]; properties?: unknown[] } = {}) {
    let call = 0;
    mockUseApiQuery.mockImplementation(() => {
        call++;
        if (call === 1) {
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
});
