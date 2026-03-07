import { useState } from 'react';
import { Link } from 'react-router-dom';
import { listProperties, createProperty, updateProperty, deleteProperty } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import toast from 'react-hot-toast';
import PropertyForm from '../components/PropertyForm';
import type { Property } from '../types/property';

function formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

export default function PropertiesListPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';
    const { data: properties, loading, error, refetch } = useApiQuery(listProperties);
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [address, setAddress] = useState('');
    const [purchasePrice, setPurchasePrice] = useState('');
    const [purchaseDate, setPurchaseDate] = useState('');
    const [currentValue, setCurrentValue] = useState('');
    const [mortgageBalance, setMortgageBalance] = useState('');
    const [showLoanDetails, setShowLoanDetails] = useState(false);
    const [loanAmount, setLoanAmount] = useState('');
    const [annualInterestRate, setAnnualInterestRate] = useState('');
    const [loanTermMonths, setLoanTermMonths] = useState('');
    const [loanStartDate, setLoanStartDate] = useState('');
    const [useComputedBalance, setUseComputedBalance] = useState(false);
    const [propertyType, setPropertyType] = useState('primary_residence');

    function resetForm() {
        setAddress('');
        setPurchasePrice('');
        setPurchaseDate('');
        setCurrentValue('');
        setMortgageBalance('');
        setShowLoanDetails(false);
        setLoanAmount('');
        setAnnualInterestRate('');
        setLoanTermMonths('');
        setLoanStartDate('');
        setUseComputedBalance(false);
        setPropertyType('primary_residence');
        setEditingId(null);
        setShowForm(false);
    }

    function startEdit(property: Property) {
        setAddress(property.address);
        setPurchasePrice(String(property.purchase_price));
        setPurchaseDate(property.purchase_date);
        setCurrentValue(String(property.current_value));
        setMortgageBalance(property.mortgage_balance ? String(property.mortgage_balance) : '');
        setShowLoanDetails(property.has_loan_details);
        setLoanAmount(property.loan_amount != null ? String(property.loan_amount) : '');
        setAnnualInterestRate(property.annual_interest_rate != null ? String(property.annual_interest_rate) : '');
        setLoanTermMonths(property.loan_term_months != null ? String(property.loan_term_months) : '');
        setLoanStartDate(property.loan_start_date ?? '');
        setUseComputedBalance(property.use_computed_balance);
        setPropertyType(property.property_type);
        setEditingId(property.id);
        setShowForm(true);
    }

    async function handleSave() {
        const request = {
            address,
            purchase_price: parseFloat(purchasePrice),
            purchase_date: purchaseDate,
            current_value: parseFloat(currentValue),
            mortgage_balance: mortgageBalance ? parseFloat(mortgageBalance) : undefined,
            property_type: propertyType,
            ...(showLoanDetails && loanAmount ? {
                loan_amount: parseFloat(loanAmount),
                annual_interest_rate: parseFloat(annualInterestRate),
                loan_term_months: parseInt(loanTermMonths),
                loan_start_date: loanStartDate,
                use_computed_balance: useComputedBalance,
            } : {}),
        };
        try {
            if (editingId) {
                await updateProperty(editingId, request);
                toast.success('Property updated');
            } else {
                await createProperty(request);
                toast.success('Property created');
            }
            resetForm();
            refetch();
        } catch {
            toast.error(editingId ? 'Failed to update property' : 'Failed to create property');
        }
    }

    async function handleDelete(id: string) {
        if (!confirm('Delete this property?')) return;
        try {
            await deleteProperty(id);
            toast.success('Property deleted');
            refetch();
        } catch {
            toast.error('Failed to delete property');
        }
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
                    address={address} onAddressChange={setAddress}
                    purchasePrice={purchasePrice} onPurchasePriceChange={setPurchasePrice}
                    purchaseDate={purchaseDate} onPurchaseDateChange={setPurchaseDate}
                    currentValue={currentValue} onCurrentValueChange={setCurrentValue}
                    mortgageBalance={mortgageBalance} onMortgageBalanceChange={setMortgageBalance}
                    propertyType={propertyType} onPropertyTypeChange={setPropertyType}
                    showLoanDetails={showLoanDetails} onShowLoanDetailsChange={setShowLoanDetails}
                    loanAmount={loanAmount} onLoanAmountChange={setLoanAmount}
                    annualInterestRate={annualInterestRate} onAnnualInterestRateChange={setAnnualInterestRate}
                    loanTermMonths={loanTermMonths} onLoanTermMonthsChange={setLoanTermMonths}
                    loanStartDate={loanStartDate} onLoanStartDateChange={setLoanStartDate}
                    useComputedBalance={useComputedBalance} onUseComputedBalanceChange={setUseComputedBalance}
                    onSubmit={handleSave}
                    onCancel={resetForm}
                />
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '1rem' }}>
                {properties?.map((p) => (
                    <div key={p.id} style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                        <Link to={`/properties/${p.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                            <h3 style={{ marginBottom: '0.5rem' }}>{p.address}</h3>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem', fontSize: '0.9rem' }}>
                                <div><span style={{ color: '#666' }}>Value:</span> {formatCurrency(p.current_value)}</div>
                                <div><span style={{ color: '#666' }}>Equity:</span> {formatCurrency(p.equity)}</div>
                            </div>
                            <div style={{ marginTop: '0.5rem', display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
                                <span style={{ padding: '0.15rem 0.5rem', background: p.property_type === 'investment' ? '#fff3e0' : p.property_type === 'vacation' ? '#e8f5e9' : '#e3f2fd', color: p.property_type === 'investment' ? '#e65100' : p.property_type === 'vacation' ? '#2e7d32' : '#1565c0', borderRadius: '4px', fontSize: '0.75rem' }}>
                                    {p.property_type === 'primary_residence' ? 'Primary' : p.property_type === 'investment' ? 'Investment' : 'Vacation'}
                                </span>
                                {p.use_computed_balance && (
                                    <span style={{ padding: '0.15rem 0.5rem', background: '#e3f2fd', color: '#1565c0', borderRadius: '4px', fontSize: '0.75rem' }}>Computed Balance</span>
                                )}
                            </div>
                        </Link>
                        {canWrite && (
                            <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
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
