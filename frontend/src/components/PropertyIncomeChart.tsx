import { useEffect, useState } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend,
    ReferenceLine, CartesianGrid,
} from 'recharts';
import { getCashFlowDetail, getDepreciationSchedule, getProperty } from '../api/properties';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import type { MonthlyCashFlowDetailEntry, DepreciationScheduleResponse, Property } from '../types/property';
import type { RechartsTooltipProps } from '../types/recharts';

const CATEGORY_CONFIG: Record<string, { label: string; color: string }> = {
    mortgage: { label: 'Mortgage', color: '#1976d2' },
    tax: { label: 'Tax', color: '#ed6c02' },
    insurance: { label: 'Insurance', color: '#9c27b0' },
    maintenance: { label: 'Maintenance', color: '#0097a7' },
    hoa: { label: 'HOA', color: '#795548' },
    capex: { label: 'CapEx', color: '#3f51b5' },
    mgmt_fee: { label: 'Mgmt Fee', color: '#607d8b' },
};

const DEPRECIATION_COLOR = '#f44336';

type Horizon = 'trailing' | '5yr' | '10yr' | '15yr' | '20yr';
const HORIZON_OPTIONS: { value: Horizon; label: string }[] = [
    { value: 'trailing', label: 'Trailing 12 Mo' },
    { value: '5yr', label: '5 Year' },
    { value: '10yr', label: '10 Year' },
    { value: '15yr', label: '15 Year' },
    { value: '20yr', label: '20 Year' },
];

function horizonYears(h: Horizon): number {
    switch (h) {
        case '5yr': return 5;
        case '10yr': return 10;
        case '15yr': return 15;
        case '20yr': return 20;
        default: return 0;
    }
}

interface PropertyIncomeChartProps {
    propertyId: string;
    propertyAddress: string;
    monthlyRentEstimate: number;
    annualRent?: number;
    inflationRate?: number;
}

// --- Trailing 12-month chart data ---

interface TrailingRow {
    label: string;
    income: number;
    net: number;
    [category: string]: number | string;
}

function buildTrailingData(entries: MonthlyCashFlowDetailEntry[], monthlyRent: number): TrailingRow[] {
    return entries.map(entry => {
        const totalExpenses = Object.values(entry.expenses_by_category)
            .reduce((sum, amt) => sum + amt, 0);
        const row: TrailingRow = {
            month: entry.month,
            label: formatMonthLabel(entry.month),
            income: monthlyRent,
            net: monthlyRent - totalExpenses,
        };
        for (const [cat, amount] of Object.entries(entry.expenses_by_category)) {
            row[cat] = -amount;
        }
        return row;
    });
}

function formatMonthLabel(month: string): string {
    const [year, m] = month.split('-');
    const names = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return `${names[parseInt(m) - 1]} '${year.slice(2)}`;
}

function collectCategories(entries: MonthlyCashFlowDetailEntry[]): string[] {
    const cats = new Set<string>();
    for (const entry of entries) {
        for (const cat of Object.keys(entry.expenses_by_category)) {
            cats.add(cat);
        }
    }
    const order = Object.keys(CATEGORY_CONFIG);
    return [...cats].sort((a, b) => order.indexOf(a) - order.indexOf(b));
}

// --- Forward projection chart data ---

interface ForwardRow {
    label: string;
    year: number;
    income: number;
    expenses: number;
    depreciation: number;
    netCash: number;
    netTaxable: number;
}

function buildForwardData(
    years: number,
    annualRent: number,
    inflationRate: number,
    property: Property | null,
    depSchedule: DepreciationScheduleResponse | null,
): ForwardRow[] {
    const currentYear = new Date().getFullYear();
    const rows: ForwardRow[] = [];

    // Compute annual expenses from property data
    const baseExpenses = (property?.annual_property_tax ?? 0)
        + (property?.annual_insurance_cost ?? 0)
        + (property?.annual_maintenance_cost ?? 0);

    for (let i = 0; i < years; i++) {
        const year = currentYear + i;
        const inflationFactor = Math.pow(1 + inflationRate, i);
        const income = annualRent * inflationFactor;
        const expenses = baseExpenses * inflationFactor;

        // Look up depreciation for this year from the schedule
        let depreciation = 0;
        if (depSchedule?.schedule) {
            const entry = depSchedule.schedule.find(s => s.tax_year === year);
            if (entry) {
                depreciation = entry.annual_depreciation;
            }
        }

        const netCash = income - expenses;
        const netTaxable = income - expenses - depreciation;

        rows.push({
            label: String(year),
            year,
            income,
            expenses: -expenses,
            depreciation: depreciation > 0 ? -depreciation : 0,
            netCash,
            netTaxable,
        });
    }

    return rows;
}

