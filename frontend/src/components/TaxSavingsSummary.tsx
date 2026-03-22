import type { RothConversionScheduleResponse } from '../types/projection';
import { cardStyle } from '../utils/styles';

interface Props {
    schedule: RothConversionScheduleResponse;
}

const fmt = (n: number) =>
    n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

const pctFmt = (n: number) => `${(n * 100).toFixed(0)}%`;

export default function TaxSavingsSummary({ schedule }: Props) {
    const savingsColor = schedule.tax_savings > 0 ? '#2e7d32' : '#d32f2f';

    return (
        <div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                <div style={{ ...cardStyle, textAlign: 'center' }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>
                        Lifetime Tax With Conversions
                    </div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>
                        {fmt(schedule.lifetime_tax_with_conversions)}
                    </div>
                </div>
                <div style={{ ...cardStyle, textAlign: 'center' }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>
                        Without Conversions
                    </div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>
                        {fmt(schedule.lifetime_tax_without)}
                    </div>
                </div>
                <div style={{ ...cardStyle, textAlign: 'center' }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>
                        Estimated Savings
                    </div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700, color: savingsColor }}>
                        {fmt(schedule.tax_savings)}
                    </div>
                </div>
            </div>

            {!schedule.exhaustion_target_met && (
                <div style={{
                    background: '#fff8e1', border: '1px solid #ffe082', borderRadius: '6px',
                    padding: '0.75rem 1rem', marginBottom: '1rem', fontSize: '0.85rem', color: '#e65100',
                }}>
                    <strong>Warning:</strong> Traditional IRA exhaustion target was not met. The
                    conversion schedule may not fully eliminate RMDs before the target age. Consider
                    increasing the conversion bracket or extending the exhaustion buffer.
                </div>
            )}

            <div style={{ display: 'flex', gap: '1.5rem', flexWrap: 'wrap', fontSize: '0.85rem', color: '#555', marginBottom: '0.5rem' }}>
                <span>
                    <strong style={{ color: '#666' }}>Conversion Bracket:</strong> {pctFmt(schedule.conversion_bracket_rate)}
                </span>
                <span>
                    <strong style={{ color: '#666' }}>RMD Target Bracket:</strong> {pctFmt(schedule.rmd_target_bracket_rate)}
                </span>
                {schedule.target_traditional_balance != null && schedule.target_traditional_balance > 0 && (
                    <span>
                        <strong style={{ color: '#666' }}>Target Trad. at RMD:</strong> {fmt(schedule.target_traditional_balance)}
                    </span>
                )}
                {schedule.rmd_bracket_headroom != null && (
                    <span>
                        <strong style={{ color: '#666' }}>RMD Headroom:</strong> {pctFmt(schedule.rmd_bracket_headroom)}
                    </span>
                )}
                <span>
                    <strong style={{ color: '#666' }}>Exhaustion Age:</strong> {schedule.exhaustion_age}
                </span>
                {schedule.mc_exhaustion_pct !== null && (
                    <span>
                        <strong style={{ color: '#666' }}>MC Exhaustion Rate:</strong> {pctFmt(schedule.mc_exhaustion_pct)}
                    </span>
                )}
            </div>
        </div>
    );
}
