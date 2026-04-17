import { useCallback, useMemo, useState } from 'react';
import { useParams, Link } from 'react-router';
import { getProperty, updateProperty, addPropertyExpense, deletePropertyExpense, getCashFlow, getValuationHistory, refreshValuation, selectZpid, getPropertyAnalytics, listPropertyExpenses } from '../api/properties';
import { listIncomeSources } from '../api/incomeSources';
import type { Property, ZillowSearchResult } from '../types/property';
import { useApiQuery } from '../hooks/useApiQuery';
import { useCrudForm } from '../hooks/useCrudForm';
import { useAuth } from '../context/AuthContext';
import { formatCurrency, toPercent } from '../utils/format';
import { cardStyle } from '../utils/styles';
import PropertyAnalyticsSection from '../components/PropertyAnalyticsSection';
import PropertyValuationSection from '../components/PropertyValuationSection';
import PropertyCashFlowSection from '../components/PropertyCashFlowSection';
import PropertyRoiCard from '../components/PropertyRoiCard';
import PropertyForm, { type PropertyFormValues, type CostSegAllocations } from '../components/PropertyForm';
import toast from 'react-hot-toast';

type CostSegAllocationsState = CostSegAllocations;
type PropertyFormData = PropertyFormValues;

const initialFormData: PropertyFormData = {
    address: '',
    purchasePrice: '',
    purchaseDate: '',
    currentValue: '',
    mortgageBalance: '',
    showLoanDetails: false,
    loanAmount: '',
    annualInterestRate: '',
    loanTermMonths: '',
    loanStartDate: '',
    useComputedBalance: false,
    propertyType: 'primary_residence',
    showFinancialAssumptions: false,
    annualAppreciationRate: '',
    annualPropertyTax: '',
    annualInsuranceCost: '',
    annualMaintenanceCost: '',
    showDepreciation: false,
    depreciationMethod: 'none',
    inServiceDate: '',
    landValue: '',
    usefulLifeYears: '27.5',
    costSegAllocations: { fiveYr: '', sevenYr: '', fifteenYr: '', twentySevenYr: '' },
    bonusDepreciationRate: '100',
    costSegStudyYear: '',
};

function buildCostSegAllocations(allocs: CostSegAllocationsState) {
    const result = [];
    if (allocs.fiveYr && parseFloat(allocs.fiveYr) > 0) result.push({ asset_class: '5yr', allocation: parseFloat(allocs.fiveYr) });
    if (allocs.sevenYr && parseFloat(allocs.sevenYr) > 0) result.push({ asset_class: '7yr', allocation: parseFloat(allocs.sevenYr) });
    if (allocs.fifteenYr && parseFloat(allocs.fifteenYr) > 0) result.push({ asset_class: '15yr', allocation: parseFloat(allocs.fifteenYr) });
    if (allocs.twentySevenYr && parseFloat(allocs.twentySevenYr) > 0) result.push({ asset_class: '27_5yr', allocation: parseFloat(allocs.twentySevenYr) });
    return result;
}

function buildRequest(data: PropertyFormData) {
    const isCostSeg = data.depreciationMethod === 'cost_segregation';
    return {
        address: data.address,
        purchase_price: parseFloat(data.purchasePrice),
        purchase_date: data.purchaseDate,
        current_value: parseFloat(data.currentValue),
        mortgage_balance: data.mortgageBalance ? parseFloat(data.mortgageBalance) : undefined,
        property_type: data.propertyType,
        ...(data.showLoanDetails && data.loanAmount ? {
            loan_amount: parseFloat(data.loanAmount),
            annual_interest_rate: parseFloat(data.annualInterestRate) / 100,
            loan_term_months: parseInt(data.loanTermMonths),
            loan_start_date: data.loanStartDate,
            use_computed_balance: data.useComputedBalance,
        } : {}),
        annual_appreciation_rate: data.annualAppreciationRate ? parseFloat(data.annualAppreciationRate) / 100 : undefined,
        annual_property_tax: data.annualPropertyTax ? parseFloat(data.annualPropertyTax) : undefined,
        annual_insurance_cost: data.annualInsuranceCost ? parseFloat(data.annualInsuranceCost) : undefined,
        annual_maintenance_cost: data.annualMaintenanceCost ? parseFloat(data.annualMaintenanceCost) : undefined,
        depreciation_method: data.depreciationMethod,
        in_service_date: data.depreciationMethod !== 'none' ? (data.inServiceDate || data.purchaseDate || undefined) : undefined,
        land_value: data.landValue ? parseFloat(data.landValue) : undefined,
        useful_life_years: data.usefulLifeYears ? parseFloat(data.usefulLifeYears) : undefined,
        ...(isCostSeg ? {
            cost_seg_allocations: buildCostSegAllocations(data.costSegAllocations),
            bonus_depreciation_rate: parseFloat(data.bonusDepreciationRate) / 100,
            cost_seg_study_year: data.costSegStudyYear ? parseInt(data.costSegStudyYear) : undefined,
        } : {}),
    };
}

