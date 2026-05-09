import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { Button } from '../ui/Button';
import { TextInput } from '../ui/TextInput';
import { colors, spacing, typography } from '../ui/theme';
import { useAuth } from '../auth/AuthContext';
import { isValidServerUrl, normalizeServerUrl } from '../config/serverUrlStorage';

export function ServerConfigScreen(): React.JSX.Element {
    const { serverUrl, setServerUrl } = useAuth();
    const [value, setValue] = useState<string>(serverUrl ?? '');
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function handleContinue() {
        const candidate = value.trim();
        if (!isValidServerUrl(candidate)) {
            setError('Please enter a valid URL, e.g. https://wealthview.example.com');
            return;
        }
        setError(null);
        setSubmitting(true);
        try {
            await setServerUrl(normalizeServerUrl(candidate));
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
            <Text style={styles.heading}>Connect to your WealthView</Text>
            <Text style={styles.body}>
                WealthView is self-hosted, so you decide where it lives. Enter the URL of
                your server below — for a LAN test that might look like{' '}
                <Text style={styles.mono}>http://192.168.1.50</Text>; for a hosted
                deployment something like{' '}
                <Text style={styles.mono}>https://wealthview.example.com</Text>.
            </Text>

            <View style={styles.form}>
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
                    returnKeyType="go"
                    onSubmitEditing={handleContinue}
                />
                <Button
                    label="Continue"
                    onPress={handleContinue}
                    loading={submitting}
                    testID="continue-button"
                />
            </View>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: {
        flexGrow: 1,
        backgroundColor: colors.bg,
        padding: spacing.lg,
        justifyContent: 'center',
    },
    heading: { ...typography.h1, marginBottom: spacing.md },
    body: { ...typography.body, color: colors.textMuted, marginBottom: spacing.lg },
    mono: typography.mono,
    form: { marginTop: spacing.md },
});
