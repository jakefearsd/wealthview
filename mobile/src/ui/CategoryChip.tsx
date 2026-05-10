import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { formatCurrency } from '@wealthview/shared';
import { colors, radius, spacing, typography } from './theme';

export interface CategoryChipProps {
    label: string;
    /** USD amount; abbreviated to thousands/millions for chip display. */
    amount: string | number;
    testID?: string;
}

function abbreviate(amount: number): string {
    const abs = Math.abs(amount);
    if (abs >= 1_000_000) {
        return `${(amount / 1_000_000).toFixed(amount % 1_000_000 === 0 ? 0 : 1)}M`;
    }
    if (abs >= 1_000) {
        return `${(amount / 1_000).toFixed(0)}K`;
    }
    return formatCurrency(amount);
}

/**
 * A compact category breakdown chip: small label on top, abbreviated dollar
 * amount underneath. Three or four sit side-by-side under the net worth
 * headline so the user sees their composition at a glance.
 */
export function CategoryChip({ label, amount, testID }: CategoryChipProps): React.JSX.Element {
    const numeric = typeof amount === 'number' ? amount : parseFloat(amount);
    const display = Number.isNaN(numeric) ? '—' : `$${abbreviate(numeric)}`;
    return (
        <View style={styles.chip} testID={testID}>
            <Text style={styles.label}>{label}</Text>
            <Text style={styles.amount}>{display}</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    chip: {
        backgroundColor: colors.chipBg,
        borderRadius: radius.md,
        paddingHorizontal: spacing.md,
        paddingVertical: spacing.sm,
        flex: 1,
    },
    label: { ...typography.caption, color: colors.chipText, marginBottom: 2 },
    amount: {
        fontSize: 18,
        fontWeight: '700',
        color: colors.text,
    },
});
