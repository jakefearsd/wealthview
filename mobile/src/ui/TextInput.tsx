import React from 'react';
import {
    StyleSheet,
    Text,
    TextInput as RNTextInput,
    View,
    type TextInputProps as RNTextInputProps,
} from 'react-native';
import { colors, radius, spacing, typography } from './theme';

export interface TextInputProps extends RNTextInputProps {
    label?: string;
    error?: string;
    /** Render label/input; passes through any additional RN TextInput props. */
}

export function TextInput({
    label,
    error,
    style,
    ...rest
}: TextInputProps): React.JSX.Element {
    return (
        <View style={styles.wrapper}>
            {label ? <Text style={styles.label}>{label}</Text> : null}
            <RNTextInput
                style={[styles.input, error ? styles.inputError : null, style]}
                placeholderTextColor={colors.textSubtle}
                autoCapitalize="none"
                autoCorrect={false}
                {...rest}
            />
            {error ? <Text style={styles.errorText}>{error}</Text> : null}
        </View>
    );
}

const styles = StyleSheet.create({
    wrapper: { marginBottom: spacing.md },
    label: { ...typography.label, marginBottom: spacing.xs },
    input: {
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: radius.md,
        paddingHorizontal: spacing.md,
        paddingVertical: spacing.sm + 2,
        backgroundColor: colors.surface,
        color: colors.text,
        fontSize: 16,
        minHeight: 48,
    },
    inputError: { borderColor: colors.danger },
    errorText: {
        color: colors.danger,
        fontSize: 13,
        marginTop: spacing.xs,
    },
});
