import React from 'react';
import {
    ActivityIndicator,
    Pressable,
    StyleSheet,
    Text,
    type GestureResponderEvent,
} from 'react-native';
import { colors, radius, spacing } from './theme';

export interface ButtonProps {
    label: string;
    onPress: (event: GestureResponderEvent) => void;
    disabled?: boolean;
    loading?: boolean;
    variant?: 'primary' | 'secondary' | 'danger';
    testID?: string;
    accessibilityLabel?: string;
}

export function Button({
    label,
    onPress,
    disabled = false,
    loading = false,
    variant = 'primary',
    testID,
    accessibilityLabel,
}: ButtonProps): React.JSX.Element {
    const isDisabled = disabled || loading;
    const containerStyle = [
        styles.base,
        variantStyles[variant].container,
        isDisabled && styles.disabled,
    ];
    const labelStyle = [styles.label, variantStyles[variant].label];

    return (
        <Pressable
            onPress={onPress}
            disabled={isDisabled}
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={accessibilityLabel ?? label}
            accessibilityState={{ disabled: isDisabled, busy: loading }}
            style={({ pressed }) => [
                ...containerStyle,
                pressed && !isDisabled && styles.pressed,
            ]}>
            {loading ? (
                <ActivityIndicator color={variantStyles[variant].label.color as string} />
            ) : (
                <Text style={labelStyle}>{label}</Text>
            )}
        </Pressable>
    );
}

const styles = StyleSheet.create({
    base: {
        paddingVertical: spacing.md,
        paddingHorizontal: spacing.lg,
        borderRadius: radius.md,
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: 48,
    },
    disabled: { opacity: 0.5 },
    pressed: { opacity: 0.85 },
    label: { fontSize: 16, fontWeight: '600' },
});

const variantStyles = {
    primary: {
        container: { backgroundColor: colors.primary },
        label: { color: colors.primaryText },
    },
    secondary: {
        container: {
            backgroundColor: colors.surface,
            borderWidth: 1,
            borderColor: colors.border,
        },
        label: { color: colors.text },
    },
    danger: {
        container: { backgroundColor: colors.dangerBg },
        label: { color: colors.danger },
    },
} as const;
