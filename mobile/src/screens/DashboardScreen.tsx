import React, { useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { Button } from '../ui/Button';
import { colors, radius, spacing, typography } from '../ui/theme';
import { useAuth } from '../auth/AuthContext';

export function DashboardScreen(): React.JSX.Element {
    const { identity, serverUrl, logout, refreshMe } = useAuth();
    const navigation = useNavigation<{ navigate: (route: string) => void }>();

    useFocusEffect(
        useCallback(() => {
            void refreshMe();
            return undefined;
        }, [refreshMe]),
    );

    return (
        <ScrollView contentContainerStyle={styles.container}>
            <View style={styles.header}>
                <View style={{ flex: 1 }}>
                    <Text style={styles.greetLabel}>Welcome</Text>
                    <Text style={styles.greetEmail}>{identity?.email ?? 'Loading…'}</Text>
                </View>
                <Pressable
                    onPress={() => navigation.navigate('Settings')}
                    testID="settings-button"
                    accessibilityRole="button"
                    accessibilityLabel="Settings"
                    style={styles.settingsBtn}>
                    <Text style={styles.settingsLabel}>Settings</Text>
                </Pressable>
            </View>

            <View style={styles.card}>
                <Text style={styles.cardLabel}>Role</Text>
                <Text style={styles.cardValue}>{identity?.role ?? '—'}</Text>
                <Text style={[styles.cardLabel, { marginTop: spacing.md }]}>Tenant ID</Text>
                <Text style={styles.cardMono}>{identity?.tenant_id ?? '—'}</Text>
                <Text style={[styles.cardLabel, { marginTop: spacing.md }]}>User ID</Text>
                <Text style={styles.cardMono}>{identity?.user_id ?? '—'}</Text>
            </View>

            <View style={styles.card}>
                <Text style={styles.cardLabel}>Server</Text>
                <Text style={styles.cardValue} numberOfLines={2}>
                    {serverUrl ?? 'not configured'}
                </Text>
            </View>

            <View style={styles.card}>
                <Text style={styles.cardLabel}>Status</Text>
                <Text style={styles.cardValue}>You're signed in.</Text>
                <Text style={styles.cardCaption}>
                    Account, transaction, and projection screens land in the next pass.
                </Text>
            </View>

            <View style={styles.footer}>
                <Button
                    label="Log out"
                    variant="danger"
                    onPress={logout}
                    testID="logout-button"
                />
            </View>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: { backgroundColor: colors.bg, padding: spacing.lg, flexGrow: 1 },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: spacing.lg,
    },
    greetLabel: { ...typography.caption, color: colors.textSubtle },
    greetEmail: { ...typography.h2 },
    settingsBtn: {
        paddingHorizontal: spacing.md,
        paddingVertical: spacing.sm,
        borderRadius: radius.md,
        borderWidth: 1,
        borderColor: colors.border,
        backgroundColor: colors.surface,
    },
    settingsLabel: { ...typography.label },
    card: {
        backgroundColor: colors.surface,
        borderRadius: radius.lg,
        padding: spacing.lg,
        marginBottom: spacing.md,
        borderWidth: 1,
        borderColor: colors.border,
    },
    cardLabel: { ...typography.label, marginBottom: spacing.xs },
    cardValue: { ...typography.body, fontWeight: '500' },
    cardMono: { ...typography.mono },
    cardCaption: { ...typography.caption, color: colors.textSubtle, marginTop: spacing.sm },
    footer: { marginTop: spacing.lg },
});
