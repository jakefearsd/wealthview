import React from 'react';
import { StyleSheet, View, type ViewStyle } from 'react-native';
import { colors, radius, spacing } from './theme';

export interface CardProps {
    children: React.ReactNode;
    /** Override the default padding (spacing.lg) — useful for list-row cards. */
    padding?: number;
    style?: ViewStyle;
    testID?: string;
}

/**
 * A neutral surface used to group related content. Whitespace is generous
 * by default; tight rows (e.g. AccountListItem) pass `padding={spacing.md}`.
 */
export function Card({ children, padding, style, testID }: CardProps): React.JSX.Element {
    const resolvedPadding = padding ?? spacing.lg;
    return (
        <View
            style={[styles.card, { padding: resolvedPadding }, style]}
            testID={testID}>
            {children}
        </View>
    );
}

const styles = StyleSheet.create({
    card: {
        backgroundColor: colors.surface,
        borderRadius: radius.lg,
        borderWidth: 1,
        borderColor: colors.border,
    },
});
