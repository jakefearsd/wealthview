import { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router';
import { getProperty, addPropertyIncome, addPropertyExpense, getCashFlow, getValuationHistory, refreshValuation, selectZpid, getPropertyAnalytics } from '../api/properties';
import type { ZillowSearchResult } from '../types/property';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import PropertyAnalyticsSection from '../components/PropertyAnalyticsSection';
import PropertyValuationSection from '../components/PropertyValuationSection';
import PropertyCashFlowSection from '../components/PropertyCashFlowSection';
import toast from 'react-hot-toast';

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

    const { data: property, refetch: refetchProperty } = useApiQuery(() => getProperty(id!));
    const { data: cashFlow, refetch: refetchCashFlow } = useApiQuery(() => getCashFlow(id!, range.from, range.to));
    const { data: valuations, refetch: refetchValuations } = useApiQuery(() => getValuationHistory(id!));
    const { data: analytics, refetch: refetchAnalytics } = useApiQuery(() => getPropertyAnalytics(id!, analyticsYear));

    async function handleAddIncome(data: { date: string; amount: number; category: string; description?: string; frequency?: string }) {
        try {
            await addPropertyIncome(id!, data);
            toast.success('Income added');
            refetchCashFlow();
        } catch {
            toast.error('Failed to add income');
        }
    }

    async function handleAddExpense(data: { date: string; amount: number; category: string; description?: string; frequency?: string }) {
        try {
            await addPropertyExpense(id!, data);
            toast.success('Expense added');
            refetchCashFlow();
        } catch {
            toast.error('Failed to add expense');
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

            {property && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
                        <h2>{property.address}</h2>
                        <span style={badgeStyle(
                            property.property_type === 'investment' ? '#e65100' : property.property_type === 'vacation' ? '#1b5e20' : '#1565c0',
                            property.property_type === 'investment' ? '#fff3e0' : property.property_type === 'vacation' ? '#e8f5e9' : '#e3f2fd'
                        )}>
                            {property.property_type === 'primary_residence' ? 'Primary Residence' : property.property_type === 'investment' ? 'Investment' : 'Vacation'}
                        </span>
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

            {analytics && (
                <PropertyAnalyticsSection
                    analytics={analytics}
                    analyticsYear={analyticsYear}
                    analyticsYearOptions={analyticsYearOptions}
                    onYearChange={handleAnalyticsYearChange}
                />
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
                onAddIncome={handleAddIncome}
                onAddExpense={handleAddExpense}
            />
        </div>
    );
}
