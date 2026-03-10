import HelpText from './HelpText';
import InfoSection from './InfoSection';
import { inputStyle, labelStyle } from '../utils/styles';

const STRATEGY_OPTIONS = [
    {
        value: 'fixed_percentage',
        title: 'Fixed Percentage (4% Rule)',
        description: 'Year 1 withdrawal is balance \u00d7 rate. Each subsequent year adjusts for inflation, not recalculated from balance. Predictable income that doesn\'t adapt to market performance.',
    },
    {
        value: 'dynamic_percentage',
        title: 'Dynamic Percentage',
        description: 'Every year, withdraw current balance \u00d7 rate. Income fluctuates with markets. Portfolio cannot mathematically deplete to zero, but income can drop significantly in downturns.',
    },
    {
        value: 'vanguard_dynamic_spending',
        title: 'Vanguard Dynamic Spending',
        description: 'Year 1 is balance \u00d7 rate. Subsequent years recalculate from current balance, but year-over-year change is clamped between a floor and ceiling. Balances adaptability with income stability.',
    },
] as const;

const WITHDRAWAL_ORDER_OPTIONS = [
    {
        value: 'taxable_first',
        title: 'Taxable First',
        description: 'Draw from taxable accounts first, then traditional, then Roth. Preserves tax-advantaged growth longest.',
    },
    {
        value: 'traditional_first',
        title: 'Traditional First',
        description: 'Draw from traditional accounts first. Reduces future RMDs but triggers early tax.',
    },
    {
        value: 'roth_first',
        title: 'Roth First',
        description: 'Draw from Roth first. Unusual but useful for specific tax planning scenarios.',
    },
    {
        value: 'pro_rata',
        title: 'Pro Rata',
        description: 'Withdraw proportionally from all pools based on balance. Smooths tax impact across years.',
    },
] as const;

export interface WithdrawalStrategySectionProps {
    withdrawalStrategy: string;
    onWithdrawalStrategyChange: (value: string) => void;
    dynamicCeiling: number;
    onDynamicCeilingChange: (value: number) => void;
    dynamicFloor: number;
    onDynamicFloorChange: (value: number) => void;
    withdrawalOrder: string;
    onWithdrawalOrderChange: (value: string) => void;
}

export default function WithdrawalStrategySection({
    withdrawalStrategy,
    onWithdrawalStrategyChange,
    dynamicCeiling,
    onDynamicCeilingChange,
    dynamicFloor,
    onDynamicFloorChange,
    withdrawalOrder,
    onWithdrawalOrderChange,
}: WithdrawalStrategySectionProps) {
    return (
        <>
            <label style={{ ...labelStyle, marginBottom: '0.5rem' }}>Withdrawal Strategy</label>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                {STRATEGY_OPTIONS.map(opt => (
                    <div
                        key={opt.value}
                        onClick={() => onWithdrawalStrategyChange(opt.value)}
                        style={{
                            border: `2px solid ${withdrawalStrategy === opt.value ? '#1976d2' : '#e0e0e0'}`,
                            background: withdrawalStrategy === opt.value ? '#e3f2fd' : '#fff',
                            cursor: 'pointer',
                            borderRadius: '8px',
                            padding: '1rem',
                        }}
                    >
                        <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>{opt.title}</div>
                        <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>{opt.description}</div>
                    </div>
                ))}
            </div>
            {withdrawalStrategy === 'vanguard_dynamic_spending' && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                    <div>
                        <label style={labelStyle}>Ceiling (max increase)</label>
                        <input style={inputStyle} type="number" step="0.01" value={dynamicCeiling} onChange={e => onDynamicCeilingChange(Number(e.target.value))} />
                        <HelpText>Maximum year-over-year spending increase (e.g., 0.05 = 5%)</HelpText>
                    </div>
                    <div>
                        <label style={labelStyle}>Floor (max decrease)</label>
                        <input style={inputStyle} type="number" step="0.01" value={dynamicFloor} onChange={e => onDynamicFloorChange(Number(e.target.value))} />
                        <HelpText>Maximum year-over-year spending decrease (e.g., -0.025 = 2.5%)</HelpText>
                    </div>
                </div>
            )}

            <label style={{ ...labelStyle, marginBottom: '0.5rem' }}>Withdrawal Order</label>
            <InfoSection prompt="What is withdrawal order?">
                When you withdraw from your portfolio in retirement, this determines which accounts are drawn from first. Different orders have different tax consequences — for example, drawing from traditional accounts first triggers income tax earlier but preserves Roth growth.
            </InfoSection>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                {WITHDRAWAL_ORDER_OPTIONS.map(opt => (
                    <div
                        key={opt.value}
                        onClick={() => onWithdrawalOrderChange(opt.value)}
                        style={{
                            border: `2px solid ${withdrawalOrder === opt.value ? '#1976d2' : '#e0e0e0'}`,
                            background: withdrawalOrder === opt.value ? '#e3f2fd' : '#fff',
                            cursor: 'pointer',
                            borderRadius: '8px',
                            padding: '1rem',
                        }}
                    >
                        <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>{opt.title}</div>
                        <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>{opt.description}</div>
                    </div>
                ))}
            </div>
        </>
    );
}
