import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('./CurrencyInput', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({ value, onChange, style }: any) => (
        <input data-testid="currency-input" value={value ?? ''} style={style} onChange={(e) => onChange(e.target.value)} />
    ),
}));

vi.mock('../utils/styles', () => ({
    inputStyle: {},
    labelStyle: {},
}));

import PropertyForm, { type PropertyFormValues } from './PropertyForm';

const values: PropertyFormValues = {
    address: '123 Oak',
    purchasePrice: '400000',
    purchaseDate: '2020-01-01',
    currentValue: '500000',
    mortgageBalance: '300000',
    propertyType: 'primary_residence',
    showLoanDetails: false,
    loanAmount: '',
    annualInterestRate: '',
    loanTermMonths: '',
    loanStartDate: '',
    useComputedBalance: false,
    showFinancialAssumptions: false,
    annualAppreciationRate: '',
    annualPropertyTax: '',
    annualInsuranceCost: '',
    annualMaintenanceCost: '',
    showDepreciation: false,
    depreciationMethod: 'none',
    inServiceDate: '',
    landValue: '',
    usefulLifeYears: '27.5',
    costSegAllocations: { fiveYr: '', sevenYr: '', fifteenYr: '', twentySevenYr: '' },
    bonusDepreciationRate: '100',
    costSegStudyYear: '',
};

