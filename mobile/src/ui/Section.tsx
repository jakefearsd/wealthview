import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { spacing, typography } from './theme';

export interface SectionProps {
    title: string;
    children: React.ReactNode;
    testID?: string;
}

/**
 * A titled vertical section. Title renders as a small all-caps header
 * above the children. Used to group account cards by category on the
 * Portfolio screen.
 */
export function Section({ title, children, testID }: SectionProps): React.JSX.Element {
    return (
        <View style={styles.section} testID={testID}>
            <Text style={styles.title}>{title.toUpperCase()}</Text>
            <View style={styles.body}>{children}</View>
        </View>
    );
}

const styles = StyleSheet.create({
    section: { marginTop: spacing.lg },
    title: { ...typography.sectionHeader, marginBottom: spacing.sm, paddingHorizontal: spacing.xs },
    body: { gap: spacing.sm },
});
