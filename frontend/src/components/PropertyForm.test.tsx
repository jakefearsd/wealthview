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
});
