import { useCallback, useState } from 'react';
import { useApiQuery } from '../hooks/useApiQuery';
import { listAccounts } from '../api/accounts';
import { listSpendingProfiles } from '../api/spendingProfiles';
import { listIncomeSources } from '../api/incomeSources';
import { toPercent } from '../utils/format';
import WithdrawalStrategySection from './WithdrawalStrategySection';
import RothConversionSection from './RothConversionSection';
import ScenarioBasicsSection from './scenario/ScenarioBasicsSection';
import ScenarioIncomeSourcesSection from './scenario/ScenarioIncomeSourcesSection';
import ScenarioTaxSection from './scenario/ScenarioTaxSection';
import ScenarioHouseholdSection from './scenario/ScenarioHouseholdSection';
import ScenarioAccountsSection from './scenario/ScenarioAccountsSection';
import {
    DEFAULT_SURVIVOR_SPENDING_FACTOR,
    DEFAULT_LONGEVITY_CONDITIONAL_AGE,
    type ScenarioFormFields,
} from './scenario/scenarioFormFields';
import { isAllocationValid } from '../utils/allocation';
import type { Account } from '../types/account';
import type {
    Scenario,
    CreateScenarioRequest,
    ScenarioAccountInput,
    ScenarioIncomeSourceInput,
    AllocationInput,
} from '../types/projection';
import Button from './Button';

/** Neutral starting point when a user opts into a custom allocation with no prior mix to seed from. */
const DEFAULT_ALL_US_ALLOCATION: AllocationInput = { us_stock: 100, intl_stock: 0, bond: 0, cash: 0 };

interface ScenarioFormProps {
    initialValues?: Scenario | null;
    onSubmit: (data: CreateScenarioRequest) => Promise<void>;
    submitLabel: string;
}

