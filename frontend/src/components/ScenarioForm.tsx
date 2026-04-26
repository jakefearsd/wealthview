import { useCallback, useState } from 'react';
import { useApiQuery } from '../hooks/useApiQuery';
import { listAccounts } from '../api/accounts';
import { listSpendingProfiles } from '../api/spendingProfiles';
import { listIncomeSources } from '../api/incomeSources';
import { formatCurrency, toPercent } from '../utils/format';
import CurrencyInput from './CurrencyInput';
import FormField from './FormField';
import WithdrawalStrategySection from './WithdrawalStrategySection';
import RothConversionSection from './RothConversionSection';
import type { Account } from '../types/account';
import type { Scenario, CreateScenarioRequest, ScenarioAccountInput, ScenarioIncomeSourceInput } from '../types/projection';
import { inputStyle } from '../utils/styles';
import Button from './Button';

const ACCOUNT_TYPE_HELP: Record<string, string> = {
    taxable: 'Regular brokerage account. After-tax contributions, growth taxed as capital gains.',
    traditional: 'Pre-tax contributions reduce taxable income now. Withdrawals in retirement taxed as ordinary income.',
    roth: 'After-tax contributions. Growth and qualified withdrawals in retirement are completely tax-free.',
};

interface ScenarioFormProps {
    initialValues?: Scenario | null;
    onSubmit: (data: CreateScenarioRequest) => Promise<void>;
    submitLabel: string;
}

interface ScenarioFormFields {
    name: string;
    retirementDate: string;
    endAge: number;
    inflationRate: number;
    birthYear: number;
    withdrawalRate: number;
    withdrawalStrategy: string;
    dynamicCeiling: number;
    dynamicFloor: number;
    filingStatus: string;
    otherIncome: number;
    annualRothConversion: number;
    rothConversionStrategy: string;
    targetBracketRate: number;
    rothConversionStartYear: number | null;
    withdrawalOrder: string;
    dynamicSequencingBracketRate: number;
    state: string;
    primaryResidencePropertyTax: number;
    primaryResidenceMortgageInterest: number;
    spendingPlanSelection: string;
}

function defaultAccount(): ScenarioAccountInput {
    return {
        linked_account_id: null,
        initial_balance: 100000,
        annual_contribution: 10000,
        expected_return: 7,
        account_type: 'taxable',
    };
}

function mapAccountType(realType: string): string {
    switch (realType) {
        case 'roth': return 'roth';
        case '401k': case 'traditional_ira': return 'traditional';
        default: return 'taxable';
    }
}

function buildInitialFields(initialValues: Scenario | null | undefined): ScenarioFormFields {
    const parsedParams = initialValues?.params_json ? JSON.parse(initialValues.params_json) : {};
    const spendingPlanSelection = initialValues?.guardrail_profile?.active
        ? 'guardrail'
        : (initialValues?.spending_profile?.id ?? '');

    return {
        name: initialValues?.name ?? '',
        retirementDate: initialValues?.retirement_date ?? '',
        endAge: initialValues?.end_age ?? 90,
        inflationRate: toPercent(initialValues?.inflation_rate ?? 0.03),
        birthYear: parsedParams.birth_year ?? 1990,
        withdrawalRate: toPercent(parsedParams.withdrawal_rate ?? 0.04),
        withdrawalStrategy: parsedParams.withdrawal_strategy ?? 'fixed_percentage',
        dynamicCeiling: toPercent(parsedParams.dynamic_ceiling ?? 0.05),
        dynamicFloor: toPercent(parsedParams.dynamic_floor ?? -0.025),
        filingStatus: parsedParams.filing_status ?? 'single',
        otherIncome: parsedParams.other_income ?? 0,
        annualRothConversion: parsedParams.annual_roth_conversion ?? 0,
        rothConversionStrategy: parsedParams.roth_conversion_strategy ?? 'fixed_amount',
        targetBracketRate: toPercent(parsedParams.target_bracket_rate ?? 0.12),
        rothConversionStartYear: parsedParams.roth_conversion_start_year ?? null,
        withdrawalOrder: parsedParams.withdrawal_order ?? 'taxable_first',
        dynamicSequencingBracketRate: parsedParams.dynamic_sequencing_bracket_rate ?? 0.12,
        state: parsedParams.state ?? '',
        primaryResidencePropertyTax: parsedParams.primary_residence_property_tax ?? 0,
        primaryResidenceMortgageInterest: parsedParams.primary_residence_mortgage_interest ?? 0,
        spendingPlanSelection,
    };
}

