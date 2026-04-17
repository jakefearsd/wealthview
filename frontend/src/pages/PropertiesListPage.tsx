import { useState, useCallback } from 'react';
import { Link } from 'react-router';
import { listProperties, createProperty, updateProperty, deleteProperty } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useCrudForm } from '../hooks/useCrudForm';
import { useAuth } from '../context/AuthContext';
import { formatCurrency, toPercent } from '../utils/format';
import PropertyForm, { type PropertyFormValues, type CostSegAllocations } from '../components/PropertyForm';
import LoadingState from '../components/LoadingState';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import Button from '../components/Button';
import type { Property } from '../types/property';

const initialFormData: PropertyFormValues = {
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

function buildCostSegAllocations(allocs: CostSegAllocations) {
    const result = [];
    if (allocs.fiveYr && parseFloat(allocs.fiveYr) > 0) result.push({ asset_class: '5yr', allocation: parseFloat(allocs.fiveYr) });
    if (allocs.sevenYr && parseFloat(allocs.sevenYr) > 0) result.push({ asset_class: '7yr', allocation: parseFloat(allocs.sevenYr) });
    if (allocs.fifteenYr && parseFloat(allocs.fifteenYr) > 0) result.push({ asset_class: '15yr', allocation: parseFloat(allocs.fifteenYr) });
    if (allocs.twentySevenYr && parseFloat(allocs.twentySevenYr) > 0) result.push({ asset_class: '27_5yr', allocation: parseFloat(allocs.twentySevenYr) });
    return result;
}

function buildRequest(data: PropertyFormValues) {
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

export default function PropertiesListPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member' || role === 'super_admin';
    const { data: properties, loading, error, refetch } = useApiQuery(listProperties);
    const [showForm, setShowForm] = useState(false);

    const onSuccess = useCallback(() => {
        setShowForm(false);
        refetch();
    }, [refetch]);

    const createFn = useCallback(async (data: PropertyFormValues): Promise<Property> => {
        return createProperty(buildRequest(data));
    }, []);

    const updateFn = useCallback(async (id: string, data: PropertyFormValues): Promise<Property> => {
        return updateProperty(id, buildRequest(data));
    }, []);

    const { editingId, formData, setFormData, handleSave, handleDelete: crudHandleDelete, resetForm: crudReset, startEdit: crudStartEdit } = useCrudForm<Property, PropertyFormValues>({
        createFn,
        updateFn,
        deleteFn: deleteProperty,
        entityName: 'Property',
        initialFormData,
        onSuccess,
        formatError: undefined,
    });

    const handleDelete = useCallback(async (id: string) => {
        if (!confirm('Delete this property?')) return;
        await crudHandleDelete(id);
    }, [crudHandleDelete]);

    const resetForm = useCallback(() => {
        crudReset();
        setShowForm(false);
    }, [crudReset]);

    function allocationsToState(allocs: Property['cost_seg_allocations']): CostSegAllocations {
        const state: CostSegAllocations = { fiveYr: '', sevenYr: '', fifteenYr: '', twentySevenYr: '' };
        for (const a of allocs ?? []) {
            if (a.asset_class === '5yr') state.fiveYr = String(a.allocation);
            else if (a.asset_class === '7yr') state.sevenYr = String(a.allocation);
            else if (a.asset_class === '15yr') state.fifteenYr = String(a.allocation);
            else if (a.asset_class === '27_5yr') state.twentySevenYr = String(a.allocation);
        }
        return state;
    }

    function startEdit(property: Property) {
        const hasFinancialFields = property.annual_appreciation_rate != null
            || property.annual_property_tax != null || property.annual_insurance_cost != null
            || property.annual_maintenance_cost != null;
        crudStartEdit(property.id, {
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
        setShowForm(true);
    }

    if (loading) return <LoadingState message="Loading properties..." />;
    if (error) return <ErrorState message={error} onRetry={refetch} />;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Properties</h2>
                {canWrite && <Button onClick={() => setShowForm(true)}>New Property</Button>}
            </div>

            {showForm && (
                <PropertyForm
                    heading={editingId ? 'Edit Property' : 'Create Property'}
                    submitLabel={editingId ? 'Save' : 'Create'}
                    values={formData}
                    onChange={(patch) => setFormData(prev => ({ ...prev, ...patch }))}
                    purchasePriceNum={parseFloat(formData.purchasePrice) || 0}
                    onSubmit={handleSave}
                    onCancel={resetForm}
                />
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(420px, 1fr))', gap: '1rem' }}>
                {properties?.map((p) => (
                    <div key={p.id} style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                        <Link to={`/properties/${p.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
                                <h3 style={{ margin: 0 }}>{p.address}</h3>
                                <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
                                    <span style={{ padding: '0.2rem 0.6rem', background: p.property_type === 'investment' ? '#fff3e0' : p.property_type === 'vacation' ? '#e8f5e9' : '#e3f2fd', color: p.property_type === 'investment' ? '#e65100' : p.property_type === 'vacation' ? '#2e7d32' : '#1565c0', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 600 }}>
                                        {p.property_type === 'primary_residence' ? 'Primary' : p.property_type === 'investment' ? 'Investment' : 'Vacation'}
                                    </span>
                                    {p.use_computed_balance && (
                                        <span style={{ padding: '0.2rem 0.6rem', background: '#e3f2fd', color: '#1565c0', borderRadius: '4px', fontSize: '0.75rem' }}>Amortized</span>
                                    )}
                                </div>
                            </div>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', marginBottom: '1rem' }}>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Current Value</div>
                                    <div style={{ fontSize: '1.3rem', fontWeight: 700, color: '#1b5e20' }}>{formatCurrency(p.current_value)}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Equity</div>
                                    <div style={{ fontSize: '1.3rem', fontWeight: 700, color: '#1565c0' }}>{formatCurrency(p.equity)}</div>
                                </div>
                            </div>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', fontSize: '0.9rem' }}>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Purchase Price</div>
                                    <div style={{ color: '#444' }}>{formatCurrency(p.purchase_price)}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Mortgage Balance</div>
                                    <div style={{ color: '#444' }}>{p.mortgage_balance ? formatCurrency(p.mortgage_balance) : 'None'}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Purchase Date</div>
                                    <div style={{ color: '#444' }}>{new Date(p.purchase_date + 'T00:00:00').toLocaleDateString()}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Appreciation</div>
                                    <div style={{ color: p.current_value >= p.purchase_price ? '#2e7d32' : '#d32f2f' }}>
                                        {formatCurrency(p.current_value - p.purchase_price)} ({((p.current_value - p.purchase_price) / p.purchase_price * 100).toFixed(1)}%)
                                    </div>
                                </div>
                            </div>
                            {(p.has_loan_details || p.annual_appreciation_rate != null) && (
                                <div style={{ borderTop: '1px solid #eee', marginTop: '0.75rem', paddingTop: '0.75rem', display: 'flex', gap: '1.5rem', fontSize: '0.85rem', color: '#666', flexWrap: 'wrap' }}>
                                    {p.has_loan_details && p.annual_interest_rate != null && (
                                        <span>Rate: {(p.annual_interest_rate * 100).toFixed(2)}%</span>
                                    )}
                                    {p.has_loan_details && p.loan_term_months != null && (
                                        <span>{Math.round(p.loan_term_months / 12)}yr term</span>
                                    )}
                                    {p.annual_appreciation_rate != null && (
                                        <span>Appr: {(p.annual_appreciation_rate * 100).toFixed(1)}%/yr</span>
                                    )}
                                </div>
                            )}
                        </Link>
                        {canWrite && (
                            <div style={{ marginTop: '1rem', paddingTop: '0.75rem', borderTop: '1px solid #eee', display: 'flex', gap: '0.5rem' }}>
                                <Button onClick={() => startEdit(p)} size="sm">Edit</Button>
                                <Button onClick={() => handleDelete(p.id)} variant="danger" size="sm">Delete</Button>
                            </div>
                        )}
                    </div>
                ))}
                {properties?.length === 0 && (
                    <EmptyState
                        title="No properties"
                        message="Add a property to track its value and equity."
                    />
                )}
            </div>
        </div>
    );
}
