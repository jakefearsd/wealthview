import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ScenarioHouseholdSection from './ScenarioHouseholdSection';
import type { ScenarioFormFields } from './scenarioFormFields';

function makeFields(overrides: Partial<ScenarioFormFields> = {}): ScenarioFormFields {
    return {
        birthYear: 1968,
        spouseBirthYear: 1970,
        spouseBirthMonth: null,
        primaryDeathAge: null,
        spouseDeathAge: null,
        survivorSpendingFactor: 75,
        communityProperty: false,
        stochasticMortality: false,
        primarySex: null,
        spouseSex: null,
        longevityConditionalAge: 95,
        ...overrides,
    } as ScenarioFormFields;
}

describe('ScenarioHouseholdSection', () => {
    it('describes Spouse Birth Month as stored for Medicare timing, not as driving the spouse 59½ date', () => {
        render(<ScenarioHouseholdSection fields={makeFields()} setField={vi.fn()} onSpouseBirthYearChange={vi.fn()} />);

        expect(screen.getByText(/Stored for upcoming Medicare timing/)).toBeInTheDocument();
        expect(screen.getByText(/follows your \(primary\) birth month/)).toBeInTheDocument();
        expect(screen.queryByText(/spouse's 59½/)).not.toBeInTheDocument();
    });

    it('hides Spouse Birth Month when there is no spouse', () => {
        render(<ScenarioHouseholdSection
            fields={makeFields({ spouseBirthYear: null })} setField={vi.fn()} onSpouseBirthYearChange={vi.fn()} />);

        expect(screen.queryByText('Spouse Birth Month')).not.toBeInTheDocument();
    });
});
