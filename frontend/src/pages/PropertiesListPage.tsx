import { useState, useCallback } from 'react';
import { Link } from 'react-router';
import { listProperties, createProperty, updateProperty, deleteProperty } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useCrudForm } from '../hooks/useCrudForm';
import { useAuth } from '../context/AuthContext';
import { formatCurrency, toPercent } from '../utils/format';
import PropertyForm, { type PropertyFormValues } from '../components/PropertyForm';
import LoadingState from '../components/LoadingState';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import Button from '../components/Button';
import StatTile from '../components/StatTile';
import type { Property } from '../types/property';
import { buildRequest, allocationsToState } from '../utils/propertyRequest';

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
                                <StatTile
                                    label="Current Value"
                                    value={formatCurrency(p.current_value)}
                                    valueColor="#1b5e20"
                                    valueStyle={{ fontSize: '1.3rem', fontWeight: 700 }}
                                />
                                <StatTile
                                    label="Equity"
                                    value={formatCurrency(p.equity)}
                                    valueColor="#1565c0"
                                    valueStyle={{ fontSize: '1.3rem', fontWeight: 700 }}
                                />
                            </div>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', fontSize: '0.9rem' }}>
                                <StatTile label="Purchase Price" value={formatCurrency(p.purchase_price)} />
                                <StatTile label="Mortgage Balance" value={p.mortgage_balance ? formatCurrency(p.mortgage_balance) : 'None'} />
                                <StatTile label="Purchase Date" value={new Date(p.purchase_date + 'T00:00:00').toLocaleDateString()} />
                                <StatTile
                                    label="Appreciation"
                                    value={`${formatCurrency(p.current_value - p.purchase_price)} (${((p.current_value - p.purchase_price) / p.purchase_price * 100).toFixed(1)}%)`}
                                    valueColor={p.current_value >= p.purchase_price ? '#2e7d32' : '#d32f2f'}
                                />
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
