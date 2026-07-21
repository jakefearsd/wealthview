import FormField from '../FormField';
import { inputStyle } from '../../utils/styles';
import type { Sex } from '../../types/projection';
import {
    MIN_LONGEVITY_CONDITIONAL_AGE,
    MAX_LONGEVITY_CONDITIONAL_AGE,
    type ScenarioFormFields,
    type SetScenarioField,
} from './scenarioFormFields';

const MIN_DEATH_AGE = 50;
const MAX_DEATH_AGE = 120;

/**
 * Client-side, DISPLAY-ONLY mirror of the backend's SSA 2021 period-life-table planning defaults
 * (com.wealthview.core.projection.household.LifeExpectancy#cohortDeathAge). Used only to render a
 * placeholder hint on the death-age inputs before first save — the server does not echo resolved
 * defaults in params_json until a death age is actually set. Keep in sync manually if the backend
 * cohort table changes.
 */
function ssaDefaultDeathAge(birthYear: number): number {
    if (birthYear <= 1940) return 84;
    if (birthYear <= 1950) return 85;
    if (birthYear <= 1960) return 86;
    if (birthYear <= 1970) return 87;
    if (birthYear <= 1980) return 88;
    if (birthYear <= 1990) return 89;
    return 90;
}

export interface ScenarioHouseholdSectionProps {
    fields: ScenarioFormFields;
    setField: SetScenarioField;
    /** Owns the multi-field reset that clearing the spouse birth year triggers — lives in ScenarioForm. */
    onSpouseBirthYearChange: (raw: string) => void;
}

/** Spouse / household modeling: death ages, survivor spending, community property, and stochastic mortality. */
export default function ScenarioHouseholdSection({ fields, setField, onSpouseBirthYearChange }: ScenarioHouseholdSectionProps) {
    const {
        birthYear, spouseBirthYear, primaryDeathAge, spouseDeathAge, survivorSpendingFactor,
        communityProperty, stochasticMortality, primarySex, spouseSex, longevityConditionalAge,
    } = fields;

    const household = spouseBirthYear != null;

    return (
        <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', marginBottom: '1rem' }}>
            <h4 style={{ marginBottom: '0.75rem' }}>Spouse / Household</h4>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem' }}>
                <FormField label="Spouse Birth Year" helpText="Leave blank for a single-person household. Set to model a spouse, survivor transitions, and joint accounts.">
                    <input
                        style={inputStyle}
                        type="number"
                        value={spouseBirthYear ?? ''}
                        onChange={e => onSpouseBirthYearChange(e.target.value)}
                    />
                </FormField>
                {household && (
                    <>
                        <FormField
                            label="Primary Death Age"
                            helpText="Assumed planning age at which the primary passes away (50-120). Blank uses the SSA planning default."
                        >
                            <input
                                style={inputStyle}
                                type="number"
                                min={MIN_DEATH_AGE}
                                max={MAX_DEATH_AGE}
                                placeholder={`SSA default (~${ssaDefaultDeathAge(birthYear)})`}
                                value={primaryDeathAge ?? ''}
                                onChange={e => setField('primaryDeathAge', e.target.value === '' ? null : Number(e.target.value))}
                            />
                        </FormField>
                        <FormField
                            label="Spouse Death Age"
                            helpText="Assumed planning age at which the spouse passes away (50-120). Blank uses the SSA planning default."
                        >
                            <input
                                style={inputStyle}
                                type="number"
                                min={MIN_DEATH_AGE}
                                max={MAX_DEATH_AGE}
                                placeholder={`SSA default (~${ssaDefaultDeathAge(spouseBirthYear)})`}
                                value={spouseDeathAge ?? ''}
                                onChange={e => setField('spouseDeathAge', e.target.value === '' ? null : Number(e.target.value))}
                            />
                        </FormField>
                        <FormField
                            label="Survivor Spending Factor (%)"
                            helpText="Share of pre-transition spending the survivor keeps from the first death forward (50-100%, default 75%)."
                        >
                            <input
                                style={inputStyle}
                                type="number"
                                step="1"
                                min="50"
                                max="100"
                                value={survivorSpendingFactor}
                                onChange={e => setField('survivorSpendingFactor', Number(e.target.value))}
                            />
                        </FormField>
                        <FormField
                            label="Community Property State"
                            helpText="Steps up 100% of embedded gain on joint taxable accounts at first death, instead of the common-law 50%."
                        >
                            <input
                                type="checkbox"
                                checked={communityProperty}
                                onChange={e => setField('communityProperty', e.target.checked)}
                            />
                        </FormField>
                        <FormField
                            label="Model Uncertain Lifespans"
                            helpText="Samples each spouse's death year per Monte Carlo trial from an SSA mortality table, instead of the fixed death ages above, for a longevity-aware guardrail success rate. Only affects the guardrail optimizer's Monte Carlo results, not the deterministic projection or its recommendation."
                        >
                            <input
                                type="checkbox"
                                checked={stochasticMortality}
                                onChange={e => setField('stochasticMortality', e.target.checked)}
                            />
                        </FormField>
                        {stochasticMortality && (
                            <>
                                <FormField
                                    label="Primary Sex"
                                    helpText="Selects the sex-specific column of the mortality table. Leave unset to use a blended (both-sex) table."
                                >
                                    <select
                                        style={inputStyle}
                                        value={primarySex ?? ''}
                                        onChange={e => setField('primarySex', e.target.value === '' ? null : e.target.value as Sex)}
                                    >
                                        <option value="">Blended (unset)</option>
                                        <option value="male">Male</option>
                                        <option value="female">Female</option>
                                    </select>
                                </FormField>
                                <FormField
                                    label="Spouse Sex"
                                    helpText="Selects the sex-specific column of the mortality table. Leave unset to use a blended (both-sex) table."
                                >
                                    <select
                                        style={inputStyle}
                                        value={spouseSex ?? ''}
                                        onChange={e => setField('spouseSex', e.target.value === '' ? null : e.target.value as Sex)}
                                    >
                                        <option value="">Blended (unset)</option>
                                        <option value="male">Male</option>
                                        <option value="female">Female</option>
                                    </select>
                                </FormField>
                                <FormField
                                    label="Longevity Age"
                                    helpText="Age threshold (80-110) for the 'the survivor lives to this age' success metric shown beside lifetime success (default 95)."
                                >
                                    <input
                                        style={inputStyle}
                                        type="number"
                                        min={MIN_LONGEVITY_CONDITIONAL_AGE}
                                        max={MAX_LONGEVITY_CONDITIONAL_AGE}
                                        value={longevityConditionalAge}
                                        onChange={e => setField('longevityConditionalAge', Number(e.target.value))}
                                    />
                                </FormField>
                            </>
                        )}
                    </>
                )}
            </div>
        </div>
    );
}
