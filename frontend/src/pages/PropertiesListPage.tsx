import { useState, useCallback } from 'react';
import { Link } from 'react-router';
import { listProperties, createProperty, updateProperty, deleteProperty } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useCrudForm } from '../hooks/useCrudForm';
import { useAuth } from '../context/AuthContext';
import { toPercent } from '../utils/format';
import PropertyForm from '../components/PropertyForm';
import type { Property } from '../types/property';

function formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

interface CostSegAllocationsState {
    fiveYr: string;
    sevenYr: string;
    fifteenYr: string;
    twentySevenYr: string;
}

interface PropertyFormData {
    address: string;
    purchasePrice: string;
    purchaseDate: string;
    currentValue: string;
    mortgageBalance: string;
    showLoanDetails: boolean;
    loanAmount: string;
    annualInterestRate: string;
    loanTermMonths: string;
    loanStartDate: string;
    useComputedBalance: boolean;
    propertyType: string;
    showFinancialAssumptions: boolean;
    annualAppreciationRate: string;
    annualPropertyTax: string;
    annualInsuranceCost: string;
    annualMaintenanceCost: string;
    showDepreciation: boolean;
    depreciationMethod: string;
    inServiceDate: string;
    landValue: string;
    usefulLifeYears: string;
    costSegAllocations: CostSegAllocationsState;
    bonusDepreciationRate: string;
    costSegStudyYear: string;
}

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

export default function PropertiesListPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member' || role === 'super_admin';
    const { data: properties, loading, error, refetch } = useApiQuery(listProperties);
    const [showForm, setShowForm] = useState(false);

    const onSuccess = useCallback(() => {
        setShowForm(false);
        refetch();
    }, [refetch]);

    const createFn = useCallback(async (data: PropertyFormData): Promise<Property> => {
        return createProperty(buildRequest(data));
    }, []);

    const updateFn = useCallback(async (id: string, data: PropertyFormData): Promise<Property> => {
        return updateProperty(id, buildRequest(data));
    }, []);

    const { editingId, formData, setFormData, handleSave, handleDelete: crudHandleDelete, resetForm: crudReset, startEdit: crudStartEdit } = useCrudForm<Property, PropertyFormData>({
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

    if (loading) return <div>Loading properties...</div>;
    if (error) return <div style={{ color: '#d32f2f' }}>Error: {error}</div>;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Properties</h2>
                {canWrite && <button onClick={() => setShowForm(true)} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>New Property</button>}
            </div>

            {showForm && (
                <PropertyForm
                    heading={editingId ? 'Edit Property' : 'Create Property'}
                    submitLabel={editingId ? 'Save' : 'Create'}
                    address={formData.address} onAddressChange={v => setFormData(prev => ({ ...prev, address: v }))}
                    purchasePrice={formData.purchasePrice} onPurchasePriceChange={v => setFormData(prev => ({ ...prev, purchasePrice: v }))}
                    purchaseDate={formData.purchaseDate} onPurchaseDateChange={v => setFormData(prev => ({ ...prev, purchaseDate: v }))}
                    currentValue={formData.currentValue} onCurrentValueChange={v => setFormData(prev => ({ ...prev, currentValue: v }))}
                    mortgageBalance={formData.mortgageBalance} onMortgageBalanceChange={v => setFormData(prev => ({ ...prev, mortgageBalance: v }))}
                    propertyType={formData.propertyType} onPropertyTypeChange={v => setFormData(prev => ({ ...prev, propertyType: v }))}
                    showLoanDetails={formData.showLoanDetails} onShowLoanDetailsChange={v => setFormData(prev => ({ ...prev, showLoanDetails: v }))}
                    loanAmount={formData.loanAmount} onLoanAmountChange={v => setFormData(prev => ({ ...prev, loanAmount: v }))}
                    annualInterestRate={formData.annualInterestRate} onAnnualInterestRateChange={v => setFormData(prev => ({ ...prev, annualInterestRate: v }))}
                    loanTermMonths={formData.loanTermMonths} onLoanTermMonthsChange={v => setFormData(prev => ({ ...prev, loanTermMonths: v }))}
                    loanStartDate={formData.loanStartDate} onLoanStartDateChange={v => setFormData(prev => ({ ...prev, loanStartDate: v }))}
                    useComputedBalance={formData.useComputedBalance} onUseComputedBalanceChange={v => setFormData(prev => ({ ...prev, useComputedBalance: v }))}
                    showFinancialAssumptions={formData.showFinancialAssumptions} onShowFinancialAssumptionsChange={v => setFormData(prev => ({ ...prev, showFinancialAssumptions: v }))}
                    annualAppreciationRate={formData.annualAppreciationRate} onAnnualAppreciationRateChange={v => setFormData(prev => ({ ...prev, annualAppreciationRate: v }))}
                    annualPropertyTax={formData.annualPropertyTax} onAnnualPropertyTaxChange={v => setFormData(prev => ({ ...prev, annualPropertyTax: v }))}
                    annualInsuranceCost={formData.annualInsuranceCost} onAnnualInsuranceCostChange={v => setFormData(prev => ({ ...prev, annualInsuranceCost: v }))}
                    annualMaintenanceCost={formData.annualMaintenanceCost} onAnnualMaintenanceCostChange={v => setFormData(prev => ({ ...prev, annualMaintenanceCost: v }))}
                    showDepreciation={formData.showDepreciation} onShowDepreciationChange={v => setFormData(prev => ({ ...prev, showDepreciation: v }))}
                    depreciationMethod={formData.depreciationMethod} onDepreciationMethodChange={v => setFormData(prev => ({ ...prev, depreciationMethod: v }))}
                    inServiceDate={formData.inServiceDate} onInServiceDateChange={v => setFormData(prev => ({ ...prev, inServiceDate: v }))}
                    landValue={formData.landValue} onLandValueChange={v => setFormData(prev => ({ ...prev, landValue: v }))}
                    usefulLifeYears={formData.usefulLifeYears} onUsefulLifeYearsChange={v => setFormData(prev => ({ ...prev, usefulLifeYears: v }))}
                    costSegAllocations={formData.costSegAllocations} onCostSegAllocationsChange={v => setFormData(prev => ({ ...prev, costSegAllocations: v }))}
                    bonusDepreciationRate={formData.bonusDepreciationRate} onBonusDepreciationRateChange={v => setFormData(prev => ({ ...prev, bonusDepreciationRate: v }))}
                    costSegStudyYear={formData.costSegStudyYear} onCostSegStudyYearChange={v => setFormData(prev => ({ ...prev, costSegStudyYear: v }))}
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
                                <button onClick={() => startEdit(p)} style={{ padding: '0.3rem 0.6rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>Edit</button>
                                <button onClick={() => handleDelete(p.id)} style={{ padding: '0.3rem 0.6rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>Delete</button>
                            </div>
                        )}
                    </div>
                ))}
                {properties?.length === 0 && <div style={{ color: '#999' }}>No properties yet.</div>}
            </div>
        </div>
    );
}
