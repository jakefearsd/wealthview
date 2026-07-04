import { formatCurrency } from '../utils/format';
import { tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import type { TaxShieldSummary } from '../utils/projectionCalcs';

interface TaxShieldTabProps {
    summary: TaxShieldSummary;
}

/**
 * Depreciation tax shield tab for a projection run: headline totals plus a
 * per-property breakdown of depreciation and losses applied to income.
 */
export default function TaxShieldTab({ summary }: TaxShieldTabProps) {
    return (
        <div style={{ padding: '1rem' }}>
            <h3 style={{ marginBottom: '0.25rem' }}>Depreciation Tax Shield Summary</h3>
            <p style={{ fontSize: '0.85rem', color: '#888', marginBottom: '1rem' }}>
                Values marked (approx.) are estimates based on effective tax rates.
            </p>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem' }}>
                <div style={{ padding: '1rem', background: '#f5f5f5', borderRadius: 8 }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Total Depreciation Taken</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(summary.totalDepreciation)}</div>
                </div>
                <div style={{ padding: '1rem', background: '#f5f5f5', borderRadius: 8 }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Total Loss Applied to Income</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(summary.totalLossApplied)}</div>
                </div>
                <div style={{ padding: '1rem', background: '#e8f5e9', borderRadius: 8 }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Estimated Tax Savings (approx.)</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#2e7d32' }}>{formatCurrency(summary.estimatedTaxSavings)}</div>
                </div>
                <div style={{ padding: '1rem', background: '#e3f2fd', borderRadius: 8 }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Roth Conversion Sheltered (approx.)</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#1565c0' }}>{formatCurrency(summary.rothConversionSheltered)}</div>
                </div>
                <div style={{ padding: '1rem', background: '#fff3e0', borderRadius: 8 }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Suspended Losses Remaining</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(summary.suspendedLossRemaining)}</div>
                </div>
            </div>

            {summary.perProperty.length > 0 && (
                <div style={{ marginTop: '1.5rem' }}>
                    <h4 style={{ marginBottom: '0.5rem' }}>Per-Property Breakdown</h4>
                    <table style={tableStyle}>
                        <thead>
                            <tr style={{ background: '#fafafa' }}>
                                <th style={thStyle}>Property</th>
                                <th style={thStyle}>Classification</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Total Depreciation</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Total Loss Applied</th>
                            </tr>
                        </thead>
                        <tbody>
                            {summary.perProperty.map((p, i) => (
                                <tr key={i} style={trHoverStyle}>
                                    <td style={tdStyle}>{p.name}</td>
                                    <td style={tdStyle}>
                                        <span style={{
                                            fontSize: '0.75rem', padding: '2px 6px', borderRadius: 4,
                                            background: p.taxTreatment === 'rental_passive' ? '#e0e0e0'
                                                : p.taxTreatment === 'rental_active_reps' ? '#c8e6c9' : '#bbdefb',
                                            color: '#333',
                                        }}>
                                            {p.taxTreatment === 'rental_passive' ? 'Passive'
                                                : p.taxTreatment === 'rental_active_reps' ? 'REPS' : 'STR'}
                                        </span>
                                    </td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(p.depreciation)}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(p.lossApplied)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
