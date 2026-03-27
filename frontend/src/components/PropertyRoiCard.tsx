import { useState, useEffect, useCallback } from 'react';
import { getRoiAnalysis } from '../api/properties';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import type { RoiAnalysisResponse } from '../types/property';
import type { IncomeSource } from '../types/projection';

interface PropertyRoiCardProps {
    propertyId: string;
    incomeSource: IncomeSource;
}

const YEAR_OPTIONS = [5, 10, 15, 20];

export default function PropertyRoiCard({ propertyId, incomeSource }: PropertyRoiCardProps) {
    const [years, setYears] = useState(10);
    const [investmentReturn, setInvestmentReturn] = useState('7');
    const [rentGrowth, setRentGrowth] = useState('3');
    const [expenseInflation, setExpenseInflation] = useState('3');
    const [analysis, setAnalysis] = useState<RoiAnalysisResponse | null>(null);
    const [loading, setLoading] = useState(false);

    const fetchAnalysis = useCallback(() => {
        const ir = parseFloat(investmentReturn);
        const rg = parseFloat(rentGrowth);
        const ei = parseFloat(expenseInflation);
        if (isNaN(ir) || isNaN(rg) || isNaN(ei)) return;

        setLoading(true);
        getRoiAnalysis(propertyId, incomeSource.id, {
            years,
            investmentReturn: ir / 100,
            rentGrowth: rg / 100,
            expenseInflation: ei / 100,
        })
            .then(setAnalysis)
            .catch(() => setAnalysis(null))
            .finally(() => setLoading(false));
    }, [propertyId, incomeSource.id, years, investmentReturn, rentGrowth, expenseInflation]);

    useEffect(() => {
        const timer = setTimeout(fetchAnalysis, 400);
        return () => clearTimeout(timer);
    }, [fetchAnalysis]);

    const inputStyle = {
        padding: '0.3rem 0.5rem',
        border: '1px solid #ccc',
        borderRadius: '4px',
        fontSize: '0.85rem',
        width: '70px',
    };

    const labelStyle = {
        fontSize: '0.75rem',
        color: '#666',
        marginBottom: '0.2rem',
    };

    const columnStyle = {
        flex: 1,
        padding: '1rem',
        background: '#f9f9f9',
        borderRadius: '8px',
    };

    const metricStyle = {
        display: 'flex',
        justifyContent: 'space-between',
        padding: '0.3rem 0',
        fontSize: '0.9rem',
    };

    return (
        <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                <div>
                    <h4 style={{ margin: 0, fontSize: '1rem' }}>{incomeSource.name}</h4>
                    <div style={{ fontSize: '0.85rem', color: '#666' }}>
                        {formatCurrency(incomeSource.annual_amount)}/year ({formatCurrency(incomeSource.annual_amount / 12)}/month)
                    </div>
                </div>
                {loading && <span style={{ fontSize: '0.8rem', color: '#999' }}>Calculating...</span>}
            </div>

            <div style={{ display: 'flex', gap: '1.5rem', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
                <div>
                    <div style={labelStyle}>Period</div>
                    <select value={years} onChange={e => setYears(Number(e.target.value))} style={{ ...inputStyle, width: '80px' }}>
                        {YEAR_OPTIONS.map(y => <option key={y} value={y}>{y} years</option>)}
                    </select>
                </div>
                <div>
                    <div style={labelStyle}>Investment Return %</div>
                    <input type="number" value={investmentReturn} onChange={e => setInvestmentReturn(e.target.value)} style={inputStyle} step="0.5" min="0" max="20" />
                </div>
                <div>
                    <div style={labelStyle}>Rent Growth %</div>
                    <input type="number" value={rentGrowth} onChange={e => setRentGrowth(e.target.value)} style={inputStyle} step="0.5" min="0" max="10" />
                </div>
                <div>
                    <div style={labelStyle}>Expense Inflation %</div>
                    <input type="number" value={expenseInflation} onChange={e => setExpenseInflation(e.target.value)} style={inputStyle} step="0.5" min="0" max="10" />
                </div>
            </div>

            {analysis && (
                <>
                    <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem' }}>
                        <div style={columnStyle}>
                            <h5 style={{ margin: '0 0 0.75rem', color: '#1565c0' }}>Hold & Rent</h5>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Property Value</span>
                                <span>{formatCurrency(analysis.hold.ending_property_value)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Mortgage Balance</span>
                                <span>{formatCurrency(analysis.hold.ending_mortgage_balance)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Cumulative Cash Flow</span>
                                <span style={{ color: analysis.hold.cumulative_net_cash_flow >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                    {formatCurrency(analysis.hold.cumulative_net_cash_flow)}
                                </span>
                            </div>
                            <div style={{ ...metricStyle, borderTop: '2px solid #ddd', paddingTop: '0.5rem', marginTop: '0.3rem', fontWeight: 700 }}>
                                <span>Net Worth</span>
                                <span>{formatCurrency(analysis.hold.ending_net_worth)}</span>
                            </div>
                        </div>

                        <div style={columnStyle}>
                            <h5 style={{ margin: '0 0 0.75rem', color: '#e65100' }}>Sell & Invest</h5>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Gross Proceeds</span>
                                <span>{formatCurrency(analysis.sell.gross_proceeds)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Selling Costs (6%)</span>
                                <span style={{ color: '#d32f2f' }}>-{formatCurrency(analysis.sell.selling_costs)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Depreciation Recapture Tax</span>
                                <span style={{ color: '#d32f2f' }}>
                                    {analysis.sell.depreciation_recapture_tax > 0 ? `-${formatCurrency(analysis.sell.depreciation_recapture_tax)}` : '$0'}
                                </span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Capital Gains Tax</span>
                                <span style={{ color: '#d32f2f' }}>-{formatCurrency(analysis.sell.capital_gains_tax)}</span>
                            </div>
                            <div style={metricStyle}>
                                <span style={{ color: '#666' }}>Net Proceeds (Invested)</span>
                                <span>{formatCurrency(analysis.sell.net_proceeds)}</span>
                            </div>
                            <div style={{ ...metricStyle, borderTop: '2px solid #ddd', paddingTop: '0.5rem', marginTop: '0.3rem', fontWeight: 700 }}>
                                <span>Net Worth</span>
                                <span>{formatCurrency(analysis.sell.ending_net_worth)}</span>
                            </div>
                        </div>
                    </div>

                    <div style={{
                        padding: '0.75rem 1rem',
                        borderRadius: '8px',
                        background: analysis.advantage === 'hold' ? '#e8f5e9' : '#fff3e0',
                        color: analysis.advantage === 'hold' ? '#2e7d32' : '#e65100',
                        fontWeight: 700,
                        fontSize: '0.95rem',
                        textAlign: 'center' as const,
                    }}>
                        {analysis.advantage === 'hold' ? 'Holding' : 'Selling'} is better by {formatCurrency(analysis.advantage_amount)} over {analysis.comparison_years} years
                    </div>
                </>
            )}
        </div>
    );
}
