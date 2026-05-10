import React, { useState } from 'react';
import { Alert, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Button } from '../ui/Button';
import { TextInput } from '../ui/TextInput';
import { colors, radius, spacing, typography } from '../ui/theme';
import { useAuth } from '../auth/AuthContext';
import { isValidServerUrl, normalizeServerUrl } from '../config/serverUrlStorage';

export function SettingsScreen(): React.JSX.Element {
    const { serverUrl, setServerUrl, logout, status, identity } = useAuth();
    const [value, setValue] = useState<string>(serverUrl ?? '');
    const [error, setError] = useState<string | null>(null);
    const [savingUrl, setSavingUrl] = useState(false);

    const isAuthenticated = status === 'authenticated';

    function confirmAndSave() {
        const candidate = value.trim();
        if (!isValidServerUrl(candidate)) {
            setError('Please enter a valid URL, e.g. https://wealthview.example.com');
            return;
        }
        setError(null);
        const normalized = normalizeServerUrl(candidate);
        if (normalized === serverUrl) {
            return;
        }

        const proceed = async () => {
            setSavingUrl(true);
            try {
                await setServerUrl(normalized);
            } finally {
                setSavingUrl(false);
            }
        };

        if (!isAuthenticated) {
            void proceed();
            return;
        }
        Alert.alert(
            'Sign out?',
            'Changing the server URL will sign you out of the current session. Continue?',
            [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Save and sign out', style: 'destructive', onPress: () => void proceed() },
            ],
        );
    }

    return (
        <ScrollView contentContainerStyle={styles.container}>
            <Text style={styles.heading}>Settings</Text>

            {isAuthenticated && identity ? (
                <View style={styles.card}>
                    <Text style={styles.cardTitle}>Account</Text>
                    <Field label="Email" value={identity.email} />
                    <Field label="Role" value={identity.role} />
                    <Field label="Tenant ID" value={identity.tenant_id} mono />
                    <Field label="User ID" value={identity.user_id} mono />
                </View>
            ) : null}

            <View style={styles.card}>
                <Text style={styles.cardTitle}>Server</Text>
                <TextInput
                    label="Server URL"
                    value={value}
                    onChangeText={setValue}
                    placeholder="https://wealthview.example.com"
                    keyboardType="url"
                    autoCapitalize="none"
                    autoCorrect={false}
                    autoComplete="url"
                    testID="server-url-input"
                    error={error ?? undefined}
                />
                <Button
                    label="Save"
                    onPress={confirmAndSave}
                    loading={savingUrl}
                    testID="save-server-button"
                />
            </View>

            {isAuthenticated ? (
                <View style={styles.card}>
                    <Text style={styles.cardTitle}>Session</Text>
                    <Button
                        label="Log out"
                        variant="danger"
                        onPress={logout}
                        testID="logout-button"
                    />
                </View>
            ) : null}
        </ScrollView>
    );
}

interface FieldProps {
    label: string;
    value: string;
    mono?: boolean;
}

function Field({ label, value, mono = false }: FieldProps): React.JSX.Element {
    return (
        <View style={styles.field}>
            <Text style={styles.fieldLabel}>{label}</Text>
            <Text style={mono ? styles.fieldMono : styles.fieldValue}>{value}</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    container: { backgroundColor: colors.bg, padding: spacing.lg, flexGrow: 1 },
    heading: { ...typography.h1, marginBottom: spacing.lg },
    card: {
        backgroundColor: colors.surface,
        borderRadius: radius.lg,
        padding: spacing.lg,
        marginBottom: spacing.md,
        borderWidth: 1,
        borderColor: colors.border,
    },
    cardTitle: { ...typography.h2, marginBottom: spacing.md },
    field: { marginBottom: spacing.sm },
    fieldLabel: { ...typography.caption, marginBottom: 2 },
    fieldValue: { ...typography.body, fontWeight: '500' },
    fieldMono: { ...typography.mono },
});
