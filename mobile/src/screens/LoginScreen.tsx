import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { Button } from '../ui/Button';
import { TextInput } from '../ui/TextInput';
import { colors, spacing, typography } from '../ui/theme';
import { useAuth } from '../auth/AuthContext';

export function LoginScreen(): React.JSX.Element {
    const { serverUrl, error, login } = useAuth();
    const navigation = useNavigation<{ navigate: (route: string) => void }>();
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [submitting, setSubmitting] = useState(false);

    const trimmedEmail = email.trim();
    const canSubmit = trimmedEmail.length > 0 && password.length > 0 && !submitting;

    async function handleSubmit() {
        if (!canSubmit) return;
        setSubmitting(true);
        try {
            await login(trimmedEmail, password);
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
            <Text style={styles.heading}>Sign in</Text>
            <Text style={styles.subheading}>WealthView</Text>

            {error ? (
                <View style={styles.errorBanner} testID="login-error">
                    <Text style={styles.errorText}>{error}</Text>
                </View>
            ) : null}

            <View style={styles.form}>
                <TextInput
                    label="Email"
                    value={email}
                    onChangeText={setEmail}
                    placeholder="you@example.com"
                    keyboardType="email-address"
                    autoComplete="email"
                    autoCapitalize="none"
                    autoCorrect={false}
                    testID="email-input"
                />
                <TextInput
                    label="Password"
                    value={password}
                    onChangeText={setPassword}
                    placeholder="••••••••"
                    secureTextEntry
                    autoComplete="current-password"
                    testID="password-input"
                />
                <Button
                    label="Sign in"
                    onPress={handleSubmit}
                    loading={submitting}
                    disabled={!canSubmit}
                    testID="sign-in-button"
                />
            </View>

            <View style={styles.footer}>
                <Text style={styles.footerLabel}>Server</Text>
                <Text style={styles.footerValue}>{serverUrl ?? 'not configured'}</Text>
                <Pressable
                    onPress={() => navigation.navigate('Settings')}
                    testID="change-server-link"
                    accessibilityRole="link">
                    <Text style={styles.footerLink}>Change</Text>
                </Pressable>
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
    heading: { ...typography.h1 },
    subheading: { ...typography.body, color: colors.textMuted, marginBottom: spacing.lg },
    form: { marginTop: spacing.md },
    errorBanner: {
        backgroundColor: colors.dangerBg,
        padding: spacing.md,
        borderRadius: 8,
        marginBottom: spacing.md,
    },
    errorText: { color: colors.danger, fontSize: 14, lineHeight: 20 },
    footer: {
        marginTop: spacing.xl,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        flexWrap: 'wrap',
        gap: spacing.xs,
    },
    footerLabel: { ...typography.caption, color: colors.textSubtle },
    footerValue: { ...typography.caption, color: colors.textMuted, marginRight: spacing.sm },
    footerLink: {
        ...typography.caption,
        color: colors.primary,
        fontWeight: '600',
        textDecorationLine: 'underline',
    },
});
