import FormField from '../FormField';
import { inputStyle } from '../../utils/styles';
import type { GuardrailProfileSummary, SpendingProfile } from '../../types/projection';
import type { ScenarioFormFields, SetScenarioField } from './scenarioFormFields';

export interface ScenarioBasicsSectionProps {
    fields: ScenarioFormFields;
    setField: SetScenarioField;
    profiles: SpendingProfile[] | null;
    guardrailProfile: GuardrailProfileSummary | null;
}

/** Core scenario inputs: name, dates, rates, market drags, and the unified Spending Plan dropdown. */
export default function ScenarioBasicsSection({ fields, setField, profiles, guardrailProfile }: ScenarioBasicsSectionProps) {
    const {
        name, retirementDate, birthYear, endAge, inflationRate, withdrawalRate,
        dividendYield, interestYield, feeRate, includeDepressionYears, spendingPlanSelection,
    } = fields;

    return (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
            <FormField label="Name">
                <input style={inputStyle} value={name} onChange={e => setField('name', e.target.value)} placeholder="Retirement Plan" />
            </FormField>
            <FormField label="Retirement Date">
                <input style={inputStyle} type="date" value={retirementDate} onChange={e => setField('retirementDate', e.target.value)} />
            </FormField>
            <FormField label="Birth Year" helpText="Used to calculate your age at each projection year.">
                <input style={inputStyle} type="number" value={birthYear} onChange={e => setField('birthYear', Number(e.target.value))} />
            </FormField>
            <FormField label="End Age" helpText="Age at which the projection ends. Plan beyond your expected lifespan for safety.">
                <input style={inputStyle} type="number" value={endAge} onChange={e => setField('endAge', Number(e.target.value))} />
            </FormField>
            <FormField label="Inflation Rate (%)" helpText="Annual rate of price increases. 3 = 3%, the historical U.S. average.">
                <input style={inputStyle} type="number" step="0.1" value={inflationRate || ''} onChange={e => setField('inflationRate', Number(e.target.value))} />
            </FormField>
            <FormField label="Withdrawal Rate (%)" helpText="Percentage of portfolio to withdraw annually in retirement. 4 = 4%.">
                <input style={inputStyle} type="number" step="0.1" value={withdrawalRate || ''} onChange={e => setField('withdrawalRate', Number(e.target.value))} />
            </FormField>
            <FormField label="Dividend Yield (%)" helpText="Annual qualified-dividend drag on taxable accounts (default 1.8%)">
                <input
                    style={inputStyle}
                    type="number"
                    step="0.1"
                    min="0"
                    max="10"
                    value={dividendYield ?? ''}
                    onChange={e => setField('dividendYield', e.target.value === '' ? null : Number(e.target.value))}
                />
            </FormField>
            <FormField
                label="Bond Interest Yield (%)"
                helpText="Nominal coupon assumption for the bond portion of taxable accounts — taxed annually as ordinary income (bonds are tax-inefficient). Only affects accounts with a bond allocation."
            >
                <input
                    style={inputStyle}
                    type="number"
                    step="0.1"
                    min="0"
                    max="10"
                    value={interestYield ?? ''}
                    onChange={e => setField('interestYield', e.target.value === '' ? null : Number(e.target.value))}
                />
            </FormField>
            <FormField
                label="Investment Fees (%)"
                helpText="Annual all-in cost (expense ratios + advisory) subtracted from returns (default 0.25%)"
            >
                <input
                    style={inputStyle}
                    type="number"
                    step="0.05"
                    min="0"
                    max="3"
                    value={feeRate ?? ''}
                    onChange={e => setField('feeRate', e.target.value === '' ? null : Number(e.target.value))}
                />
            </FormField>
            <FormField
                label="Include 1928–1971 market history"
                helpText="Widens the simulated return sample to include the Great Depression and postwar era. Slightly higher average equity returns, materially fatter tails."
            >
                <input
                    type="checkbox"
                    checked={includeDepressionYears}
                    onChange={e => setField('includeDepressionYears', e.target.checked)}
                />
            </FormField>
            <FormField
                label="Spending Plan"
                helpText="Choose a spending profile (user-defined tiers) or guardrail profile (Monte Carlo optimized). When linked, the projection withdraws what you need each year, minus non-portfolio income."
            >
                <select style={inputStyle} value={spendingPlanSelection} onChange={e => setField('spendingPlanSelection', e.target.value)}>
                    <option value="">None (use withdrawal rate)</option>
                    {profiles?.map(p => (
                        <option key={p.id} value={p.id}>{p.name}</option>
                    ))}
                    {guardrailProfile && (
                        <option value="guardrail">
                            &#9881; {guardrailProfile.name}{guardrailProfile.stale ? ' (stale)' : ''}{!guardrailProfile.active ? ' (inactive)' : ''}
                        </option>
                    )}
                </select>
                {spendingPlanSelection === 'guardrail' && !guardrailProfile && (
                    <div style={{ fontSize: '0.8rem', color: '#e65100', marginTop: '0.25rem' }}>
                        Guardrail profile no longer available. Please select another spending plan.
                    </div>
                )}
            </FormField>
        </div>
    );
}
