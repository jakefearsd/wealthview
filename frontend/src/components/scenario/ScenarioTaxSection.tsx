import CurrencyInput from '../CurrencyInput';
import FormField from '../FormField';
import { inputStyle } from '../../utils/styles';
import { effectiveFilingStatus, type ScenarioFormFields, type SetScenarioField } from './scenarioFormFields';

export interface ScenarioTaxSectionProps {
    fields: ScenarioFormFields;
    setField: SetScenarioField;
}

/**
 * Tax configuration: filing status (always shown — it sets the brackets and deduction for every
 * scenario, not only ones with Roth conversions), state selection plus the SALT/itemized inputs it
 * unlocks, and the heirs' tax rate.
 */
export default function ScenarioTaxSection({ fields, setField }: ScenarioTaxSectionProps) {
    const { state, primaryResidencePropertyTax, primaryResidenceMortgageInterest, heirTaxRate } = fields;

    return (
        <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', marginBottom: '1rem' }}>
            <h4 style={{ marginBottom: '0.75rem' }}>Tax Configuration</h4>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem' }}>
                <FormField
                    label="Filing Status"
                    helpText="Sets the federal brackets and standard deduction for every projection year. Defaults to Married Filing Jointly when a spouse birth year is entered; after a first death the survivor files Single."
                >
                    <select
                        style={inputStyle}
                        value={effectiveFilingStatus(fields)}
                        onChange={e => setField('filingStatus', e.target.value)}
                    >
                        <option value="single">Single</option>
                        <option value="married_filing_jointly">Married Filing Jointly</option>
                    </select>
                </FormField>
                <FormField label="State" helpText="State income tax applied to projections. Enables SALT deduction and itemized vs standard deduction comparison.">
                    <select style={inputStyle} value={state} onChange={e => setField('state', e.target.value)}>
                        <option value="">None (federal only)</option>
                        <option value="AZ">AZ - Arizona</option>
                        <option value="CA">CA - California</option>
                        <option value="NV">NV - Nevada (no income tax)</option>
                        <option value="OR">OR - Oregon</option>
                        <option value="WA">WA - Washington (no income tax)</option>
                    </select>
                </FormField>
                {state && (
                    <>
                        <FormField label="Primary Residence Property Tax" helpText="Annual property tax on your primary residence. Feeds SALT deduction (capped at $10K with state income tax).">
                            <CurrencyInput
                                style={inputStyle}
                                value={primaryResidencePropertyTax || ''}
                                onChange={v => setField('primaryResidencePropertyTax', Number(v) || 0)}
                            />
                        </FormField>
                        <FormField label="Primary Residence Mortgage Interest" helpText="Annual mortgage interest on your primary residence. Added to SALT for itemized deduction comparison.">
                            <CurrencyInput
                                style={inputStyle}
                                value={primaryResidenceMortgageInterest || ''}
                                onChange={v => setField('primaryResidenceMortgageInterest', Number(v) || 0)}
                            />
                        </FormField>
                    </>
                )}
                <FormField
                    label="Heir Tax Rate (%)"
                    helpText="Tax rate your heirs would pay on inherited traditional (pre-tax) balances. Used for the After-tax Legacy figure (default 24%)."
                >
                    <input
                        style={inputStyle}
                        type="number"
                        step="1"
                        min="0"
                        max="50"
                        value={heirTaxRate ?? ''}
                        onChange={e => setField('heirTaxRate', e.target.value === '' ? null : Number(e.target.value))}
                    />
                </FormField>
            </div>
        </div>
    );
}
