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
 * Renders a monetary amount with consistent typography. The API delivers
 * monetary fields as JSON numbers; the string branch is kept for callers
 * formatting user-entered input that hasn't been parsed yet.
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
