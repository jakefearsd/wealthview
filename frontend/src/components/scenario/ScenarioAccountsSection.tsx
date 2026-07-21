import AllocationEditor from '../AllocationEditor';
import Button from '../Button';
import CurrencyInput from '../CurrencyInput';
import FormField from '../FormField';
import { formatCurrency } from '../../utils/format';
import { inputStyle } from '../../utils/styles';
import type { Account } from '../../types/account';
import type { AllocationInput, ScenarioAccountInput } from '../../types/projection';

const ACCOUNT_TYPE_HELP: Record<string, string> = {
    taxable: 'Regular brokerage account. After-tax contributions, growth taxed as capital gains.',
    traditional: 'Pre-tax contributions reduce taxable income now. Withdrawals in retirement taxed as ordinary income.',
    roth: 'After-tax contributions. Growth and qualified withdrawals in retirement are completely tax-free.',
};

function formatAllocationSummary(a: AllocationInput): string {
    return `${a.us_stock.toFixed(1)}% US / ${a.intl_stock.toFixed(1)}% Intl / ${a.bond.toFixed(1)}% Bond / ${a.cash.toFixed(1)}% Cash`;
}

export interface ScenarioAccountsSectionProps {
    accounts: ScenarioAccountInput[];
    /** Parallel-indexed with `accounts`: the holdings-derived mix the backend last reported per account. */
    derivedAllocations: (AllocationInput | null)[];
    existingAccounts: Account[];
    household: boolean;
    onAddAccount: () => void;
    onLinkAccount: (index: number, accountId: string) => void;
    onRemoveAccount: (index: number) => void;
    onUpdateAccount: (index: number, field: keyof ScenarioAccountInput, value: string | number | null | AllocationInput) => void;
    onCustomizeAllocation: (index: number) => void;
}

/** Per-account cards: linking, type/owner, balances, contributions, return override, cost basis, and allocation. */
export default function ScenarioAccountsSection({
    accounts,
    derivedAllocations,
    existingAccounts,
    household,
    onAddAccount,
    onLinkAccount,
    onRemoveAccount,
    onUpdateAccount,
    onCustomizeAllocation,
}: ScenarioAccountsSectionProps) {
    return (
        <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <h4>Accounts</h4>
                <button
                    onClick={onAddAccount}
                    style={{ padding: '0.25rem 0.75rem', background: '#4caf50', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                >
                    + Add Account
                </button>
            </div>
            <div style={{ fontSize: '0.85rem', color: '#666', marginBottom: '0.75rem' }}>
                Each account represents a pool of investments with its own tax treatment, growth rate, and contribution schedule.
                {' '}Note: comparing Roth vs. Traditional contributions here doesn&apos;t model the pre-tax wage deduction a
                Traditional contribution provides today — only each account&apos;s own growth and withdrawal taxation.
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
                                onChange={e => onLinkAccount(idx, e.target.value)}
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
                                    onClick={() => onRemoveAccount(idx)}
                                    variant="danger"
                                    size="sm"
                                    style={{ background: 'none', border: '1px solid #d32f2f', color: '#d32f2f' }}
                                >
                                    Remove
                                </Button>
                            )}
                        </div>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: household ? '1fr 1fr 1fr 1fr 1fr' : '1fr 1fr 1fr 1fr', gap: '1rem', alignItems: 'start' }}>
                        <FormField label="Account Type" helpText={ACCOUNT_TYPE_HELP[acct.account_type || 'taxable']}>
                            <select style={inputStyle} value={acct.account_type || 'taxable'} onChange={e => onUpdateAccount(idx, 'account_type', e.target.value)}>
                                <option value="taxable">Taxable</option>
                                <option value="traditional">Traditional (Pre-tax)</option>
                                <option value="roth">Roth</option>
                            </select>
                        </FormField>
                        {household && (
                            <FormField label="Owner" helpText="Whose account this is. Joint ownership is only available for taxable accounts.">
                                <select style={inputStyle} value={acct.owner || 'primary'} onChange={e => onUpdateAccount(idx, 'owner', e.target.value)}>
                                    <option value="primary">Primary</option>
                                    <option value="spouse">Spouse</option>
                                    <option
                                        value="joint"
                                        disabled={(acct.account_type || 'taxable') !== 'taxable'}
                                        title={(acct.account_type || 'taxable') !== 'taxable' ? 'Joint ownership is only available for taxable accounts.' : undefined}
                                    >
                                        Joint
                                    </option>
                                </select>
                            </FormField>
                        )}
                        <FormField label={`Initial Balance${acct.linked_account_id ? ' (live)' : ''}`}>
                            <CurrencyInput
                                style={{ ...inputStyle, ...(acct.linked_account_id ? { background: '#f5f5f5' } : {}) }}
                                value={acct.initial_balance || ''}
                                onChange={v => onUpdateAccount(idx, 'initial_balance', Number(v) || 0)}
                                readOnly={!!acct.linked_account_id}
                            />
                        </FormField>
                        <FormField label="Annual Contribution">
                            <CurrencyInput style={inputStyle} value={acct.annual_contribution || ''} onChange={v => onUpdateAccount(idx, 'annual_contribution', Number(v) || 0)} />
                        </FormField>
                        <FormField label="Override Return (%)" helpText="Blank uses the allocation-derived return.">
                            <input
                                style={inputStyle}
                                type="number"
                                step="0.1"
                                value={acct.expected_return ?? ''}
                                onChange={e => onUpdateAccount(idx, 'expected_return', e.target.value === '' ? null : Number(e.target.value))}
                            />
                        </FormField>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 3fr', gap: '1rem', marginTop: '0.75rem', alignItems: 'start' }}>
                        <FormField
                            label="Cost Basis"
                            helpText={acct.linked_account_id
                                ? 'Derived from the linked account\'s holdings.'
                                : 'Total dollars invested, used for capital-gains tax calculations.'}
                        >
                            {!acct.linked_account_id ? (
                                <CurrencyInput
                                    style={inputStyle}
                                    value={acct.cost_basis ?? ''}
                                    onChange={v => onUpdateAccount(idx, 'cost_basis', v ? Number(v) : null)}
                                />
                            ) : (
                                <div style={{ ...inputStyle, background: '#f5f5f5' }}>
                                    {acct.cost_basis != null ? formatCurrency(acct.cost_basis) : 'Available after first run'}
                                </div>
                            )}
                        </FormField>
                        <FormField
                            label="Allocation"
                            helpText="US/Intl stocks, bonds, and cash drive the account's projected return. Leave derived to use the linked holdings' actual mix."
                        >
                            {acct.allocation == null ? (
                                <div>
                                    <div style={{ fontSize: '0.85rem', color: '#666', marginBottom: '0.4rem' }}>
                                        Derived from holdings
                                        {derivedAllocations[idx] && ` (${formatAllocationSummary(derivedAllocations[idx] as AllocationInput)})`}
                                    </div>
                                    <button
                                        type="button"
                                        onClick={() => onCustomizeAllocation(idx)}
                                        style={{ padding: '0.25rem 0.6rem', background: 'none', border: '1px solid #1976d2', color: '#1976d2', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}
                                    >
                                        Customize allocation
                                    </button>
                                </div>
                            ) : (
                                <AllocationEditor
                                    idPrefix={`acct-${idx}-`}
                                    value={acct.allocation}
                                    onChange={a => onUpdateAccount(idx, 'allocation', a)}
                                    onReset={() => onUpdateAccount(idx, 'allocation', null)}
                                />
                            )}
                        </FormField>
                    </div>
                </div>
            ))}
        </>
    );
}
