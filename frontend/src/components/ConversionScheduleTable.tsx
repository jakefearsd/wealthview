import type { ConversionYearDetail } from '../types/projection';
import { tableStyle, thStyle as baseThStyle, tdStyle as baseTdStyle, trHoverStyle } from '../utils/styles';

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

const localThStyle: React.CSSProperties = {
    ...baseThStyle,
    textAlign: 'right',
    whiteSpace: 'nowrap',
};

const localTdStyle: React.CSSProperties = {
    ...baseTdStyle,
    textAlign: 'right',
};

export default function ConversionScheduleTable({ years }: Props) {
    if (years.length === 0) {
        return <p style={{ color: '#888' }}>No conversion schedule data available.</p>;
    }

    return (
        <div style={{ overflowX: 'auto' }}>
            <table style={tableStyle}>
                <thead>
                    <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                        <th style={{ ...localThStyle, textAlign: 'left' }}>Age</th>
                        <th style={{ ...localThStyle, textAlign: 'left' }}>Year</th>
                        <th style={localThStyle}>Conversion</th>
                        <th style={localThStyle}>Est. Tax</th>
                        <th style={localThStyle}>Traditional</th>
                        <th style={localThStyle}>Roth</th>
                        <th style={localThStyle}>RMD</th>
                        <th style={localThStyle}>Other Income</th>
                        <th style={localThStyle}>Taxable Income</th>
                        <th style={{ ...localThStyle, textAlign: 'left' }}>Bracket</th>
                    </tr>
                </thead>
                <tbody>
                    {years.map(y => (
                        <tr key={y.calendar_year} style={{
                            ...trHoverStyle,
                            background: y.conversion_amount > 0 ? '#f3f8ff' : undefined,
                        }}>
                            <td style={{ ...localTdStyle, textAlign: 'left' }}>{y.age}</td>
                            <td style={{ ...localTdStyle, textAlign: 'left', color: '#666' }}>{y.calendar_year}</td>
                            <td style={{
                                ...localTdStyle,
                                fontWeight: y.conversion_amount > 0 ? 600 : 400,
                                color: y.conversion_amount > 0 ? '#1976d2' : '#888',
                            }}>
                                {y.conversion_amount > 0 ? fmt(y.conversion_amount) : '--'}
                            </td>
                            <td style={{ ...localTdStyle, color: y.estimated_tax > 0 ? '#d32f2f' : '#888' }}>
                                {y.estimated_tax > 0 ? fmt(y.estimated_tax) : '--'}
                            </td>
                            <td style={localTdStyle}>{fmtShort(y.traditional_balance_after)}</td>
                            <td style={localTdStyle}>{fmtShort(y.roth_balance_after)}</td>
                            <td style={{ ...localTdStyle, color: '#888' }}>
                                {y.projected_rmd > 0 ? fmt(y.projected_rmd) : '--'}
                            </td>
                            <td style={{ ...localTdStyle, color: '#888' }}>
                                {y.other_income > 0 ? fmt(y.other_income) : '--'}
                            </td>
                            <td style={localTdStyle}>{fmt(y.total_taxable_income)}</td>
                            <td style={{ ...localTdStyle, textAlign: 'left', color: '#666' }}>{y.bracket_used}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
