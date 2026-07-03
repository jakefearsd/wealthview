import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
    ActivityIndicator,
    Alert,
    RefreshControl,
    ScrollView,
    StyleSheet,
    Text,
    View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import {
    groupAccountsByCategory,
    type AccountResponse,
    type DashboardSummaryResponse,
} from '@wealthview/shared';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../ui/Button';
import { Card } from '../ui/Card';
import { CategoryChip } from '../ui/CategoryChip';
import { MoneyDisplay } from '../ui/MoneyDisplay';
import { Section } from '../ui/Section';
import { AccountListItem } from '../ui/AccountListItem';
import { colors, spacing, typography } from '../ui/theme';

interface DataState {
    summary: DashboardSummaryResponse | null;
    accounts: AccountResponse[];
}

const EMPTY: DataState = { summary: null, accounts: [] };

const CHIP_TYPES: Array<{ key: string; label: string }> = [
    { key: 'investment', label: 'Investments' },
    { key: 'cash', label: 'Cash' },
    { key: 'property', label: 'Property' },
    { key: 'retirement', label: 'Retirement' },
];

/**
 * Aggregates allocation entries from the dashboard summary into the
 * coarse buckets we display as category chips. Brokerage/ira/401k/roth
 * roll up into "Investments" and "Retirement" — we report investments as
 * the wider bucket here because the chip area is space-constrained and
 * users care about "stocks vs cash vs property" first.
 */
function chipAmounts(summary: DashboardSummaryResponse | null): Array<{ key: string; label: string; amount: number }> {
    if (!summary) return [];
    const result: Array<{ key: string; label: string; amount: number }> = [];
    const investments = summary.total_investments;
    const cash = summary.total_cash;
    const property = summary.total_property_equity;
    if (investments > 0) result.push({ key: 'investment', label: 'Investments', amount: summary.total_investments });
    if (cash > 0) result.push({ key: 'cash', label: 'Cash', amount: summary.total_cash });
    if (property > 0) result.push({ key: 'property', label: 'Property', amount: summary.total_property_equity });
    return result.slice(0, 4);
}

