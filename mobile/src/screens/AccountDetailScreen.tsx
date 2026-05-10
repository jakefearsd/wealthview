import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { AccountResponse } from '@wealthview/shared';
import { Card } from '../ui/Card';
import { MoneyDisplay } from '../ui/MoneyDisplay';
import { colors, radius, spacing, typography } from '../ui/theme';

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

type Params = { account: AccountResponse };

export function AccountDetailScreen(): React.JSX.Element {
    const navigation = useNavigation<{ goBack: () => void }>();
    const route = useRoute<RouteProp<Record<string, Params>, string>>();
    const account = route.params.account;

    return (
        <ScrollView contentContainerStyle={styles.container}>
            <View style={styles.header}>
                <Pressable
                    onPress={() => navigation.goBack()}
                    testID="back-button"
                    accessibilityRole="button"
                    accessibilityLabel="Back"
                    style={styles.backButton}>
                    <Text style={styles.backLabel}>‹ Back</Text>
                </Pressable>
                <Text style={styles.title} numberOfLines={2}>
                    {account.name}
                </Text>
            </View>

            <Card style={styles.card}>
                <Text style={styles.label}>Balance</Text>
                <MoneyDisplay
                    value={account.balance}
                    currency={account.currency}
                    size="large"
                />
                <View style={styles.metaRow}>
                    <Meta label="Type" value={typeLabel(account.type)} />
                    <Meta label="Currency" value={account.currency} />
                </View>
                <View style={styles.metaRow}>
                    <Meta label="Institution" value={account.institution ?? '—'} />
                </View>
            </Card>

            <Card style={styles.card}>
                <Text style={styles.label}>What's next</Text>
                <Text style={styles.body}>
                    More details coming soon — holdings, transactions, history.
                </Text>
            </Card>
        </ScrollView>
    );
}

interface MetaProps {
    label: string;
    value: string;
}

function Meta({ label, value }: MetaProps): React.JSX.Element {
    return (
        <View style={styles.meta}>
            <Text style={styles.metaLabel}>{label}</Text>
            <Text style={styles.metaValue}>{value}</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    container: { backgroundColor: colors.bg, padding: spacing.lg, flexGrow: 1 },
    header: { marginBottom: spacing.lg },
    backButton: {
        alignSelf: 'flex-start',
        paddingVertical: spacing.xs,
        paddingHorizontal: spacing.sm,
        borderRadius: radius.sm,
    },
    backLabel: { ...typography.label, color: colors.primary },
    title: { ...typography.h1, marginTop: spacing.sm },
    card: { marginBottom: spacing.md },
    label: { ...typography.label, marginBottom: spacing.xs },
    body: { ...typography.body },
    metaRow: { flexDirection: 'row', flexWrap: 'wrap', marginTop: spacing.md, gap: spacing.lg },
    meta: {},
    metaLabel: { ...typography.caption },
    metaValue: { ...typography.body, fontWeight: '500', marginTop: 2 },
});
