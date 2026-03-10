import type { ProjectionYear } from '../types/projection';
import BalanceChart from './BalanceChart';
import FlowsChart from './FlowsChart';
import SpendingChart from './SpendingChart';

interface ProjectionChartProps {
    data: ProjectionYear[];
    retirementYear: number | null;
    mode: 'balance' | 'flows' | 'spending';
}

export default function ProjectionChart({ data, retirementYear, mode }: ProjectionChartProps) {
    switch (mode) {
        case 'spending':
            return <SpendingChart data={data} />;
        case 'flows':
            return <FlowsChart data={data} retirementYear={retirementYear} />;
        case 'balance':
            return <BalanceChart data={data} retirementYear={retirementYear} />;
    }
}