// --- Tooltips ---

function TrailingTooltip({ active, payload, label }: RechartsTooltipProps) {
    if (!active || !payload) return null;

    const income = payload.find(p => p.name === 'Rent Estimate');
    const netEntry = payload.find(p => p.name === 'Net Cash Flow');
    const expenses = payload.filter(p => p.name !== 'Rent Estimate' && p.name !== 'Net Cash Flow');
    const totalExpenses = expenses.reduce((sum, p) => sum + Math.abs(p.value ?? 0), 0);
    const net = netEntry?.value ?? ((income?.value ?? 0) - totalExpenses);

    return (
        <div style={{ background: '#fff', border: '1px solid #ddd', borderRadius: 6, padding: '0.75rem', fontSize: '0.85rem' }}>
            <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>{label}</div>
            {income && (
                <div style={{ color: '#2e7d32', marginBottom: '0.25rem' }}>
                    Rent Estimate: {formatCurrency(income.value ?? 0)}
                </div>
            )}
            {expenses.map(p => (
                <div key={p.name} style={{ color: p.color, marginBottom: '0.15rem' }}>
                    {p.name}: {formatCurrency(Math.abs(p.value ?? 0))}
                </div>
            ))}
            {expenses.length > 0 && (
                <>
                    <div style={{ borderTop: '1px solid #eee', marginTop: '0.25rem', paddingTop: '0.25rem', color: '#d32f2f' }}>
                        Total Expenses: {formatCurrency(totalExpenses)}
                    </div>
                    <div style={{ fontWeight: 600, color: net >= 0 ? '#2e7d32' : '#d32f2f' }}>
                        Net: {formatCurrency(net)}
                    </div>
                </>
            )}
        </div>
    );
}

function ForwardTooltip({ active, payload, label }: RechartsTooltipProps<ForwardRow>) {
    if (!active || !payload) return null;

    const row = payload[0]?.payload;
    if (!row) return null;

    return (
        <div style={{ background: '#fff', border: '1px solid #ddd', borderRadius: 6, padding: '0.75rem', fontSize: '0.85rem', minWidth: 200 }}>
            <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>{label}</div>
            <div style={{ color: '#2e7d32', marginBottom: '0.15rem' }}>
                Rental Income: {formatCurrency(row.income)}
            </div>
            <div style={{ color: '#d32f2f', marginBottom: '0.15rem' }}>
                Operating Expenses: {formatCurrency(Math.abs(row.expenses))}
            </div>
            {row.depreciation < 0 && (
                <div style={{ color: DEPRECIATION_COLOR, marginBottom: '0.15rem' }}>
                    Depreciation: {formatCurrency(Math.abs(row.depreciation))}
                </div>
            )}
            <div style={{ borderTop: '1px solid #eee', marginTop: '0.35rem', paddingTop: '0.35rem' }}>
                <div style={{ fontWeight: 600, color: row.netCash >= 0 ? '#2e7d32' : '#d32f2f' }}>
                    Net Cash Flow: {formatCurrency(row.netCash)}
                </div>
                <div style={{ fontWeight: 600, color: row.netTaxable >= 0 ? '#e65100' : '#2e7d32', fontSize: '0.8rem' }}>
                    Taxable Income: {formatCurrency(row.netTaxable)}
                    {row.netTaxable < 0 && <span style={{ color: '#2e7d32', fontWeight: 400 }}> (tax loss)</span>}
                </div>
            </div>
        </div>
    );
}

// --- Main component ---

