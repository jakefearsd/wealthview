/**
 * Shared shape for recharts <Tooltip content={...}> render props.
 * `T` is the type of the original data row that recharts re-attaches as `payload.payload`.
 */
export interface RechartsTooltipEntry<T = Record<string, unknown>> {
    name?: string;
    value?: number;
    color?: string;
    dataKey?: string | number;
    fill?: string;
    payload?: T;
}

export interface RechartsTooltipProps<T = Record<string, unknown>> {
    active?: boolean;
    payload?: Array<RechartsTooltipEntry<T>>;
    label?: string | number;
}
