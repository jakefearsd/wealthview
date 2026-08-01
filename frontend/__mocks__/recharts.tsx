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
import { createContext, useContext, cloneElement, isValidElement } from 'react';
import type { ReactNode, ReactElement } from 'react';

/**
 * The rows the enclosing chart container was handed. Tooltip needs them to synthesise a realistic
 * `label`/`payload` so that render callbacks (`content`, `formatter`, `labelFormatter`) actually
 * execute — with the stub Tooltip they never ran, leaving every chart's tooltip logic uncovered.
 */
const ChartDataContext = createContext<Array<Record<string, unknown>> | null>(null);

interface ChartContainerProps {
    children?: ReactNode;
    data?: unknown;
}

function chartContainer(testId: string) {
    return function ChartContainer({ children, data }: ChartContainerProps) {
        const rows = Array.isArray(data) ? (data as Array<Record<string, unknown>>) : null;
        return (
            <div data-testid={testId} data-chart-data={data === undefined ? undefined : JSON.stringify(data)}>
                <ChartDataContext.Provider value={rows}>{children}</ChartDataContext.Provider>
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
interface TooltipEntry {
    dataKey: string;
    name: string;
    value: unknown;
    color: undefined;
    payload: Record<string, unknown>;
}

interface TooltipProps {
    content?: ReactElement | ((props: unknown) => ReactNode);
    formatter?: (value: unknown, name: unknown, entry: TooltipEntry) => unknown;
    labelFormatter?: (label: unknown) => ReactNode;
}

/**
 * Drives the tooltip off the FIRST row of the enclosing chart's data: `label` is that row's
 * `year`/`label` field and `payload` is one entry per numeric field. That is enough for the render
 * callbacks these charts use — they map over `payload` and look the row back up by `label`.
 *
 * The rendered output is scoped under `data-testid="tooltip"`, so a test that needs to assert on
 * tooltip text can query `within(screen.getByTestId('tooltip'))` rather than the whole document.
 */
export const Tooltip = ({ content, formatter, labelFormatter }: TooltipProps) => {
    const rows = useContext(ChartDataContext);
    const row = rows && rows.length > 0 ? rows[0] : null;
    if (!row) return <div data-testid="tooltip" />;

    const label = (row.year ?? row.label) as string | number | undefined;
    const payload: TooltipEntry[] = Object.entries(row)
        .filter(([, v]) => typeof v === 'number')
        .map(([k, v]) => ({ dataKey: k, name: k, value: v, color: undefined, payload: row }));

    if (isValidElement(content)) {
        return (
            <div data-testid="tooltip">
                {cloneElement(content as ReactElement<Record<string, unknown>>, { active: true, payload, label })}
            </div>
        );
    }
    if (typeof content === 'function') {
        return <div data-testid="tooltip">{content({ active: true, payload, label }) as ReactNode}</div>;
    }

    // formatter / labelFormatter style (no custom content element).
    const formatted = formatter
        ? payload.map((entry) => formatter(entry.value, entry.name, entry))
        : [];
    return (
        <div data-testid="tooltip">
            {labelFormatter ? <span data-testid="tooltip-label">{labelFormatter(label)}</span> : null}
            {formatted.map((f, i) => (
                <span key={i} data-testid="tooltip-entry">{Array.isArray(f) ? String(f[1]) : String(f)}</span>
            ))}
        </div>
    );
};
export const Legend = () => <div data-testid="legend" />;

interface ReferenceLineLabel {
    value?: string;
}

export const ReferenceLine = ({ label }: { label?: ReferenceLineLabel | string }) => {
    const value = typeof label === 'string' ? label : label?.value;
    return <div data-testid="reference-line" data-label={value} />;
};

interface ReferenceAreaProps {
    x1?: unknown;
    x2?: unknown;
    label?: ReferenceLineLabel | string;
}

/** Shaded band (e.g. BalanceChart's retirement span); exposes its bounds for assertions. */
export const ReferenceArea = ({ x1, x2, label }: ReferenceAreaProps) => {
    const value = typeof label === 'string' ? label : label?.value;
    return (
        <div
            data-testid="reference-area"
            data-x1={x1 === undefined ? undefined : String(x1)}
            data-x2={x2 === undefined ? undefined : String(x2)}
            data-label={value}
        />
    );
};
