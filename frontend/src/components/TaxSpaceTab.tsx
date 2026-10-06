import React from 'react';
import { formatCurrency } from '../utils/format';
import { tableStyle } from '../utils/styles';
import type { TaxSpaceYear } from '../types/projection';

interface TaxSpaceTabProps {
    taxSpace: TaxSpaceYear[];
}

const DASH = '—';

function pct(rate: number | null): string {
    return rate == null ? DASH : `${(rate * 100).toFixed(1)}%`;
}

function money(value: number | null | undefined): string {
    return value == null ? DASH : formatCurrency(value);
}

function ssZone(rate: number | null): string {
    return rate == null ? DASH : `${Math.round(rate * 100)}¢ per $1`;
}

function irmaa(y: TaxSpaceYear): string {
    if (y.irmaa_premium_year == null || y.irmaa_tier == null) return DASH;
    if (y.irmaa_room_to_next_tier == null) return `Tier ${y.irmaa_tier} (top) · ${y.irmaa_premium_year} premiums`;
    const roomStr = formatCurrency(y.irmaa_room_to_next_tier);
    // This year's income sets the premium two years later, so name that year as the one affected.
    const costStr = y.irmaa_next_tier_annual_cost != null
        ? ` (+${formatCurrency(y.irmaa_next_tier_annual_cost)}/yr on ${y.irmaa_premium_year} premiums)`
        : ` (${y.irmaa_premium_year} premiums)`;
    return `Tier ${y.irmaa_tier} · ${roomStr} to next tier${costStr}`;
}

const EXPLAINERS: [string, string][] = [
    ['MAGI', 'Modified adjusted gross income — the figure ACA credits, IRMAA and the NIIT key off.'],
    ['Bracket / Room to top', 'Your marginal ordinary-income bracket and how much more income fits before the top of the current bracket (named under the amount; below the standard deduction the bracket shows 0% while the room runs to the top of the 10% bracket).'],
    ['0% capital-gains room', 'Long-term gains or qualified dividends you could still realize at a 0% federal rate.'],
    ['SS zone', 'How much extra Social Security becomes taxable for each extra $1 of ordinary income.'],
    ['NIIT headroom', 'Distance to the 3.8% net investment income tax threshold (negative = over it).'],
    ['IRMAA', 'Medicare surcharge tier this year\'s income sets, two years later.'],
    ['Eff. marginal', 'Total tax on the next $1,000 of ordinary income or capital gains, all effects included.'],
];

export default function TaxSpaceTab({ taxSpace }: TaxSpaceTabProps) {
    if (taxSpace.length === 0) {
        return <p style={{ color: '#666' }}>No retirement years in this projection.</p>;
    }

    const th: React.CSSProperties = {
        textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff',
    };
    const td: React.CSSProperties = { textAlign: 'right', padding: '0.5rem' };

    return (
        <div>
            <h4 style={{ marginBottom: '0.5rem' }}>Tax Space by Year</h4>
            <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
                <table style={tableStyle}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ ...th, textAlign: 'left' }}>Year</th>
                            <th style={th}>Age</th>
                            <th style={th}>MAGI</th>
                            <th style={th}>Bracket</th>
                            <th style={th}>Room to Top</th>
                            <th style={th}>0% Cap-Gains Room</th>
                            <th style={th}>SS Zone</th>
                            <th style={th}>NIIT Headroom</th>
                            <th style={th}>IRMAA</th>
                            <th style={th}>Eff. Marginal (Ord.)</th>
                            <th style={th}>Eff. Marginal (LTCG)</th>
                        </tr>
                    </thead>
                    <tbody>
                        {taxSpace.map(y => (
                            <tr key={y.year} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ ...td, textAlign: 'left' }}>{y.year}</td>
                                <td style={td}>{y.age}</td>
                                <td style={td}>{money(y.magi)}</td>
                                <td style={td}>{pct(y.marginal_ordinary_rate)}</td>
                                <td style={td}>
                                    {money(y.bracket_room[0]?.room)}
                                    {y.bracket_room[0] && (
                                        <span style={{ display: 'block', fontSize: '0.75rem', color: '#666' }}>
                                            to top of {pct(y.bracket_room[0].rate)}
                                        </span>
                                    )}
                                </td>
                                <td style={td}>{money(y.ltcg_zero_room)}</td>
                                <td style={td}>{ssZone(y.ss_inclusion_rate)}</td>
                                <td style={{ ...td, color: y.niit_headroom < 0 ? '#d32f2f' : undefined }}>
                                    {money(y.niit_headroom)}
                                </td>
                                <td style={td}>{irmaa(y)}</td>
                                <td style={td}>{pct(y.effective_marginal_ordinary)}</td>
                                <td style={td}>{pct(y.effective_marginal_ltcg)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
            <ul style={{ marginTop: '1rem', fontSize: '0.85rem', color: '#555', paddingLeft: '1.25rem' }}>
                {EXPLAINERS.map(([term, text]) => (
                    <li key={term}><strong>{term}:</strong> {text}</li>
                ))}
            </ul>
            <p style={{ fontSize: '0.8rem', color: '#666' }}>
                WealthView provides planning estimates only, not tax advice. All tax calculations are approximations.
            </p>
        </div>
    );
}
