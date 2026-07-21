import type { Dispatch, SetStateAction } from 'react';
import CurrencyInput from '../CurrencyInput';
import { formatCurrency } from '../../utils/format';
import { inputStyle } from '../../utils/styles';
import type { IncomeSource, ScenarioIncomeSourceInput } from '../../types/projection';

export interface ScenarioIncomeSourcesSectionProps {
    availableIncomeSources: IncomeSource[] | null;
    selectedIncomeSources: ScenarioIncomeSourceInput[];
    onSelectedIncomeSourcesChange: Dispatch<SetStateAction<ScenarioIncomeSourceInput[]>>;
}

/** Income-source picker: opt Social Security, pensions, rental income, etc. into the projection with optional per-scenario overrides. */
export default function ScenarioIncomeSourcesSection({
    availableIncomeSources,
    selectedIncomeSources,
    onSelectedIncomeSourcesChange,
}: ScenarioIncomeSourcesSectionProps) {
    if (!availableIncomeSources || availableIncomeSources.length === 0) {
        return null;
    }

    return (
        <div style={{ marginBottom: '1rem' }}>
            <h4 style={{ marginBottom: '0.5rem' }}>Income Sources</h4>
            <div style={{ fontSize: '0.85rem', color: '#666', marginBottom: '0.75rem' }}>
                Select income sources (Social Security, pensions, rental income, etc.) to include in this projection. You can optionally override the annual amount per scenario.
            </div>
            {availableIncomeSources.map(is => {
                const selected = selectedIncomeSources.find(s => s.income_source_id === is.id);
                return (
                    <div key={is.id} style={{ border: '1px solid #e0e0e0', borderRadius: '8px', padding: '0.75rem', marginBottom: '0.5rem' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                            <input
                                type="checkbox"
                                checked={!!selected}
                                onChange={e => {
                                    if (e.target.checked) {
                                        onSelectedIncomeSourcesChange(prev => [...prev, { income_source_id: is.id, override_annual_amount: null }]);
                                    } else {
                                        onSelectedIncomeSourcesChange(prev => prev.filter(s => s.income_source_id !== is.id));
                                    }
                                }}
                            />
                            <div style={{ flex: 1 }}>
                                <strong>{is.name}</strong>
                                <span style={{ color: '#666', marginLeft: '0.5rem', fontSize: '0.85rem' }}>
                                    ({is.income_type.replace(/_/g, ' ')}) — {formatCurrency(is.annual_amount)}/yr
                                </span>
                            </div>
                            {selected && (
                                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                    <label style={{ fontSize: '0.85rem', color: '#666' }}>Override:</label>
                                    <CurrencyInput
                                        style={{ ...inputStyle, width: '140px' }}
                                        placeholder="Use default"
                                        value={selected.override_annual_amount != null ? selected.override_annual_amount : ''}
                                        onChange={v => {
                                            const val = v ? Number(v) || null : null;
                                            onSelectedIncomeSourcesChange(prev => prev.map(s =>
                                                s.income_source_id === is.id ? { ...s, override_annual_amount: val } : s
                                            ));
                                        }}
                                    />
                                </div>
                            )}
                        </div>
                    </div>
                );
            })}
        </div>
    );
}
