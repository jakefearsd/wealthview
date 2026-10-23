import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ScenarioBasicsSection from './ScenarioBasicsSection';
import type { ScenarioFormFields } from './scenarioFormFields';

function makeFields(overrides: Partial<ScenarioFormFields> = {}): ScenarioFormFields {
    return {
        name: 'Plan',
        retirementDate: '2040-01-01',
        birthYear: 1980,
        birthMonth: null,
        endAge: 90,
        inflationRate: 3,
        withdrawalRate: 4,
        dividendYield: 1.8,
        interestYield: 4,
        feeRate: 0.25,
        includeDepressionYears: false,
        spendingPlanSelection: '',
        ...overrides,
    } as ScenarioFormFields;
}

function renderBasics(fields: ScenarioFormFields, bondTentOn: boolean) {
    render(<ScenarioBasicsSection
        fields={fields} setField={vi.fn()} profiles={[]} guardrailProfile={null} bondTentOn={bondTentOn} />);
    return screen.getByLabelText('Include 1928–1971 market history');
}

describe('ScenarioBasicsSection', () => {
    it('leaves depression years editable when there is no tent', () => {
        const box = renderBasics(makeFields({ includeDepressionYears: true }), false);

        expect(box).toBeChecked();
        expect(box).toBeEnabled();
        expect(screen.queryByText(/Not available with a bond tent/)).not.toBeInTheDocument();
    });

    it('disables depression years while a tent is on', () => {
        const box = renderBasics(makeFields({ includeDepressionYears: true }), true);

        expect(box).not.toBeChecked();
        expect(box).toBeDisabled();
        expect(screen.getByText('Not available with a bond tent: bond maturity data starts in 1954.'))
            .toBeInTheDocument();
    });
});
