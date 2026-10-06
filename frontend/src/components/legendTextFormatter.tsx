/**
 * Recharts colours legend text with the series stroke/fill, and pale series (orange, slate, indigo)
 * fail 4.5:1 as text. The swatch keeps the series colour; the label is neutral dark grey.
 */
export const legendTextFormatter = (value: string) => <span style={{ color: '#333' }}>{value}</span>;