function defaultAccount(): ScenarioAccountInput {
    return {
        linked_account_id: null,
        initial_balance: 100000,
        annual_contribution: 10000,
        // No override by default — the allocation-derived return (this phase's centerpiece)
        // drives growth unless the user explicitly enters one.
        expected_return: null,
        account_type: 'taxable',
        cost_basis: null,
        allocation: null,
        owner: 'primary',
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
        dividendYield: parsedParams.dividend_yield != null ? parsedParams.dividend_yield * 100 : 1.8,
        feeRate: parsedParams.fee_rate != null ? parsedParams.fee_rate * 100 : 0.25,
        interestYield: parsedParams.interest_yield != null ? parsedParams.interest_yield * 100 : 4.0,
        includeDepressionYears: parsedParams.include_depression_years ?? false,
        spendingPlanSelection,
        spouseBirthYear: parsedParams.spouse_birth_year ?? null,
        primaryDeathAge: parsedParams.primary_death_age ?? null,
        spouseDeathAge: parsedParams.spouse_death_age ?? null,
        survivorSpendingFactor: parsedParams.survivor_spending_factor != null
            ? toPercent(parsedParams.survivor_spending_factor)
            : DEFAULT_SURVIVOR_SPENDING_FACTOR,
        communityProperty: parsedParams.community_property ?? false,
        stochasticMortality: parsedParams.stochastic_mortality ?? false,
        primarySex: parsedParams.primary_sex ?? null,
        spouseSex: parsedParams.spouse_sex ?? null,
        longevityConditionalAge: parsedParams.longevity_conditional_age ?? DEFAULT_LONGEVITY_CONDITIONAL_AGE,
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
            // expected_return is an optional override; preserve null (no override) as blank —
            // do NOT collapse it to 0, which is a distinct, genuine 0% override on the backend.
            expected_return: a.expected_return != null ? toPercent(a.expected_return) : undefined,
            account_type: a.account_type || 'taxable',
            cost_basis: a.cost_basis ?? null,
            // Only seed the editor with the response allocation when it's a real user override;
            // a derived/auto mix is shown as a read-only summary instead (see derivedAllocations)
            // so re-saving without touching it keeps sending null (auto-derive) rather than
            // freezing a snapshot of the derived mix as a permanent override.
            allocation: a.allocation_is_override ? (a.allocation ?? null) : null,
            owner: a.owner || 'primary',
        })) ?? [defaultAccount()]
    );
    // Parallel-indexed with `accounts`: the holdings-derived mix the backend last reported per
    // account, used to render the "Derived from holdings" summary and to seed the editor with a
    // sensible starting point when the user opts into customizing. When the response allocation
    // is a user override, the true derived mix is unknown (it needs a projection run), so seed
    // null rather than letting a later reset-to-derived echo the removed override as "derived".
    const [derivedAllocations, setDerivedAllocations] = useState<(AllocationInput | null)[]>(
        initialValues?.accounts?.map(a => a.allocation_is_override ? null : (a.allocation ?? null)) ?? [null]
    );
    const [selectedIncomeSources, setSelectedIncomeSources] = useState<ScenarioIncomeSourceInput[]>(
        initialValues?.income_sources?.map(is => ({
            income_source_id: is.income_source_id,
            override_annual_amount: is.override_annual_amount,
        })) ?? []
    );
    const [saving, setSaving] = useState(false);
    const hasInvalidAllocation = accounts.some(a => a.allocation != null && !isAllocationValid(a.allocation));

    const {
        name, retirementDate, endAge, inflationRate, birthYear, withdrawalRate,
        withdrawalStrategy, dynamicCeiling, dynamicFloor, filingStatus, otherIncome,
        annualRothConversion, rothConversionStrategy, targetBracketRate,
        rothConversionStartYear, withdrawalOrder, dynamicSequencingBracketRate,
        state, primaryResidencePropertyTax, primaryResidenceMortgageInterest,
        dividendYield, feeRate, interestYield, includeDepressionYears, spendingPlanSelection,
        spouseBirthYear, primaryDeathAge, spouseDeathAge, survivorSpendingFactor, communityProperty,
        stochasticMortality, primarySex, spouseSex, longevityConditionalAge,
    } = fields;

    const household = spouseBirthYear != null;

    // Clearing the spouse birth year nulls (and hides) every dependent household field — a
    // household field with no spouse is meaningless, mirrors the backend's own validation
    // (ScenarioCrudService.validateHouseholdFields), and guarantees a stale value can't sneak
    // back into the payload if the user re-adds a spouse later.
    function handleSpouseBirthYearChange(raw: string) {
        const value = raw === '' ? null : Number(raw);
        setFields(prev => ({
            ...prev,
            spouseBirthYear: value,
            ...(value == null ? {
                primaryDeathAge: null,
                spouseDeathAge: null,
                survivorSpendingFactor: DEFAULT_SURVIVOR_SPENDING_FACTOR,
                communityProperty: false,
                stochasticMortality: false,
                primarySex: null,
                spouseSex: null,
                longevityConditionalAge: DEFAULT_LONGEVITY_CONDITIONAL_AGE,
            } : {}),
        }));
    }

    function updateAccount(index: number, field: keyof ScenarioAccountInput, value: string | number | null | AllocationInput) {
        setAccounts(prev => prev.map((a, i) => {
            if (i !== index) return a;
            const updated = { ...a, [field]: value };
            // "joint" is only valid for taxable accounts -- if the type changes away from taxable
            // while joint is selected, fall back to primary rather than submit an invalid pair.
            if (field === 'account_type' && updated.owner === 'joint' && value !== 'taxable') {
                updated.owner = 'primary';
            }
            return updated;
        }));
    }

    function customizeAllocation(index: number) {
        const seed = derivedAllocations[index] ?? DEFAULT_ALL_US_ALLOCATION;
        updateAccount(index, 'allocation', seed);
    }

    function addAccount() {
        setAccounts(prev => [...prev, defaultAccount()]);
        setDerivedAllocations(prev => [...prev, null]);
    }

    function linkAccount(index: number, accountId: string) {
        if (!accountId) {
            updateAccount(index, 'linked_account_id', null);
            return;
        }
        const acct = existingAccounts.find(a => a.id === accountId);
        if (acct) {
            const newAccountType = mapAccountType(acct.type);
            setAccounts(prev => prev.map((a, i) => i === index ? {
                ...a,
                linked_account_id: acct.id,
                initial_balance: acct.balance,
                account_type: newAccountType,
                // Linking derives allocation, cost basis, and return from the account's holdings, so
                // clear any stale manual override carried over from the row's prior state — a
                // leftover override is NOT link-gated and would wrongly apply to the new account.
                allocation: null,
                cost_basis: null,
                expected_return: null,
                // "joint" is only valid for taxable accounts -- drop a stale joint owner if the
                // newly-linked account isn't taxable, same guard as a manual type change.
                owner: a.owner === 'joint' && newAccountType !== 'taxable' ? 'primary' : a.owner,
            } : a));
            // Newly linked account: we don't have a fetched derived mix for it yet (that
            // requires a projection run), so drop any stale summary from a previous selection.
            setDerivedAllocations(prev => prev.map((d, i) => i === index ? null : d));
        }
    }

    function removeAccount(index: number) {
        setAccounts(prev => prev.filter((_, i) => i !== index));
        setDerivedAllocations(prev => prev.filter((_, i) => i !== index));
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
                dividend_yield: dividendYield != null ? dividendYield / 100 : undefined,
                fee_rate: feeRate != null ? feeRate / 100 : undefined,
                interest_yield: interestYield != null ? interestYield / 100 : undefined,
                include_depression_years: includeDepressionYears,
                spouse_birth_year: spouseBirthYear,
                primary_death_age: household && primaryDeathAge != null ? primaryDeathAge : null,
                spouse_death_age: household && spouseDeathAge != null ? spouseDeathAge : null,
                survivor_spending_factor: household ? survivorSpendingFactor / 100 : null,
                community_property: household ? communityProperty : null,
                // Sub-project B: omitted (not explicit null) when inactive, so a toggle-off/no-household
                // request is byte-identical to a pre-Task-9 request — no new keys added.
                ...(household && stochasticMortality ? {
                    stochastic_mortality: true,
                    ...(primarySex ? { primary_sex: primarySex } : {}),
                    ...(spouseSex ? { spouse_sex: spouseSex } : {}),
                    longevity_conditional_age: longevityConditionalAge,
                } : {}),
                spending_profile_id: (spendingPlanSelection && spendingPlanSelection !== 'guardrail') ? spendingPlanSelection : null,
                use_guardrail_profile: spendingPlanSelection === 'guardrail' ? true : null,
                accounts: accounts.map(a => ({
                    ...a,
                    // expected_return is an optional override: send it only when set (including a
                    // genuine 0% override), and omit (undefined) when blank/null so the engine uses
                    // the allocation-derived return. Use != null, NOT a truthy check — a real 0
                    // must round-trip, not be dropped as falsy.
                    expected_return: a.expected_return != null ? a.expected_return / 100 : undefined,
                    cost_basis: a.cost_basis ?? null,
                    allocation: a.allocation ?? null,
                })),
                income_sources: selectedIncomeSources,
            };
            await onSubmit(request);
        } finally {
            setSaving(false);
        }
    }

    return (
        <div>
            <ScenarioBasicsSection
                fields={fields}
                setField={setField}
                profiles={profiles}
                guardrailProfile={initialValues?.guardrail_profile ?? null}
            />

            <ScenarioIncomeSourcesSection
                availableIncomeSources={availableIncomeSources}
                selectedIncomeSources={selectedIncomeSources}
                onSelectedIncomeSourcesChange={setSelectedIncomeSources}
            />

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

            <ScenarioTaxSection fields={fields} setField={setField} />

            <ScenarioHouseholdSection
                fields={fields}
                setField={setField}
                onSpouseBirthYearChange={handleSpouseBirthYearChange}
            />

            <ScenarioAccountsSection
                accounts={accounts}
                derivedAllocations={derivedAllocations}
                existingAccounts={existingAccounts}
                household={household}
                onAddAccount={addAccount}
                onLinkAccount={linkAccount}
                onRemoveAccount={removeAccount}
                onUpdateAccount={updateAccount}
                onCustomizeAllocation={customizeAllocation}
            />

            <Button
                onClick={handleSubmit}
                disabled={saving || hasInvalidAllocation}
                style={{ marginTop: '0.5rem' }}
            >
                {saving ? 'Saving...' : submitLabel}
            </Button>
            {hasInvalidAllocation && (
                <div style={{ fontSize: '0.8rem', color: '#d32f2f', marginTop: '0.4rem' }}>
                    One or more account allocations must sum to 100% before saving.
                </div>
            )}
        </div>
    );
}