const CATEGORY_LABELS: Record<string, string> = {
    mortgage: 'Mortgage',
    tax: 'Tax',
    insurance: 'Insurance',
    maintenance: 'Maintenance',
    capex: 'CapEx',
    hoa: 'HOA',
    mgmt_fee: 'Management Fee',
};

function getDefaultRange() {
    const now = new Date();
    const from = new Date(now.getFullYear() - 1, now.getMonth(), 1);
    return {
        from: `${from.getFullYear()}-${String(from.getMonth() + 1).padStart(2, '0')}`,
        to: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`,
    };
}

export default function PropertyDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';
    const range = useMemo(getDefaultRange, []);
    const [refreshing, setRefreshing] = useState(false);
    const [zillowCandidates, setZillowCandidates] = useState<ZillowSearchResult[] | null>(null);
    const [analyticsYear, setAnalyticsYear] = useState<number | undefined>(undefined);
    const [showEditForm, setShowEditForm] = useState(false);

    const { data: property, refetch: refetchProperty } = useApiQuery(() => getProperty(id!));
    const { data: cashFlow, refetch: refetchCashFlow } = useApiQuery(() => getCashFlow(id!, range.from, range.to));
    const { data: valuations, refetch: refetchValuations } = useApiQuery(() => getValuationHistory(id!));
    const { data: analytics, refetch: refetchAnalytics } = useApiQuery(() => getPropertyAnalytics(id!, analyticsYear));
    const { data: allIncomeSources } = useApiQuery(listIncomeSources);
    const { data: expenses, refetch: refetchExpenses } = useApiQuery(() => listPropertyExpenses(id!));

    const linkedIncomeSources = useMemo(() => {
        if (!allIncomeSources || !id) return [];
        return allIncomeSources.filter(s => s.property_id === id);
    }, [allIncomeSources, id]);

    const onEditSuccess = useCallback(() => {
        setShowEditForm(false);
        refetchProperty();
    }, [refetchProperty]);

    const updateFn = useCallback(async (_id: string, data: PropertyFormData): Promise<Property> => {
        return updateProperty(id!, buildRequest(data));
    }, [id]);

    const createFn = useCallback(async (_data: PropertyFormData): Promise<Property> => {
        throw new Error('Create not supported on detail page');
    }, []);

    const { formData, setFormData, handleSave, resetForm: crudReset, startEdit } = useCrudForm<Property, PropertyFormData>({
        createFn,
        updateFn,
        entityName: 'Property',
        initialFormData,
        onSuccess: onEditSuccess,
        formatError: undefined,
    });

    function allocationsToState(allocs: Property['cost_seg_allocations']): CostSegAllocationsState {
        const state: CostSegAllocationsState = { fiveYr: '', sevenYr: '', fifteenYr: '', twentySevenYr: '' };
        for (const a of allocs ?? []) {
            if (a.asset_class === '5yr') state.fiveYr = String(a.allocation);
            else if (a.asset_class === '7yr') state.sevenYr = String(a.allocation);
            else if (a.asset_class === '15yr') state.fifteenYr = String(a.allocation);
            else if (a.asset_class === '27_5yr') state.twentySevenYr = String(a.allocation);
        }
        return state;
    }

    function handleStartEdit() {
        if (!property) return;
        const hasFinancialFields = property.annual_appreciation_rate != null
            || property.annual_property_tax != null || property.annual_insurance_cost != null
            || property.annual_maintenance_cost != null;
        startEdit(property.id, {
            address: property.address,
            purchasePrice: String(property.purchase_price),
            purchaseDate: property.purchase_date,
            currentValue: String(property.current_value),
            mortgageBalance: property.mortgage_balance ? String(property.mortgage_balance) : '',
            showLoanDetails: property.has_loan_details,
            loanAmount: property.loan_amount != null ? String(property.loan_amount) : '',
            annualInterestRate: property.annual_interest_rate != null ? String(toPercent(property.annual_interest_rate)) : '',
            loanTermMonths: property.loan_term_months != null ? String(property.loan_term_months) : '',
            loanStartDate: property.loan_start_date ?? '',
            useComputedBalance: property.use_computed_balance,
            propertyType: property.property_type,
            showFinancialAssumptions: hasFinancialFields,
            annualAppreciationRate: property.annual_appreciation_rate != null ? String(toPercent(property.annual_appreciation_rate)) : '',
            annualPropertyTax: property.annual_property_tax != null ? String(property.annual_property_tax) : '',
            annualInsuranceCost: property.annual_insurance_cost != null ? String(property.annual_insurance_cost) : '',
            annualMaintenanceCost: property.annual_maintenance_cost != null ? String(property.annual_maintenance_cost) : '',
            showDepreciation: !!property.depreciation_method && property.depreciation_method !== 'none',
            depreciationMethod: property.depreciation_method || 'none',
            inServiceDate: property.in_service_date ?? '',
            landValue: property.land_value != null ? String(property.land_value) : '',
            usefulLifeYears: String(property.useful_life_years || 27.5),
            costSegAllocations: allocationsToState(property.cost_seg_allocations),
            bonusDepreciationRate: String((property.bonus_depreciation_rate ?? 1) * 100),
            costSegStudyYear: property.cost_seg_study_year != null ? String(property.cost_seg_study_year) : '',
        });
        setShowEditForm(true);
    }

    function handleCancelEdit() {
        crudReset();
        setShowEditForm(false);
    }

    async function handleAddExpense(data: { date: string; amount: number; category: string; description?: string; frequency?: string }) {
        try {
            await addPropertyExpense(id!, data);
            toast.success('Expense added');
            refetchCashFlow();
            refetchExpenses();
        } catch {
            toast.error('Failed to add expense');
        }
    }

    async function handleDeleteExpense(expenseId: string) {
        if (!confirm('Delete this expense?')) return;
        try {
            await deletePropertyExpense(id!, expenseId);
            toast.success('Expense deleted');
            refetchCashFlow();
            refetchExpenses();
        } catch {
            toast.error('Failed to delete expense');
        }
    }

    async function handleRefreshValuation() {
        setRefreshing(true);
        try {
            const result = await refreshValuation(id!);
            if (result.status === 'updated') {
                toast.success(`Valuation updated: $${result.value?.toLocaleString()}`);
                refetchValuations();
                refetchProperty();
            } else if (result.status === 'multiple_matches') {
                setZillowCandidates(result.candidates);
            } else {
                toast.error('No Zillow results found for this address');
            }
        } catch (err: unknown) {
            if (err && typeof err === 'object' && 'response' in err) {
                const axiosErr = err as { response?: { status?: number } };
                if (axiosErr.response?.status === 503) {
                    toast.error('Valuation service is not enabled. Set app.zillow.enabled=true to use this feature.');
                    return;
                }
            }
            toast.error('Failed to refresh valuation');
        } finally {
            setRefreshing(false);
        }
    }

    async function handleSelectZpid(zpid: string) {
        setZillowCandidates(null);
        setRefreshing(true);
        try {
            const result = await selectZpid(id!, zpid);
            if (result.status === 'updated') {
                toast.success(`Valuation updated: $${result.value?.toLocaleString()}`);
                refetchValuations();
                refetchProperty();
            } else {
                toast.error('Could not fetch valuation for the selected property');
            }
        } catch {
            toast.error('Failed to select property');
        } finally {
            setRefreshing(false);
        }
    }

    function handleAnalyticsYearChange(value: string) {
        setAnalyticsYear(value === '' ? undefined : Number(value));
        setTimeout(refetchAnalytics, 0);
    }

    const analyticsYearOptions = useMemo(() => {
        if (!property?.purchase_date) return [];
        const purchaseYear = new Date(property.purchase_date).getFullYear();
        const currentYear = new Date().getFullYear();
        const years: number[] = [];
        for (let y = purchaseYear; y <= currentYear; y++) {
            years.push(y);
        }
        return years;
    }, [property?.purchase_date]);

    const badgeStyle = (color: string, bg: string) => ({
        display: 'inline-block',
        padding: '0.15rem 0.5rem',
        background: bg,
        color: color,
        borderRadius: '4px',
        fontSize: '0.75rem',
        fontWeight: 600 as const,
    });

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/properties" style={{ color: '#1976d2', textDecoration: 'none' }}>Properties</Link> / {property?.address}
            </div>

            {showEditForm && (
                <PropertyForm
                    heading="Edit Property"
                    submitLabel="Save"
                    values={formData}
                    onChange={(patch) => setFormData(prev => ({ ...prev, ...patch }))}
                    purchasePriceNum={parseFloat(formData.purchasePrice) || 0}
                    onSubmit={handleSave}
                    onCancel={handleCancelEdit}
                />
            )}

            {property && !showEditForm && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
                        <h2>{property.address}</h2>
                        <span style={badgeStyle(
                            property.property_type === 'investment' ? '#e65100' : property.property_type === 'vacation' ? '#1b5e20' : '#1565c0',
                            property.property_type === 'investment' ? '#fff3e0' : property.property_type === 'vacation' ? '#e8f5e9' : '#e3f2fd'
                        )}>
                            {property.property_type === 'primary_residence' ? 'Primary Residence' : property.property_type === 'investment' ? 'Investment' : 'Vacation'}
                        </span>
                        {canWrite && (
                            <button
                                onClick={handleStartEdit}
                                style={{ marginLeft: 'auto', padding: '0.3rem 0.8rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                            >
                                Edit
                            </button>
                        )}
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem' }}>
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Purchase Price</div><div style={{ fontWeight: 600 }}>{formatCurrency(property.purchase_price)}</div></div>
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Current Value</div><div style={{ fontWeight: 600 }}>{formatCurrency(property.current_value)}</div></div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>
                                Mortgage{' '}
                                {property.use_computed_balance
                                    ? <span style={badgeStyle('#1565c0', '#e3f2fd')}>Computed</span>
                                    : <span style={badgeStyle('#666', '#eee')}>Manual</span>}
                            </div>
                            <div style={{ fontWeight: 600 }}>{formatCurrency(property.mortgage_balance)}</div>
                        </div>
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Equity</div><div style={{ fontWeight: 600, color: '#2e7d32' }}>{formatCurrency(property.equity)}</div></div>
                    </div>

                    {property.has_loan_details && (
                        <div style={{ marginTop: '1.5rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                            <h4 style={{ marginBottom: '0.5rem', fontSize: '0.9rem', color: '#444' }}>Loan Details</h4>
                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem', fontSize: '0.9rem' }}>
                                <div><span style={{ color: '#666' }}>Amount:</span> {formatCurrency(property.loan_amount!)}</div>
                                <div><span style={{ color: '#666' }}>Rate:</span> {((property.annual_interest_rate ?? 0) * 100).toFixed(2)}%</div>
                                <div><span style={{ color: '#666' }}>Term:</span> {property.loan_term_months} months</div>
                                <div><span style={{ color: '#666' }}>Start:</span> {property.loan_start_date}</div>
                            </div>
                        </div>
                    )}
                </div>
            )}

            {property && (
                <div style={{ ...cardStyle, marginBottom: '2rem', padding: '1rem 1.5rem' }}>
                    <h4 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem', color: '#444' }}>Linked Income Sources</h4>
                    {linkedIncomeSources.length > 0 ? (
                        linkedIncomeSources.map(source => (
                            <div key={source.id} style={{ display: 'flex', alignItems: 'center', gap: '1rem', padding: '0.5rem 0' }}>
                                <div>
                                    <div style={{ fontWeight: 600 }}>{source.name}</div>
                                    <div style={{ fontSize: '0.9rem', color: '#666' }}>
                                        {formatCurrency(source.annual_amount)}/year ({formatCurrency(source.annual_amount / 12)}/month)
                                    </div>
                                </div>
                            </div>
                        ))
                    ) : (
                        <div style={{ color: '#999', fontSize: '0.9rem' }}>
                            No income source linked. <Link to="/income-sources" style={{ color: '#1976d2', textDecoration: 'none' }}>Create one on the Income Sources page.</Link>
                        </div>
                    )}
                    {linkedIncomeSources.length > 0 && (
                        <div style={{ marginTop: '0.5rem' }}>
                            <Link to="/income-sources" style={{ color: '#1976d2', textDecoration: 'none', fontSize: '0.85rem' }}>
                                Manage on Income Sources page
                            </Link>
                        </div>
                    )}
                </div>
            )}

            {analytics && (
                <PropertyAnalyticsSection
                    analytics={analytics}
                    analyticsYear={analyticsYear}
                    analyticsYearOptions={analyticsYearOptions}
                    onYearChange={handleAnalyticsYearChange}
                    propertyId={id!}
                    depreciationMethod={property?.depreciation_method || 'none'}
                />
            )}

            {property?.property_type === 'investment' && linkedIncomeSources.length > 0 && (
                <div style={{ marginBottom: '2rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Hold vs. Sell Analysis</h3>
                    {linkedIncomeSources.map(source => (
                        <PropertyRoiCard
                            key={source.id}
                            propertyId={id!}
                            incomeSource={source}
                        />
                    ))}
                </div>
            )}

            <PropertyValuationSection
                valuations={valuations}
                canWrite={canWrite}
                refreshing={refreshing}
                zillowCandidates={zillowCandidates}
                onRefreshValuation={handleRefreshValuation}
                onSelectZpid={handleSelectZpid}
                onDismissCandidates={() => setZillowCandidates(null)}
            />

            <PropertyCashFlowSection
                cashFlow={cashFlow}
                canWrite={canWrite}
                onAddExpense={handleAddExpense}
            />

            {expenses && expenses.length > 0 && (
                <div style={{ ...cardStyle, marginTop: '2rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Recorded Expenses</h3>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #eee', textAlign: 'left' }}>
                                <th style={{ padding: '0.5rem' }}>Date</th>
                                <th style={{ padding: '0.5rem' }}>Category</th>
                                <th style={{ padding: '0.5rem', textAlign: 'right' }}>Amount</th>
                                <th style={{ padding: '0.5rem' }}>Frequency</th>
                                <th style={{ padding: '0.5rem' }}>Description</th>
                                {canWrite && <th style={{ padding: '0.5rem', width: '1px' }}></th>}
                            </tr>
                        </thead>
                        <tbody>
                            {expenses.map((exp) => (
                                <tr key={exp.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{exp.date}</td>
                                    <td style={{ padding: '0.5rem' }}>{CATEGORY_LABELS[exp.category] ?? exp.category}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(exp.amount)}</td>
                                    <td style={{ padding: '0.5rem' }}>
                                        <span style={{
                                            padding: '0.1rem 0.4rem',
                                            background: exp.frequency === 'annual' ? '#fff3e0' : '#e3f2fd',
                                            color: exp.frequency === 'annual' ? '#e65100' : '#1565c0',
                                            borderRadius: '4px',
                                            fontSize: '0.75rem',
                                            fontWeight: 600,
                                        }}>
                                            {exp.frequency === 'annual' ? 'Annual' : 'Monthly'}
                                        </span>
                                    </td>
                                    <td style={{ padding: '0.5rem', color: '#666' }}>{exp.description ?? ''}</td>
                                    {canWrite && (
                                        <td style={{ padding: '0.5rem' }}>
                                            <button
                                                onClick={() => handleDeleteExpense(exp.id)}
                                                style={{ padding: '0.2rem 0.5rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.75rem' }}
                                            >
                                                Delete
                                            </button>
                                        </td>
                                    )}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
