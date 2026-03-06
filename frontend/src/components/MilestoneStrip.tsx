import { cardStyle } from '../utils/styles';
import { formatCurrency } from '../utils/format';
import { findPeakBalance, findDepletionYear } from '../utils/projectionCalcs';
import type { ProjectionResult } from '../types/projection';

interface MilestoneStripProps {
    result: ProjectionResult;
    retirementYear: number | null;
}

const itemStyle = { textAlign: 'center' as const, flex: 1 };
const labelStyle = { fontSize: '0.75rem', color: '#999', marginBottom: '0.25rem' };
const valueStyle = { fontSize: '0.95rem', fontWeight: 600 as const };

export default function MilestoneStrip({ result, retirementYear }: MilestoneStripProps) {
    const peak = findPeakBalance(result.yearly_data);
    const depletion = findDepletionYear(result.yearly_data);

    return (
        <div style={{ ...cardStyle, display: 'flex', gap: '1.5rem', padding: '1rem 1.5rem' }}>
            <div style={itemStyle}>
                <div style={labelStyle}>Retirement</div>
                <div style={valueStyle}>{retirementYear ?? 'N/A'}</div>
            </div>
            <div style={{ width: '1px', background: '#e0e0e0' }} />
            <div style={itemStyle}>
                <div style={labelStyle}>Peak Balance</div>
                <div style={valueStyle}>{formatCurrency(peak.balance)} ({peak.year})</div>
            </div>
            <div style={{ width: '1px', background: '#e0e0e0' }} />
            <div style={itemStyle}>
                <div style={labelStyle}>Depletion</div>
                <div style={{ ...valueStyle, color: depletion ? '#d32f2f' : '#2e7d32' }}>
                    {depletion ? `${depletion.year} (age ${depletion.age})` : 'Funds last through plan'}
                </div>
            </div>
        </div>
    );
}