export default function PropertyIncomeChart({
    propertyId,
    propertyAddress,
    monthlyRentEstimate,
    annualRent,
    inflationRate = 0,
}: PropertyIncomeChartProps) {
    const [trailingData, setTrailingData] = useState<MonthlyCashFlowDetailEntry[] | null>(null);
    const [depSchedule, setDepSchedule] = useState<DepreciationScheduleResponse | null>(null);
    const [property, setProperty] = useState<Property | null>(null);
    const [loading, setLoading] = useState(true);
    const [horizon, setHorizon] = useState<Horizon>('trailing');

    const effectiveAnnualRent = annualRent ?? monthlyRentEstimate * 12;

    useEffect(() => {
        const now = new Date();
        const from = `${now.getFullYear() - 1}-${String(now.getMonth() + 2).padStart(2, '0')}`;
        const to = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;

        Promise.all([
            getCashFlowDetail(propertyId, from, to).catch(() => null),
            getDepreciationSchedule(propertyId).catch(() => null),
            getProperty(propertyId).catch(() => null),
        ]).then(([cashFlow, dep, prop]) => {
            setTrailingData(cashFlow);
            setDepSchedule(dep);
            setProperty(prop);
        }).finally(() => setLoading(false));
    }, [propertyId]);

    if (loading) {
        return <div style={{ ...cardStyle, textAlign: 'center', padding: '1.5rem', color: '#999' }}>Loading property data...</div>;
    }

    const hasDepreciation = depSchedule?.schedule && depSchedule.schedule.length > 0;
    const showTrailing = horizon === 'trailing';

    return (
        <div style={{ ...cardStyle, marginTop: '0.75rem' }}>
            {/* Header with horizon tabs */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem', flexWrap: 'wrap', gap: '0.5rem' }}>
                <h4 style={{ margin: 0, fontSize: '0.95rem' }}>
                    {showTrailing ? 'Rent vs Expenses' : 'Projected Cash Flow & Depreciation'} — {propertyAddress}
                </h4>
                <div style={{ display: 'flex', gap: 0, borderRadius: 6, overflow: 'hidden', border: '1px solid #ddd' }}>
                    {HORIZON_OPTIONS.map(opt => (
                        <button
                            key={opt.value}
                            onClick={() => setHorizon(opt.value)}
                            style={{
                                padding: '0.3rem 0.6rem',
                                fontSize: '0.75rem',
                                border: 'none',
                                borderRight: '1px solid #ddd',
                                background: horizon === opt.value ? '#1976d2' : '#fff',
                                color: horizon === opt.value ? '#fff' : '#555',
                                cursor: 'pointer',
                                fontWeight: horizon === opt.value ? 600 : 400,
                            }}
                        >
                            {opt.label}
                        </button>
                    ))}
                </div>
            </div>

            {showTrailing ? (
                <TrailingView
                    data={trailingData}
                    monthlyRent={monthlyRentEstimate}
                />
            ) : (
                <ForwardView
                    years={horizonYears(horizon)}
                    annualRent={effectiveAnnualRent}
                    inflationRate={inflationRate}
                    property={property}
                    depSchedule={depSchedule}
                    hasDepreciation={!!hasDepreciation}
                />
            )}
        </div>
    );
}

// --- Trailing 12-month sub-view ---

function TrailingView({ data, monthlyRent }: { data: MonthlyCashFlowDetailEntry[] | null; monthlyRent: number }) {
    if (!data || data.length === 0) {
        return (
            <div style={{ textAlign: 'center', padding: '1.5rem', color: '#999', fontSize: '0.9rem' }}>
                No income or expense data logged on this property yet.
                <br />
                <span style={{ fontSize: '0.8rem' }}>Add records on the <a href="/properties" style={{ color: '#1976d2' }}>property detail page</a> to see the breakdown here.</span>
            </div>
        );
    }

    const categories = collectCategories(data);
    const chartData = buildTrailingData(data, monthlyRent);

    const maxExpense = Math.max(...data.map(d => d.total_expenses));
    const minNet = Math.min(...chartData.map(d => d.net as number));
    const yMin = -Math.ceil(Math.max(maxExpense, Math.abs(Math.min(minNet, 0))) / 500) * 500 - 500;
    const yMax = Math.ceil(monthlyRent / 500) * 500 + 500;

    const annualIncome = chartData.reduce((sum, d) => sum + d.income, 0);
    const annualExpenses = data.reduce((sum, d) => sum + d.total_expenses, 0);
    const annualNet = annualIncome - annualExpenses;

    return (
        <>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0.75rem', fontSize: '0.9rem', marginBottom: '0.75rem' }}>
                <div>
                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Total Income</div>
                    <div style={{ color: '#2e7d32', fontWeight: 600 }}>{formatCurrency(annualIncome)}</div>
                </div>
                <div>
                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Total Expenses</div>
                    <div style={{ color: '#d32f2f', fontWeight: 600 }}>{formatCurrency(annualExpenses)}</div>
                </div>
                <div>
                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Net Cash Flow</div>
                    <div style={{ color: annualNet >= 0 ? '#2e7d32' : '#d32f2f', fontWeight: 700, fontSize: '1rem' }}>
                        {annualNet >= 0 ? '+' : ''}{formatCurrency(annualNet)}
                    </div>
                </div>
            </div>
            <ResponsiveContainer width="100%" height={300}>
                <BarChart data={chartData} stackOffset="sign" margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="label" tick={{ fontSize: 11 }} />
                    <YAxis
                        domain={[yMin, yMax]}
                        tick={{ fontSize: 11 }}
                        tickFormatter={(v: number) => `$${Math.abs(v / 1000).toFixed(1)}k`}
                    />
                    <Tooltip content={<TrailingTooltip />} />
                    <Legend />
                    <ReferenceLine y={0} stroke="#999" strokeWidth={1} />
                    <Bar dataKey="income" name="Rent Estimate" fill="#2e7d32" stackId="pos" />
                    {categories.map(cat => (
                        <Bar
                            key={cat}
                            dataKey={cat}
                            name={CATEGORY_CONFIG[cat]?.label ?? cat}
                            fill={CATEGORY_CONFIG[cat]?.color ?? '#999'}
                            stackId="neg"
                        />
                    ))}
                    <Bar dataKey="net" name="Net Cash Flow" fill="#ff9800" />
                </BarChart>
            </ResponsiveContainer>
        </>
    );
}

// --- Forward projection sub-view ---

function ForwardView({
    years,
    annualRent,
    inflationRate,
    property,
    depSchedule,
    hasDepreciation,
}: {
    years: number;
    annualRent: number;
    inflationRate: number;
    property: Property | null;
    depSchedule: DepreciationScheduleResponse | null;
    hasDepreciation: boolean;
}) {
    const data = buildForwardData(years, annualRent, inflationRate, property, depSchedule);

    // Summary stats
    const totalIncome = data.reduce((s, d) => s + d.income, 0);
    const totalExpenses = data.reduce((s, d) => s + Math.abs(d.expenses), 0);
    const totalDepreciation = data.reduce((s, d) => s + Math.abs(d.depreciation), 0);
    const totalNetCash = data.reduce((s, d) => s + d.netCash, 0);

    // Y-axis scaling
    const maxIncome = Math.max(...data.map(d => d.income));
    const maxNeg = Math.max(...data.map(d => Math.abs(d.expenses) + Math.abs(d.depreciation)));
    const yMax = Math.ceil(maxIncome / 10000) * 10000 + 10000;
    const yMin = -Math.ceil(maxNeg / 10000) * 10000 - 10000;

    return (
        <>
            <div style={{ display: 'grid', gridTemplateColumns: hasDepreciation ? '1fr 1fr 1fr 1fr' : '1fr 1fr 1fr', gap: '0.75rem', fontSize: '0.9rem', marginBottom: '0.75rem' }}>
                <div>
                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>{years}yr Total Income</div>
                    <div style={{ color: '#2e7d32', fontWeight: 600 }}>{formatCurrency(totalIncome)}</div>
                </div>
                <div>
                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>{years}yr Total Expenses</div>
                    <div style={{ color: '#d32f2f', fontWeight: 600 }}>{formatCurrency(totalExpenses)}</div>
                </div>
                {hasDepreciation && (
                    <div>
                        <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>{years}yr Depreciation</div>
                        <div style={{ color: DEPRECIATION_COLOR, fontWeight: 600 }}>{formatCurrency(totalDepreciation)}</div>
                    </div>
                )}
                <div>
                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>{years}yr Net Cash Flow</div>
                    <div style={{ color: totalNetCash >= 0 ? '#2e7d32' : '#d32f2f', fontWeight: 700, fontSize: '1rem' }}>
                        {totalNetCash >= 0 ? '+' : ''}{formatCurrency(totalNetCash)}
                    </div>
                </div>
            </div>

            <ResponsiveContainer width="100%" height={340}>
                <BarChart data={data} stackOffset="sign" margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="label" tick={{ fontSize: 11 }} interval={years > 10 ? 1 : 0} />
                    <YAxis
                        domain={[yMin, yMax]}
                        tick={{ fontSize: 11 }}
                        tickFormatter={(v: number) => `$${Math.abs(v / 1000).toFixed(0)}k`}
                    />
                    <Tooltip content={<ForwardTooltip />} />
                    <Legend />
                    <ReferenceLine y={0} stroke="#999" strokeWidth={1} />
                    <Bar dataKey="income" name="Rental Income" fill="#2e7d32" stackId="pos" />
                    <Bar dataKey="expenses" name="Operating Expenses" fill="#d32f2f" stackId="neg" />
                    {hasDepreciation && (
                        <Bar dataKey="depreciation" name="Depreciation" fill={DEPRECIATION_COLOR} stackId="neg" fillOpacity={0.5} />
                    )}
                    <Bar dataKey="netCash" name="Net Cash Flow" fill="#ff9800" />
                </BarChart>
            </ResponsiveContainer>

            {hasDepreciation && (
                <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.5rem', fontStyle: 'italic' }}>
                    Depreciation is a non-cash tax deduction that reduces taxable income but does not affect cash flow.
                    Years where depreciation exceeds net income create a tax loss that can shield other income from taxes.
                </div>
            )}
        </>
    );
}
