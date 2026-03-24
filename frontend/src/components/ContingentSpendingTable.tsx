import type { GuardrailYearlySpending } from '../types/projection';
import { formatDollarTooltip } from '../utils/chartFormatters';

interface Props {
    yearlySpending: GuardrailYearlySpending[];
}

// Milestone ages to show in the table (approximately every 2-3 years covering key milestones)
const MILESTONE_AGES = new Set([55, 57, 60, 63, 65, 68, 70, 73, 75, 78, 80, 83, 85, 88, 90, 95, 100]);

function shouldShowRow(age: number): boolean {
    // Always show if it's a milestone age, or if no milestone ages are present in the data,
    // fall back to showing every 2nd year.
    return MILESTONE_AGES.has(age);
}

function fmt(value: number | null | undefined): string {
    if (value == null) return '—';
    return formatDollarTooltip(value);
}

export default function ContingentSpendingTable({ yearlySpending }: Props) {
    // Filter to only rows where at least one contingent value is non-null
    const allWithContingent = yearlySpending.filter(
        y => y.contingent_spending_p25 != null || y.contingent_spending_median != null || y.contingent_spending_p55 != null,
    );

    // Try milestone-based filtering first; fall back to every-2nd-year if too few rows
    let rows = allWithContingent.filter(y => shouldShowRow(y.age));
    if (rows.length < 3 && allWithContingent.length > 0) {
        // Fall back: show every other row
        rows = allWithContingent.filter((_, i) => i % 2 === 0);
        // Ensure we always include at least the first and last
        if (allWithContingent.length > 0 && rows[rows.length - 1] !== allWithContingent[allWithContingent.length - 1]) {
            rows = [...rows, allWithContingent[allWithContingent.length - 1]];
        }
    }

    if (rows.length === 0) {
        return null;
    }

    const thStyle: React.CSSProperties = {
        padding: '0.5rem',
        fontSize: '0.8rem',
        fontWeight: 600,
        color: '#555',
        borderBottom: '2px solid #e0e0e0',
        whiteSpace: 'nowrap',
    };

    const thGroupStyle: React.CSSProperties = {
        ...thStyle,
        textAlign: 'center',
        borderBottom: '1px solid #e0e0e0',
    };

    const tdBase: React.CSSProperties = {
        padding: '0.4rem 0.5rem',
        fontSize: '0.85rem',
        textAlign: 'right',
        whiteSpace: 'nowrap',
    };

    return (
        <div>
            <p style={{ fontSize: '0.875rem', color: '#444', lineHeight: 1.6, marginBottom: '1rem', marginTop: 0 }}>
                Your spending should adapt to your actual portfolio performance. This table shows sustainable spending
                at three portfolio levels — find the column closest to your actual balance each year for your
                recommended spending.
            </p>
            <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                    <thead>
                        <tr>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'left', verticalAlign: 'bottom' }}>Age</th>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'left', verticalAlign: 'bottom' }}>Year</th>
                            <th colSpan={2} style={{ ...thGroupStyle, background: '#fff3f3', color: '#c62828' }}>P25 Portfolio (Conservative)</th>
                            <th colSpan={2} style={{ ...thGroupStyle, background: '#e3f2fd', color: '#1565c0' }}>Median Portfolio (Moderate)</th>
                            <th colSpan={2} style={{ ...thGroupStyle, background: '#e8f5e9', color: '#2e7d32' }}>P55 Portfolio (Comfortable)</th>
                        </tr>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ ...thStyle, textAlign: 'right', fontSize: '0.75rem', color: '#888', background: '#fff8f8' }}>Balance</th>
                            <th style={{ ...thStyle, textAlign: 'right', fontSize: '0.75rem', color: '#888', background: '#fff3f3' }}>Spending</th>
                            <th style={{ ...thStyle, textAlign: 'right', fontSize: '0.75rem', color: '#888', background: '#f5f9ff' }}>Balance</th>
                            <th style={{ ...thStyle, textAlign: 'right', fontSize: '0.75rem', color: '#888', background: '#e3f2fd' }}>Spending</th>
                            <th style={{ ...thStyle, textAlign: 'right', fontSize: '0.75rem', color: '#888', background: '#f4faf5' }}>Balance</th>
                            <th style={{ ...thStyle, textAlign: 'right', fontSize: '0.75rem', color: '#888', background: '#e8f5e9' }}>Spending</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((y, i) => {
                            const rowBg = i % 2 === 0 ? '#fff' : '#fafafa';
                            return (
                                <tr key={y.year} style={{ borderBottom: '1px solid #eee', background: rowBg }}>
                                    <td style={{ ...tdBase, textAlign: 'left', fontWeight: 600 }}>{y.age}</td>
                                    <td style={{ ...tdBase, textAlign: 'left', color: '#666' }}>{y.year}</td>
                                    <td style={{ ...tdBase, color: '#888' }}>{fmt(y.portfolio_balance_p25)}</td>
                                    <td style={{ ...tdBase, fontWeight: 600, color: '#c62828', background: '#fff8f8' }}>
                                        {fmt(y.contingent_spending_p25)}
                                    </td>
                                    <td style={{ ...tdBase, color: '#888' }}>{fmt(y.portfolio_balance_median)}</td>
                                    <td style={{ ...tdBase, fontWeight: 600, color: '#1565c0', background: '#f0f7ff' }}>
                                        {fmt(y.contingent_spending_median)}
                                    </td>
                                    <td style={{ ...tdBase, color: '#888' }}>{fmt(y.portfolio_balance_p55)}</td>
                                    <td style={{ ...tdBase, fontWeight: 600, color: '#2e7d32', background: '#f1faf2' }}>
                                        {fmt(y.contingent_spending_p55)}
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