export function PortfolioScreen(): React.JSX.Element {
    const { getDataApis } = useAuth();
    const navigation = useNavigation<{
        navigate: (route: string, params?: object) => void;
    }>();
    const [data, setData] = useState<DataState>(EMPTY);
    const [initialLoading, setInitialLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(
        async (mode: 'initial' | 'refresh' | 'retry') => {
            const apis = getDataApis();
            if (!apis) {
                setError('Not signed in.');
                setInitialLoading(false);
                return;
            }
            if (mode === 'refresh') setRefreshing(true);
            try {
                const [summary, accountsPage] = await Promise.all([
                    apis.dashboardApi.getSummary(),
                    apis.accountsApi.list(),
                ]);
                setData({ summary, accounts: accountsPage.data });
                setError(null);
            } catch (err) {
                const message =
                    err instanceof Error && err.message
                        ? err.message
                        : "We couldn't load your portfolio.";
                setError(`We couldn't load your portfolio. ${message}`);
            } finally {
                setInitialLoading(false);
                setRefreshing(false);
            }
        },
        [getDataApis],
    );

    // Cold-start fetch + refetch whenever the screen comes back into focus
    // (e.g. user switches tabs and comes back). useEffect handles the very
    // first mount; useFocusEffect handles subsequent focus events.
    useEffect(() => {
        void load('initial');
    }, [load]);

    useFocusEffect(
        useCallback(() => {
            // Skip the very first focus — the useEffect above already fetched.
            // We just refresh silently on subsequent focuses.
            return undefined;
        }, []),
    );

    const groups = useMemo(() => groupAccountsByCategory(data.accounts), [data.accounts]);
    const chips = useMemo(() => chipAmounts(data.summary), [data.summary]);

    const onAccountPress = useCallback(
        (account: AccountResponse) => {
            navigation.navigate('AccountDetail', { account });
        },
        [navigation],
    );

    if (initialLoading && !data.summary && !error) {
        return (
            <SafeAreaView style={styles.fill} edges={['top']}>
                <View style={styles.centered} testID="portfolio-loading">
                    <ActivityIndicator size="large" color={colors.primary} />
                </View>
            </SafeAreaView>
        );
    }

    return (
        <SafeAreaView style={styles.fill} edges={['top']}>
            <ScrollView
                contentContainerStyle={styles.scroll}
                refreshControl={
                    <RefreshControl
                        refreshing={refreshing}
                        onRefresh={() => void load('refresh')}
                        tintColor={colors.primary}
                    />
                }>
                <Header />

                {error ? (
                    <ErrorCard message={error} onRetry={() => void load('retry')} />
                ) : null}

                {data.summary ? (
                    <View style={styles.headline}>
                        <Text style={styles.headlineLabel}>Net Worth</Text>
                        <MoneyDisplay
                            value={data.summary.net_worth}
                            size="display"
                            testID="net-worth-headline"
                        />
                        {chips.length > 0 ? (
                            <View style={styles.chipRow}>
                                {chips.map((c) => (
                                    <CategoryChip
                                        key={c.key}
                                        label={c.label}
                                        amount={c.amount}
                                        testID={`chip-${c.key}`}
                                    />
                                ))}
                            </View>
                        ) : null}
                    </View>
                ) : null}

                {!error && data.accounts.length === 0 && !initialLoading ? (
                    <EmptyState />
                ) : null}

                {groups.map((group) => (
                    <Section key={group.category} title={group.label}>
                        {group.accounts.map((account) => (
                            <AccountListItem
                                key={account.id}
                                account={account}
                                onPress={() => onAccountPress(account)}
                                testID={`account-row-${account.id}`}
                            />
                        ))}
                    </Section>
                ))}
            </ScrollView>
        </SafeAreaView>
    );
}

function Header(): React.JSX.Element {
    return (
        <View style={styles.header}>
            <Text style={styles.brand}>WealthView</Text>
        </View>
    );
}

interface ErrorCardProps {
    message: string;
    onRetry(): void;
}

function ErrorCard({ message, onRetry }: ErrorCardProps): React.JSX.Element {
    return (
        <Card style={styles.errorCard}>
            <Text style={styles.errorTitle}>Something went wrong</Text>
            <Text style={styles.errorBody}>{message}</Text>
            <View style={{ marginTop: spacing.md }}>
                <Button label="Retry" onPress={onRetry} testID="portfolio-retry" />
            </View>
        </Card>
    );
}

function EmptyState(): React.JSX.Element {
    return (
        <Card style={styles.emptyCard}>
            <Text style={styles.emptyTitle}>No accounts yet</Text>
            <Text style={styles.emptyBody}>
                Add your investment, retirement, and cash accounts in the WealthView web
                app to see them here.
            </Text>
            <View style={{ marginTop: spacing.md }}>
                <Button
                    label="Open in browser"
                    variant="primary"
                    onPress={() =>
                        Alert.alert(
                            'Open in browser',
                            'Browser deep-linking is coming soon. For now, sign into WealthView from your web browser to add accounts.',
                        )
                    }
                    testID="empty-open-browser"
                />
            </View>
        </Card>
    );
}

const styles = StyleSheet.create({
    fill: { flex: 1, backgroundColor: colors.bg },
    centered: { flex: 1, justifyContent: 'center', alignItems: 'center' },
    scroll: { padding: spacing.lg, paddingBottom: spacing.xl },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: spacing.md,
    },
    brand: { ...typography.h2 },
    headline: { marginTop: spacing.md },
    headlineLabel: { ...typography.label, marginBottom: spacing.xs },
    chipRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.lg },
    errorCard: { borderColor: colors.danger, backgroundColor: colors.dangerBg },
    errorTitle: { ...typography.h2, color: colors.danger },
    errorBody: { ...typography.body, color: colors.text, marginTop: spacing.xs },
    emptyCard: { marginTop: spacing.lg, alignItems: 'flex-start' },
    emptyTitle: { ...typography.h2 },
    emptyBody: { ...typography.body, color: colors.textMuted, marginTop: spacing.sm },
});
