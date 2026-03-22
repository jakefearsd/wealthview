import type { ConversionYearDetail } from '../types/projection';

interface Props {
    years: ConversionYearDetail[];
}

const fmt = (n: number) =>
    n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

const fmtShort = (n: number) => {
    const abs = Math.abs(n);
    const sign = n < 0 ? '-' : '';
    if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(1)}M`;
    if (abs >= 1_000) return `${sign}$${Math.round(abs / 1_000)}k`;
    return `${sign}$${Math.round(abs)}`;
};

const thStyle: React.CSSProperties = {
    textAlign: 'right',
    padding: '0.5rem',
    whiteSpace: 'nowrap',
};

const tdStyle: React.CSSProperties = {
    padding: '0.4rem 0.5rem',
    textAlign: 'right',
};

export default function ConversionScheduleTable({ years }: Props) {
    if (years.length === 0) {
        return <p style={{ color: '#888' }}>No conversion schedule data available.</p>;
    }

    return (
        <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                <thead>
                    <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                        <th style={{ ...thStyle, textAlign: 'left' }}>Age</th>
                        <th style={{ ...thStyle, textAlign: 'left' }}>Year</th>
                        <th style={thStyle}>Conversion</th>
                        <th style={thStyle}>Est. Tax</th>
                        <th style={thStyle}>Traditional</th>
                        <th style={thStyle}>Roth</th>
                        <th style={thStyle}>RMD</th>
                        <th style={thStyle}>Other Income</th>
                        <th style={thStyle}>Taxable Income</th>
                        <th style={{ ...thStyle, textAlign: 'left' }}>Bracket</th>
                    </tr>
                </thead>
                <tbody>
                    {years.map(y => (
                        <tr key={y.calendar_year} style={{
                            borderBottom: '1px solid #eee',
                            background: y.conversion_amount > 0 ? '#f3f8ff' : undefined,
                        }}>
                            <td style={{ ...tdStyle, textAlign: 'left' }}>{y.age}</td>
                            <td style={{ ...tdStyle, textAlign: 'left', color: '#666' }}>{y.calendar_year}</td>
                            <td style={{
                                ...tdStyle,
                                fontWeight: y.conversion_amount > 0 ? 600 : 400,
                                color: y.conversion_amount > 0 ? '#1976d2' : '#888',
                            }}>
                                {y.conversion_amount > 0 ? fmt(y.conversion_amount) : '--'}
                            </td>
                            <td style={{ ...tdStyle, color: y.estimated_tax > 0 ? '#d32f2f' : '#888' }}>
                                {y.estimated_tax > 0 ? fmt(y.estimated_tax) : '--'}
                            </td>
                            <td style={tdStyle}>{fmtShort(y.traditional_balance_after)}</td>
                            <td style={tdStyle}>{fmtShort(y.roth_balance_after)}</td>
                            <td style={{ ...tdStyle, color: '#888' }}>
                                {y.projected_rmd > 0 ? fmt(y.projected_rmd) : '--'}
                            </td>
                            <td style={{ ...tdStyle, color: '#888' }}>
                                {y.other_income > 0 ? fmt(y.other_income) : '--'}
                            </td>
                            <td style={tdStyle}>{fmt(y.total_taxable_income)}</td>
                            <td style={{ ...tdStyle, textAlign: 'left', color: '#666' }}>{y.bracket_used}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