export default function ScenarioForm({ initialValues, onSubmit, submitLabel }: ScenarioFormProps) {
    const { data: profiles } = useApiQuery(listSpendingProfiles);
    const { data: accountsPage } = useApiQuery(() => listAccounts(0, 100));
    const { data: availableIncomeSources } = useApiQuery(listIncomeSources);
    const existingAccounts: Account[] = accountsPage?.data ?? [];

    const [fields, setFields] = useState<ScenarioFormFields>(() => buildInitialFields(initialValues));
    const setField = useCallback(<K extends keyof ScenarioFormFields>(key: K, value: ScenarioFormFields[K]) => {
        setFields(prev => ({ ...prev, [key]: value }));
    }, []);

    const [accounts, setAccounts] = useState<ScenarioAccountInput[]>(
        initialValues?.accounts?.map(a => ({
            linked_account_id: a.linked_account_id,
            initial_balance: a.initial_balance,
            annual_contribution: a.annual_contribution,
            expected_return: toPercent(a.expected_return),
            account_type: a.account_type || 'taxable',
        })) ?? [defaultAccount()]
    );
    const [selectedIncomeSources, setSelectedIncomeSources] = useState<ScenarioIncomeSourceInput[]>(
        initialValues?.income_sources?.map(is => ({
            income_source_id: is.income_source_id,
            override_annual_amount: is.override_annual_amount,
        })) ?? []
    );
    const [saving, setSaving] = useState(false);

    const {
        name, retirementDate, endAge, inflationRate, birthYear, withdrawalRate,
        withdrawalStrategy, dynamicCeiling, dynamicFloor, filingStatus, otherIncome,
        annualRothConversion, rothConversionStrategy, targetBracketRate,
        rothConversionStartYear, withdrawalOrder, dynamicSequencingBracketRate,
        state, primaryResidencePropertyTax, primaryResidenceMortgageInterest,
        spendingPlanSelection,
    } = fields;

    function updateAccount(index: number, field: keyof ScenarioAccountInput, value: string | number | null) {
        setAccounts(prev => prev.map((a, i) => i === index ? { ...a, [field]: value } : a));
    }

    function addAccount() {
        setAccounts(prev => [...prev, defaultAccount()]);
    }

    function linkAccount(index: number, accountId: string) {
        if (!accountId) {
            updateAccount(index, 'linked_account_id', null);
            return;
        }
        const acct = existingAccounts.find(a => a.id === accountId);
        if (acct) {
            setAccounts(prev => prev.map((a, i) => i === index ? {
                ...a,
                linked_account_id: acct.id,
                initial_balance: acct.balance,
                account_type: mapAccountType(acct.type),
            } : a));
        }
    }

    function removeAccount(index: number) {
        setAccounts(prev => prev.filter((_, i) => i !== index));
    }

    async function handleSubmit() {
        setSaving(true);
        try {
            const request: CreateScenarioRequest = {
                name,
                retirement_date: retirementDate,
                end_age: endAge,
                inflation_rate: inflationRate / 100,
                birth_year: birthYear,
                withdrawal_rate: withdrawalRate / 100,
                withdrawal_strategy: withdrawalStrategy,
                dynamic_ceiling: withdrawalStrategy === 'vanguard_dynamic_spending' ? dynamicCeiling / 100 : null,
                dynamic_floor: withdrawalStrategy === 'vanguard_dynamic_spending' ? dynamicFloor / 100 : null,
                filing_status: (rothConversionStrategy === 'fill_bracket' || annualRothConversion > 0) ? filingStatus : null,
                other_income: (rothConversionStrategy === 'fill_bracket' || annualRothConversion > 0) ? otherIncome : null,
                annual_roth_conversion: rothConversionStrategy === 'fixed_amount' && annualRothConversion > 0 ? annualRothConversion : null,
                withdrawal_order: withdrawalOrder !== 'taxable_first' ? withdrawalOrder : null,
                ...(withdrawalOrder === 'dynamic_sequencing' ? {
                    dynamic_sequencing_bracket_rate: dynamicSequencingBracketRate,
                } : {}),
                roth_conversion_strategy: rothConversionStrategy !== 'fixed_amount' ? rothConversionStrategy : null,
                target_bracket_rate: rothConversionStrategy === 'fill_bracket' ? targetBracketRate / 100 : null,
                roth_conversion_start_year: rothConversionStartYear || null,
                state: state || null,
                primary_residence_property_tax: state ? primaryResidencePropertyTax : null,
                primary_residence_mortgage_interest: state ? primaryResidenceMortgageInterest : null,
                spending_profile_id: (spendingPlanSelection && spendingPlanSelection !== 'guardrail') ? spendingPlanSelection : null,
                use_guardrail_profile: spendingPlanSelection === 'guardrail' ? true : null,
                accounts: accounts.map(a => ({ ...a, expected_return: a.expected_return / 100 })),
                income_sources: selectedIncomeSources,
            };
            await onSubmit(request);
        } finally {
            setSaving(false);
        }
    }

    return (
        <div>
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
                <FormField
                    label="Spending Plan"
                    helpText="Choose a spending profile (user-defined tiers) or guardrail profile (Monte Carlo optimized). When linked, the projection withdraws what you need each year, minus non-portfolio income."
                >
                    <select style={inputStyle} value={spendingPlanSelection} onChange={e => setField('spendingPlanSelection', e.target.value)}>
                        <option value="">None (use withdrawal rate)</option>
                        {profiles?.map(p => (
                            <option key={p.id} value={p.id}>{p.name}</option>
                        ))}
                        {initialValues?.guardrail_profile && (
                            <option value="guardrail">
                                &#9881; {initialValues.guardrail_profile.name}{initialValues.guardrail_profile.stale ? ' (stale)' : ''}{!initialValues.guardrail_profile.active ? ' (inactive)' : ''}
                            </option>
                        )}
                    </select>
                    {spendingPlanSelection === 'guardrail' && !initialValues?.guardrail_profile && (
                        <div style={{ fontSize: '0.8rem', color: '#e65100', marginTop: '0.25rem' }}>
                            Guardrail profile no longer available. Please select another spending plan.
                        </div>
                    )}
                </FormField>
            </div>

            {availableIncomeSources && availableIncomeSources.length > 0 && (
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
                                                setSelectedIncomeSources(prev => [...prev, { income_source_id: is.id, override_annual_amount: null }]);
                                            } else {
                                                setSelectedIncomeSources(prev => prev.filter(s => s.income_source_id !== is.id));
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
                                                    setSelectedIncomeSources(prev => prev.map(s =>
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
            )}

            <WithdrawalStrategySection
                withdrawalStrategy={withdrawalStrategy}
                onWithdrawalStrategyChange={v => setField('withdrawalStrategy', v)}
                dynamicCeiling={dynamicCeiling}
                onDynamicCeilingChange={v => setField('dynamicCeiling', v)}
                dynamicFloor={dynamicFloor}
                onDynamicFloorChange={v => setField('dynamicFloor', v)}
                withdrawalOrder={withdrawalOrder}
                onWithdrawalOrderChange={v => setField('withdrawalOrder', v)}
                dynamicSequencingBracketRate={dynamicSequencingBracketRate}
                onDynamicSequencingBracketRateChange={v => setField('dynamicSequencingBracketRate', v)}
            />

            <RothConversionSection
                rothConversionStrategy={rothConversionStrategy}
                onRothConversionStrategyChange={v => setField('rothConversionStrategy', v)}
                annualRothConversion={annualRothConversion}
                onAnnualRothConversionChange={v => setField('annualRothConversion', v)}
                targetBracketRate={targetBracketRate}
                onTargetBracketRateChange={v => setField('targetBracketRate', v)}
                rothConversionStartYear={rothConversionStartYear}
                onRothConversionStartYearChange={v => setField('rothConversionStartYear', v)}
                filingStatus={filingStatus}
                onFilingStatusChange={v => setField('filingStatus', v)}
                otherIncome={otherIncome}
                onOtherIncomeChange={v => setField('otherIncome', v)}
            />

            <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', marginBottom: '1rem' }}>
                <h4 style={{ marginBottom: '0.75rem' }}>Tax Configuration</h4>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem' }}>
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
                </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <h4>Accounts</h4>
                <button
                    onClick={addAccount}
                    style={{ padding: '0.25rem 0.75rem', background: '#4caf50', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                >
                    + Add Account
                </button>
            </div>
            <div style={{ fontSize: '0.85rem', color: '#666', marginBottom: '0.75rem' }}>
                Each account represents a pool of investments with its own tax treatment, growth rate, and contribution schedule.
            </div>
            {accounts.map((acct, idx) => (
                <div key={idx} style={{ border: '1px solid #e0e0e0', borderRadius: '8px', padding: '1rem', marginBottom: '0.75rem' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: '1rem', marginBottom: '0.75rem', alignItems: 'end' }}>
                        <FormField
                            label="Link Existing Account"
                            helpText={acct.linked_account_id
                                ? 'Balance updates automatically each time the projection runs.'
                                : 'Enter values manually, or select an existing account above.'}
                        >
                            <select
                                style={inputStyle}
                                value={acct.linked_account_id ?? ''}
                                onChange={e => linkAccount(idx, e.target.value)}
                            >
                                <option value="">Manual Entry</option>
                                {existingAccounts.map(a => (
                                    <option key={a.id} value={a.id}>
                                        {a.name} ({a.institution ?? a.type}) — {formatCurrency(a.balance)}
                                    </option>
                                ))}
                            </select>
                        </FormField>
                        <div>
                            {accounts.length > 1 && (
                                <Button
                                    onClick={() => removeAccount(idx)}
                                    variant="danger"
                                    size="sm"
                                    style={{ background: 'none', border: '1px solid #d32f2f', color: '#d32f2f' }}
                                >
                                    Remove
                                </Button>
                            )}
                        </div>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', alignItems: 'start' }}>
                        <FormField label="Account Type" helpText={ACCOUNT_TYPE_HELP[acct.account_type || 'taxable']}>
                            <select style={inputStyle} value={acct.account_type || 'taxable'} onChange={e => updateAccount(idx, 'account_type', e.target.value)}>
                                <option value="taxable">Taxable</option>
                                <option value="traditional">Traditional (Pre-tax)</option>
                                <option value="roth">Roth</option>
                            </select>
                        </FormField>
                        <FormField label={`Initial Balance${acct.linked_account_id ? ' (live)' : ''}`}>
                            <CurrencyInput
                                style={{ ...inputStyle, ...(acct.linked_account_id ? { background: '#f5f5f5' } : {}) }}
                                value={acct.initial_balance || ''}
                                onChange={v => updateAccount(idx, 'initial_balance', Number(v) || 0)}
                                readOnly={!!acct.linked_account_id}
                            />
                        </FormField>
                        <FormField label="Annual Contribution">
                            <CurrencyInput style={inputStyle} value={acct.annual_contribution || ''} onChange={v => updateAccount(idx, 'annual_contribution', Number(v) || 0)} />
                        </FormField>
                        <FormField label="Expected Return (%)">
                            <input style={inputStyle} type="number" step="0.1" value={acct.expected_return || ''} onChange={e => updateAccount(idx, 'expected_return', Number(e.target.value))} />
                        </FormField>
                    </div>
                </div>
            ))}

            <Button
                onClick={handleSubmit}
                disabled={saving}
                style={{ marginTop: '0.5rem' }}
            >
                {saving ? 'Saving...' : submitLabel}
            </Button>
        </div>
    );
}
