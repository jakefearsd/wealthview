import React from 'react';
import { StyleSheet, Text, type TextStyle } from 'react-native';
import { formatCurrency } from '@wealthview/shared';
import { typography } from './theme';

export type MoneySize = 'display' | 'large' | 'body' | 'small';

export interface MoneyDisplayProps {
    /** Decimal string ("1234.56"), Number, or null for unknown. */
    value: string | number | null | undefined;
    /** ISO currency code; defaults to USD. */
    currency?: string;
    size?: MoneySize;
    style?: TextStyle;
    testID?: string;
}

/**
 * Renders a monetary amount with consistent typography. Accepts a string
 * (the BigDecimal-as-string we get from the backend) or a Number; the
 * conversion to display happens here so callers don't have to remember
 * `Number(...)` everywhere.
 */
export function MoneyDisplay({
    value,
    currency = 'USD',
    size = 'body',
    style,
    testID,
}: MoneyDisplayProps): React.JSX.Element {
    const numeric =
        value === null || value === undefined
            ? null
            : typeof value === 'number'
              ? value
              : parseFloat(value);
    const text =
        numeric === null || Number.isNaN(numeric) ? '—' : formatCurrency(numeric, currency);
    return (
        <Text style={[sizeStyles[size], style]} testID={testID}>
            {text}
        </Text>
    );
}

const sizeStyles = StyleSheet.create({
    display: { ...typography.display },
    large: { ...typography.h2 },
    body: { ...typography.body, fontWeight: '600' },
    small: { ...typography.caption },
});
