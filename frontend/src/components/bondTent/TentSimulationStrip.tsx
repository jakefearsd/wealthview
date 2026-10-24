import type { BondTentSimulation } from '../../types/projection';
import { cardStyle, tableStyle, thStyle, tdStyle } from '../../utils/styles';
import { money, years } from './format';

interface Props {
    simulation: BondTentSimulation;
}

export default function TentSimulationStrip({ simulation }: Props) {
    const { columns, trial_count } = simulation;
    const numeric = { ...tdStyle, textAlign: 'right' as const };
    return (
        <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
            <h3 style={{ marginTop: 0 }}>
                Across {trial_count.toLocaleString('en-US')} simulations (starting today)
            </h3>
            <div style={{ overflowX: 'auto' }}>
                <table style={tableStyle}>
                    <thead>
                        <tr>
                            <th style={thStyle} />
                            {columns.map((c, i) => (
                                <th key={i} style={{ ...thStyle, textAlign: 'right' }}>
                                    {c.name}
                                    {c.tent_target != null && (
                                        <div style={{ fontWeight: 400, color: '#666' }}>{money(c.tent_target)} tent</div>
                                    )}
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <th scope="row" style={{ ...tdStyle, textAlign: 'left', fontWeight: 400 }}>Success rate</th>
                            {columns.map((c, i) => (
                                <td key={i} style={numeric}>{Math.round(c.success_rate * 100)}%</td>
                            ))}
                        </tr>
                        <tr>
                            <th scope="row" style={{ ...tdStyle, textAlign: 'left', fontWeight: 400 }}>Years covered, typical / worst 10%</th>
                            {columns.map((c, i) => (
                                <td key={i} style={numeric}>
                                    {years(c.years_covered_median)} / {years(c.years_covered_worst10)}
                                </td>
                            ))}
                        </tr>
                        <tr>
                            <th scope="row" style={{ ...tdStyle, textAlign: 'left', fontWeight: 400 }}>Balance at 90, typical</th>
                            {columns.map((c, i) => (
                                <td key={i} style={numeric}>{money(c.balance_at90_median)}</td>
                            ))}
                        </tr>
                    </tbody>
                </table>
            </div>
            <p style={{ fontSize: '0.8rem', color: '#666', marginBottom: 0 }}>
                Success means every year&apos;s planned spending is paid in full.
            </p>
        </div>
    );
}