describe('PropertyForm', () => {
    it('renders address and financial fields by default', () => {
        render(
            <PropertyForm
                heading="New Property"
                submitLabel="Create"
                values={values}
                onChange={vi.fn()}
                purchasePriceNum={400000}
                onSubmit={vi.fn()}
                onCancel={vi.fn()}
            />
        );
        expect(screen.getByText('New Property')).toBeInTheDocument();
        expect(screen.getByDisplayValue('123 Oak')).toBeInTheDocument();
    });

    it('emits a single-field patch when address changes', () => {
        const onChange = vi.fn();
        render(
            <PropertyForm
                heading="Edit" submitLabel="Save" values={values}
                onChange={onChange} purchasePriceNum={400000}
                onSubmit={vi.fn()} onCancel={vi.fn()}
            />
        );
        fireEvent.change(screen.getByDisplayValue('123 Oak'), { target: { value: '456 Elm' } });
        expect(onChange).toHaveBeenCalledWith({ address: '456 Elm' });
    });

    it('toggles loan details when Show Loan Details is clicked', () => {
        const onChange = vi.fn();
        render(
            <PropertyForm
                heading="Edit" submitLabel="Save" values={values}
                onChange={onChange} purchasePriceNum={400000}
                onSubmit={vi.fn()} onCancel={vi.fn()}
            />
        );
        fireEvent.click(screen.getByText('Show Loan Details'));
        expect(onChange).toHaveBeenCalledWith({ showLoanDetails: true });
    });

    it('shows depreciation warning when land >= purchase price', () => {
        const bad = { ...values, landValue: '500000', showDepreciation: true, depreciationMethod: 'straight_line' };
        render(
            <PropertyForm
                heading="Edit" submitLabel="Save" values={bad}
                onChange={vi.fn()} purchasePriceNum={400000}
                onSubmit={vi.fn()} onCancel={vi.fn()}
            />
        );
        expect(screen.getByText(/Land value must be less than purchase price/)).toBeInTheDocument();
    });

    it('invokes onSubmit and onCancel on the respective buttons', () => {
        const onSubmit = vi.fn();
        const onCancel = vi.fn();
        render(
            <PropertyForm
                heading="Edit" submitLabel="Save" values={values}
                onChange={vi.fn()} purchasePriceNum={400000}
                onSubmit={onSubmit} onCancel={onCancel}
            />
        );
        fireEvent.click(screen.getByText('Save'));
        expect(onSubmit).toHaveBeenCalled();
        fireEvent.click(screen.getByText('Cancel'));
        expect(onCancel).toHaveBeenCalled();
    });

    // === helpers ===

    const renderForm = (overrides: Partial<PropertyFormValues> = {}, onChange = vi.fn(),
                        purchasePriceNum = 400000) => {
        render(
            <PropertyForm
                heading="Edit Property"
                submitLabel="Save"
                values={{ ...values, ...overrides }}
                onChange={onChange}
                purchasePriceNum={purchasePriceNum}
                onSubmit={vi.fn()}
                onCancel={vi.fn()}
            />,
        );
        return onChange;
    };

    /** The n-th CurrencyInput inside the depreciation section, in rendered order. */
    const currencyInputs = () => screen.getAllByTestId('currency-input');

    const depreciating = (method: string, extra: Partial<PropertyFormValues> = {}) => ({
        showDepreciation: true,
        depreciationMethod: method,
        ...extra,
    });

    // === depreciation method selection ===

    it('defaults the in-service date to the purchase date when depreciation is switched on', () => {
        const onChange = renderForm(depreciating('none'));

        fireEvent.change(screen.getByDisplayValue('None'), { target: { value: 'straight_line' } });

        expect(onChange).toHaveBeenCalledWith({
            depreciationMethod: 'straight_line',
            inServiceDate: '2020-01-01',
        });
    });

    it('does not overwrite an in-service date the user already set', () => {
        const onChange = renderForm(depreciating('none', { inServiceDate: '2021-07-01' }));

        fireEvent.change(screen.getByDisplayValue('None'), { target: { value: 'straight_line' } });

        expect(onChange).toHaveBeenCalledWith({ depreciationMethod: 'straight_line' });
    });

    it('sends no in-service date when depreciation is switched off', () => {
        const onChange = renderForm(depreciating('straight_line'));

        fireEvent.change(screen.getByDisplayValue('Straight-Line'), { target: { value: 'none' } });

        expect(onChange).toHaveBeenCalledWith({ depreciationMethod: 'none' });
    });

    it('flags that the in-service date came from the purchase date', () => {
        renderForm(depreciating('straight_line', { inServiceDate: '2020-01-01' }));

        expect(screen.getByText('Defaulted to purchase date')).toBeInTheDocument();
    });

    // === straight-line figures ===

    it('shows the depreciable basis and annual depreciation for straight-line', () => {
        renderForm(depreciating('straight_line', { landValue: '80000', usefulLifeYears: '27.5' }));

        // 400,000 - 80,000 = 320,000 basis; 320,000 / 27.5 = 11,636.36 a year.
        expect(screen.getByText(/Depreciable Basis:/).parentElement).toHaveTextContent('320,000.00');
        expect(screen.getByText(/Annual Depreciation:/).parentElement).toHaveTextContent('11,636.36');
    });

    it('rejects a non-positive useful life', () => {
        renderForm(depreciating('straight_line', { usefulLifeYears: '0' }));

        expect(screen.getByText('Useful life must be greater than 0')).toBeInTheDocument();
    });

    it('accepts a blank useful life without complaining', () => {
        renderForm(depreciating('straight_line', { usefulLifeYears: '' }));

        expect(screen.queryByText('Useful life must be greater than 0')).not.toBeInTheDocument();
    });

    // === cost segregation: auto-fill of the structural remainder ===

    it('auto-fills the 27.5-year bucket with whatever the short-lived classes leave over', () => {
        const onChange = renderForm(depreciating('cost_segregation', { landValue: '80000' }));

        // 5-year is the first CurrencyInput inside the cost-seg grid.
        const fiveYear = currencyInputs().find((el) =>
            el.closest('div')?.textContent?.includes('Appliances'))!;
        fireEvent.change(fiveYear, { target: { value: '50000' } });

        // basis 320,000 - 50,000 short-lived = 270,000 structural.
        expect(onChange).toHaveBeenCalledWith({
            costSegAllocations: expect.objectContaining({ fiveYr: '50000', twentySevenYr: '270000' }),
        });
    });

    it('clamps the auto-filled remainder at zero when the short-lived classes exceed the basis', () => {
        const onChange = renderForm(depreciating('cost_segregation', {
            landValue: '80000',
            costSegAllocations: { fiveYr: '', sevenYr: '200000', fifteenYr: '200000', twentySevenYr: '' },
        }));

        const fiveYear = currencyInputs().find((el) =>
            el.closest('div')?.textContent?.includes('Appliances'))!;
        fireEvent.change(fiveYear, { target: { value: '100000' } });

        expect(onChange).toHaveBeenCalledWith({
            costSegAllocations: expect.objectContaining({ twentySevenYr: '' }),
        });
    });

    it('lets the structural bucket be overridden without re-deriving it', () => {
        const onChange = renderForm(depreciating('cost_segregation', { landValue: '80000' }));

        const structural = currencyInputs().find((el) =>
            el.closest('div')?.textContent?.includes('Auto-computed as remainder'))!;
        fireEvent.change(structural, { target: { value: '999' } });

        expect(onChange).toHaveBeenCalledWith({
            costSegAllocations: expect.objectContaining({ twentySevenYr: '999' }),
        });
    });

    // === cost segregation: reconciliation and bonus ===

    it('warns when the allocations do not add up to the depreciable basis', () => {
        renderForm(depreciating('cost_segregation', {
            landValue: '80000',
            costSegAllocations: { fiveYr: '50000', sevenYr: '', fifteenYr: '', twentySevenYr: '100000' },
        }));

        expect(screen.getByText(/does not equal depreciable basis/)).toBeInTheDocument();
    });

    it('stops warning once the allocations reconcile with the basis', () => {
        renderForm(depreciating('cost_segregation', {
            landValue: '80000',
            costSegAllocations: { fiveYr: '50000', sevenYr: '', fifteenYr: '', twentySevenYr: '270000' },
        }));

        expect(screen.queryByText(/does not equal depreciable basis/)).not.toBeInTheDocument();
    });

    it('computes the year-1 bonus from the short-lived classes only', () => {
        renderForm(depreciating('cost_segregation', {
            landValue: '80000',
            bonusDepreciationRate: '60',
            costSegAllocations: { fiveYr: '50000', sevenYr: '20000', fifteenYr: '30000', twentySevenYr: '220000' },
        }));

        // (50,000 + 20,000 + 30,000) x 60% = 60,000 — the 27.5-year structural is NOT bonus-eligible.
        expect(screen.getByText(/Year-1 Bonus Deduction:/).parentElement)
            .toHaveTextContent('60,000.00');
    });

    it('derives annual structural depreciation from the 27.5-year bucket', () => {
        renderForm(depreciating('cost_segregation', {
            landValue: '80000',
            costSegAllocations: { fiveYr: '50000', sevenYr: '', fifteenYr: '', twentySevenYr: '270000' },
        }));

        // 270,000 / 27.5 = 9,818.18
        expect(screen.getByText(/Annual Structural Depreciation:/).parentElement)
            .toHaveTextContent('9,818.18');
    });

    it('replaces the cost-seg figures with the land-value error when land exceeds price', () => {
        renderForm(depreciating('cost_segregation', { landValue: '500000' }));

        expect(screen.getByText('Land value must be less than purchase price for depreciation.'))
            .toBeInTheDocument();
        expect(screen.queryByText(/Year-1 Bonus Deduction:/)).not.toBeInTheDocument();
    });

    // === section toggles ===

    it('toggles the financial assumptions section', () => {
        const onChange = renderForm();

        fireEvent.click(screen.getByText(/Financial Assumptions/));

        expect(onChange).toHaveBeenCalledWith({ showFinancialAssumptions: true });
    });

    it('toggles the depreciation section', () => {
        const onChange = renderForm();

        fireEvent.click(screen.getByText(/Depreciation/));

        expect(onChange).toHaveBeenCalledWith({ showDepreciation: true });
    });

    it('emits the appreciation rate as typed, leaving conversion to the request builder', () => {
        const onChange = renderForm({ showFinancialAssumptions: true });

        fireEvent.change(screen.getByPlaceholderText('e.g. 3.0'), { target: { value: '3.5' } });

        expect(onChange).toHaveBeenCalledWith({ annualAppreciationRate: '3.5' });
    });
});
