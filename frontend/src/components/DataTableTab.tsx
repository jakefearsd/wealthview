import { useState } from 'react';
import { formatCurrency } from '../utils/format';
import { tableStyle } from '../utils/styles';
import Button from './Button';
import type { ProjectionYear } from '../types/projection';

interface DataTableTabProps {
    yearlyData: ProjectionYear[];
    hasPoolData: boolean;
    hasSpendingData: boolean;
    hasSurplusReinvested: boolean;
    computeTotalSpending: (y: ProjectionYear) => number | null;
    onDownloadCsv: () => void;
}

const stickyTh: React.CSSProperties = {
    textAlign: 'right',
    padding: '0.5rem',
    position: 'sticky',
    top: 0,
    background: '#fff',
};

export default function DataTableTab({
    yearlyData,
    hasPoolData,
    hasSpendingData,
    hasSurplusReinvested,
    computeTotalSpending,
    onDownloadCsv,
}: DataTableTabProps) {
    const [showPoolDetails, setShowPoolDetails] = useState(false);

    return (
        <>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.5rem', gap: '0.5rem' }}>
                {hasPoolData && (
                    <button
                        onClick={() => setShowPoolDetails(!showPoolDetails)}
                        style={{
                            padding: '0.4rem 0.8rem',
                            background: showPoolDetails ? '#e65100' : '#757575',
                            color: '#fff',
                            border: 'none',
                            borderRadius: '4px',
                            cursor: 'pointer',
                            fontSize: '0.85rem',
                        }}
                    >
                        {showPoolDetails ? 'Hide' : 'Show'} Pool Details
                    </button>
                )}
                <Button onClick={onDownloadCsv} size="sm" style={{ padding: '0.4rem 1rem' }}>
                    Download CSV
                </Button>
            </div>
            <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
                <table style={tableStyle}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'left', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Year</th>
                            <th style={stickyTh}>Age</th>
                            <th style={stickyTh}>Start</th>
                            <th style={stickyTh}>Contributions</th>
                            <th style={stickyTh}>Growth</th>
                            <th style={stickyTh}>Withdrawals</th>
                            <th style={stickyTh}>Income</th>
                            <th style={stickyTh}>Total Spending</th>
                            <th style={stickyTh}>End</th>
                            {hasPoolData && (
                                <>
                                    <th style={stickyTh}>Traditional</th>
                                    <th style={stickyTh}>Roth</th>
                                    <th style={stickyTh}>Taxable</th>
                                    <th style={stickyTh}>Conversion</th>
                                    <th style={stickyTh}>Tax</th>
                                </>
                            )}
                            {showPoolDetails && hasPoolData && (
                                <>
                                    {hasSpendingData && (
                                        <>
                                            <th style={stickyTh}>Essential</th>
                                            <th style={stickyTh}>Discretionary</th>
                                            <th style={stickyTh}>Net Need</th>
                                        </>
                                    )}
                                    <th style={stickyTh}>Trad Growth</th>
                                    <th style={stickyTh}>Roth Growth</th>
                                    <th style={stickyTh}>Taxable Growth</th>
                                    <th style={stickyTh}>Tax from Taxable</th>
                                    <th style={stickyTh}>Tax from Trad</th>
                                    <th style={stickyTh}>Tax from Roth</th>
                                    <th style={stickyTh}>WD from Taxable</th>
                                    <th style={stickyTh}>WD from Trad</th>
                                    <th style={stickyTh}>WD from Roth</th>
                                    {hasSpendingData && (
                                        <>
                                            <th style={stickyTh}>Surplus/Deficit</th>
                                            {hasSurplusReinvested && (
                                                <th style={stickyTh}>Surplus Reinvested</th>
                                            )}
                                        </>
                                    )}
                                    <th style={{ textAlign: 'center', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Status</th>
                                </>
                            )}
                        </tr>
                    </thead>
                    <tbody>
                        {yearlyData.map((y, i) => {
                            const isRetirementTransition = y.retired && i > 0 && !yearlyData[i - 1].retired;
                            return (
                                <tr
                                    key={y.year}
                                    style={{
                                        borderBottom: '1px solid #f0f0f0',
                                        borderTop: isRetirementTransition ? '3px solid #ff9800' : undefined,
                                        background: y.retired ? '#fff8e1' : 'transparent',
                                    }}
                                >
                                    <td style={{ padding: '0.5rem' }}>
                                        {y.year}
                                        {y.irmaa_warning && (
                                            <span title="Income exceeds 22% bracket — review IRMAA implications for Medicare (2-year lookback)"
                                                  style={{ color: '#d32f2f', marginLeft: 4, cursor: 'help', fontSize: '0.9rem' }}>
                                                &#9888;
                                            </span>
                                        )}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.age}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(y.start_balance)}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{y.contributions > 0 ? formatCurrency(y.contributions) : '-'}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: y.growth >= 0 ? '#2e7d32' : '#d32f2f' }}>{formatCurrency(y.growth)}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawals > 0 ? formatCurrency(y.withdrawals) : '-'}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{y.income_streams_total != null ? formatCurrency(y.income_streams_total) : '-'}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{computeTotalSpending(y) != null ? formatCurrency(computeTotalSpending(y)!) : '-'}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right', fontWeight: 600 }}>{formatCurrency(y.end_balance)}</td>
                                    {hasPoolData && (
                                        <>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#e65100' }}>{y.traditional_balance != null ? formatCurrency(y.traditional_balance) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{y.roth_balance != null ? formatCurrency(y.roth_balance) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#1976d2' }}>{y.taxable_balance != null ? formatCurrency(y.taxable_balance) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.roth_conversion_amount ? formatCurrency(y.roth_conversion_amount) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_liability ? formatCurrency(y.tax_liability) : '-'}</td>
                                        </>
                                    )}
                                    {showPoolDetails && hasPoolData && (
                                        <>
                                            {hasSpendingData && (
                                                <>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.essential_expenses != null ? formatCurrency(y.essential_expenses) : '-'}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.discretionary_after_cuts != null ? formatCurrency(y.discretionary_after_cuts) : y.discretionary_expenses != null ? formatCurrency(y.discretionary_expenses) : '-'}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.net_spending_need != null ? formatCurrency(y.net_spending_need) : '-'}</td>
                                                </>
                                            )}
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.traditional_growth != null ? formatCurrency(y.traditional_growth) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.roth_growth != null ? formatCurrency(y.roth_growth) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.taxable_growth != null ? formatCurrency(y.taxable_growth) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_paid_from_taxable != null ? formatCurrency(y.tax_paid_from_taxable) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_paid_from_traditional != null ? formatCurrency(y.tax_paid_from_traditional) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_paid_from_roth != null ? formatCurrency(y.tax_paid_from_roth) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawal_from_taxable != null ? formatCurrency(y.withdrawal_from_taxable) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawal_from_traditional != null ? formatCurrency(y.withdrawal_from_traditional) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawal_from_roth != null ? formatCurrency(y.withdrawal_from_roth) : '-'}</td>
                                            {hasSpendingData && (
                                                <>
                                                    <td style={{
                                                        padding: '0.5rem', textAlign: 'right',
                                                        color: y.spending_surplus != null && Math.abs(y.spending_surplus) >= 1 ? (y.spending_surplus > 0 ? '#2e7d32' : '#d32f2f') : undefined,
                                                        fontWeight: 600,
                                                    }}>
                                                        {y.spending_surplus != null && Math.abs(y.spending_surplus) >= 1 ? formatCurrency(y.spending_surplus) : '-'}
                                                    </td>
                                                    {hasSurplusReinvested && (
                                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>
                                                            {y.surplus_reinvested != null && y.surplus_reinvested > 0 ? formatCurrency(y.surplus_reinvested) : '-'}
                                                        </td>
                                                    )}
                                                </>
                                            )}
                                            <td style={{ padding: '0.5rem', textAlign: 'center' }}>{y.retired ? 'Retired' : 'Working'}</td>
                                        </>
                                    )}
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>
        </>
    );
}
