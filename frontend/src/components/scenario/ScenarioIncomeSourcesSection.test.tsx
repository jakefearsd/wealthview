import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { Dispatch, SetStateAction } from 'react';
import type { IncomeSource, ScenarioIncomeSourceInput } from '../../types/projection';
import ScenarioIncomeSourcesSection from './ScenarioIncomeSourcesSection';

const pension = {
    id: 'is-1', name: 'Pension', income_type: 'pension', annual_amount: 24000,
} as unknown as IncomeSource;

/** Applies the functional updater the component hands to the setter and returns the new state. */
function renderWith(selected: ScenarioIncomeSourceInput[]) {
    const onChange = vi.fn() as unknown as Dispatch<SetStateAction<ScenarioIncomeSourceInput[]>> & ReturnType<typeof vi.fn>;
    render(
        <ScenarioIncomeSourcesSection
            availableIncomeSources={[pension]}
            selectedIncomeSources={selected}
            onSelectedIncomeSourcesChange={onChange}
        />,
    );
    const next = () => {
        const updater = onChange.mock.calls.at(-1)![0] as (prev: ScenarioIncomeSourceInput[]) => ScenarioIncomeSourceInput[];
        return updater(selected);
    };
    return { next };
}

describe('ScenarioIncomeSourcesSection', () => {
    it('lets a scenario override an income source to $0 instead of snapping back to the default', () => {
        const { next } = renderWith([{ income_source_id: 'is-1', override_annual_amount: null }]);

        fireEvent.change(screen.getByPlaceholderText('Use default'), { target: { value: '0' } });

        expect(next()[0].override_annual_amount).toBe(0);
    });

    it('clears the override back to the default when the field is emptied', () => {
        const { next } = renderWith([{ income_source_id: 'is-1', override_annual_amount: 5000 }]);

        fireEvent.change(screen.getByPlaceholderText('Use default'), { target: { value: '' } });

        expect(next()[0].override_annual_amount).toBeNull();
    });

    it('stores a typed override as a number', () => {
        const { next } = renderWith([{ income_source_id: 'is-1', override_annual_amount: null }]);

        fireEvent.change(screen.getByPlaceholderText('Use default'), { target: { value: '18000' } });

        expect(next()[0].override_annual_amount).toBe(18000);
    });

    it('shows an existing $0 override rather than the placeholder', () => {
        renderWith([{ income_source_id: 'is-1', override_annual_amount: 0 }]);

        expect(screen.getByPlaceholderText('Use default')).toHaveValue('0');
    });
});
