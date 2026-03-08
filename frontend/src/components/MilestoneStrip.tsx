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
    const feasibility = result.spending_feasibility;

    const hasProfile = feasibility !== null;
    const depletes = depletion !== null;

    let outcomeLabel: string, outcomeValue: string, outcomeColor: string;

    if (!hasProfile) {
        outcomeLabel = "Depletion";
        outcomeValue = depletes ? `${depletion.year} (age ${depletion.age})` : "Funds last through plan";
        outcomeColor = depletes ? '#d32f2f' : '#2e7d32';
    } else if (depletes) {
        outcomeLabel = "Plan Outcome";
        outcomeValue = `Depleted at age ${depletion.age}`;
        outcomeColor = '#d32f2f';
    } else if (feasibility.spending_feasible) {
        outcomeLabel = "Plan Outcome";
        outcomeValue = "Fully Sustainable";
        outcomeColor = '#2e7d32';
    } else {
        outcomeLabel = "Plan Outcome";
        outcomeValue = `Underfunded at age ${feasibility.first_shortfall_age}`;
        outcomeColor = '#d32f2f';
    }

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
                <div style={labelStyle}>{outcomeLabel}</div>
                <div style={{ ...valueStyle, color: outcomeColor }}>
                    {outcomeValue}
                </div>
            </div>
        </div>
    );
}
