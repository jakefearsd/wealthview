import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { colors, spacing } from '../ui/theme';

export function BootSplash(): React.JSX.Element {
    return (
        <View style={styles.container} accessibilityLiveRegion="polite">
            <Text style={styles.brand}>WealthView</Text>
            <ActivityIndicator color={colors.primary} size="large" />
            <Text style={styles.caption}>Restoring your session…</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: colors.bg,
        alignItems: 'center',
        justifyContent: 'center',
        padding: spacing.lg,
    },
    brand: {
        fontSize: 28,
        fontWeight: '700',
        color: colors.text,
        marginBottom: spacing.lg,
    },
    caption: {
        marginTop: spacing.md,
        color: colors.textMuted,
        fontSize: 14,
    },
});
