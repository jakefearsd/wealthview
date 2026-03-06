import { cardStyle } from '../utils/styles';

interface SummaryCardProps {
    label: string;
    value: string;
    valueColor?: string;
    subtext?: string;
    large?: boolean;
}

export default function SummaryCard({ label, value, valueColor, subtext, large }: SummaryCardProps) {
    return (
        <div style={cardStyle}>
            <div style={{ color: '#666', fontSize: '0.85rem' }}>{label}</div>
            <div style={{ fontSize: large ? '1.75rem' : '1.25rem', fontWeight: large ? 700 : 600, color: valueColor ?? '#1a1a2e' }}>
                {value}
            </div>
            {subtext && <div style={{ color: '#999', fontSize: '0.75rem', marginTop: '0.25rem' }}>{subtext}</div>}
        </div>
    );
}
