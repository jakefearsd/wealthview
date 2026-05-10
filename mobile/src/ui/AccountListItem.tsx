import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { AccountResponse } from '@wealthview/shared';
import { colors, radius, spacing, typography } from './theme';
import { MoneyDisplay } from './MoneyDisplay';

export interface AccountListItemProps {
    account: AccountResponse;
    onPress(): void;
    testID?: string;
}

const TYPE_LABELS: Record<string, string> = {
    brokerage: 'Brokerage',
    ira: 'IRA',
    '401k': '401(k)',
    roth: 'Roth IRA',
    bank: 'Cash',
    property: 'Property',
};

function typeLabel(type: string): string {
    return TYPE_LABELS[type] ?? type;
}

/**
 * A single tappable row showing one account: name (large), institution +
 * type + currency on the meta line, balance prominently right-aligned with
 * a chevron suggesting tap-through to the detail screen.
 */
export function AccountListItem({
    account,
    onPress,
    testID,
}: AccountListItemProps): React.JSX.Element {
    const meta = [account.institution, typeLabel(account.type), account.currency]
        .filter(Boolean)
        .join(' · ');
    return (
        <Pressable
            style={({ pressed }) => [styles.row, pressed && styles.pressed]}
            onPress={onPress}
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={`${account.name}, balance ${account.balance} ${account.currency}`}>
            <View style={styles.left}>
                <Text style={styles.name} numberOfLines={1}>
                    {account.name}
                </Text>
                <Text style={styles.meta} numberOfLines={1}>
                    {meta}
                </Text>
            </View>
            <View style={styles.right}>
                <MoneyDisplay
                    value={account.balance}
                    currency={account.currency}
                    size="body"
                />
                <Text style={styles.chevron}>›</Text>
            </View>
        </Pressable>
    );
}

const styles = StyleSheet.create({
    row: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: colors.surface,
        borderRadius: radius.lg,
        borderWidth: 1,
        borderColor: colors.border,
        padding: spacing.md,
    },
    pressed: { opacity: 0.7 },
    left: { flex: 1, marginRight: spacing.md },
    right: { flexDirection: 'row', alignItems: 'center' },
    name: { ...typography.body, fontWeight: '600' },
    meta: { ...typography.caption, marginTop: 2 },
    chevron: {
        marginLeft: spacing.sm,
        fontSize: 22,
        color: colors.textSubtle,
        lineHeight: 22,
    },
});
