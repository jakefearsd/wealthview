/**
 * Shared vitest automock for the `recharts` package.
 *
 * Placement note: this lives at the project ROOT (adjacent to `node_modules`),
 * not under `src/__mocks__` — vitest's automock convention for a node_modules
 * package requires the mock file to sit next to `node_modules`, unlike a local
 * module mock (e.g. `src/api/__mocks__/client.ts`), which sits next to the file
 * it mocks. Verified empirically: a `src/__mocks__/recharts.tsx` placement is
 * silently ignored by a factory-less `vi.mock('recharts')`.
 *
 * Consuming test files opt in with a factory-less `vi.mock('recharts')` (no
 * inline factory) instead of hand-rolling a subset of these primitives.
 *
 * Design: chart containers (LineChart/AreaChart/BarChart/ComposedChart/PieChart)
 * render a `data-testid` naming the chart type plus `data-chart-data` — the
 * JSON-stringified `data` prop, so tests can assert on the shaped rows a
 * component hands to recharts without a captured-variable closure. Series
 * primitives (Line/Area/Bar/Pie/Cell) render a generic `data-testid` for the
 * element type plus `data-key`/`data-name` attributes (from `dataKey`/`name`)
 * so tests can select an individual series's row via
 * `screen.getAllByTestId('bar').find(el => el.getAttribute('data-key') === ...)`
 * — and still render `name` as text content (respecting `hide`) so
 * `screen.getByText(name)` keeps working for the tests that rely on that.
 */
import type { ReactNode } from 'react';

interface ChartContainerProps {
    children?: ReactNode;
    data?: unknown;
}

function chartContainer(testId: string) {
    return function ChartContainer({ children, data }: ChartContainerProps) {
        return (
            <div data-testid={testId} data-chart-data={data === undefined ? undefined : JSON.stringify(data)}>
                {children}
            </div>
        );
    };
}

interface SeriesElementProps {
    dataKey?: string;
    name?: string;
    hide?: boolean;
}

function seriesElement(testId: string) {
    return function SeriesElement({ dataKey, name, hide }: SeriesElementProps) {
        if (hide) return null;
        return (
            <div data-testid={testId} data-key={dataKey} data-name={name}>
                {name}
            </div>
        );
    };
}

export const ResponsiveContainer = ({ children }: { children?: ReactNode }) => (
    <div data-testid="responsive-container">{children}</div>
);

export const LineChart = chartContainer('line-chart');
export const AreaChart = chartContainer('area-chart');
export const BarChart = chartContainer('bar-chart');
export const ComposedChart = chartContainer('composed-chart');
export const PieChart = chartContainer('pie-chart');

export const Line = seriesElement('line');
export const Area = seriesElement('area');
export const Bar = seriesElement('bar');
export const Pie = seriesElement('pie');
export const Cell = seriesElement('cell');

export const XAxis = () => <div data-testid="x-axis" />;
export const YAxis = () => <div data-testid="y-axis" />;
export const CartesianGrid = () => <div data-testid="cartesian-grid" />;
export const Tooltip = () => <div data-testid="tooltip" />;
export const Legend = () => <div data-testid="legend" />;

interface ReferenceLineLabel {
    value?: string;
}

export const ReferenceLine = ({ label }: { label?: ReferenceLineLabel | string }) => {
    const value = typeof label === 'string' ? label : label?.value;
    return <div data-testid="reference-line" data-label={value} />;
};
