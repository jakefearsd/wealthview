/**
 * WealthView Mobile (scaffold)
 *
 * Single-screen proof that the mobile app consumes the @wealthview/shared
 * package end-to-end: it imports `formatCurrency` from the shared workspace
 * and renders the formatted result.
 *
 * @format
 */

import React from 'react';
import { SafeAreaView, StatusBar, StyleSheet, Text, View } from 'react-native';
import { formatCurrency } from '@wealthview/shared';

const SAMPLE_AMOUNT = 1234567.89;

export default function App(): React.JSX.Element {
  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.body}>
        <Text style={styles.heading}>WealthView</Text>
        <Text style={styles.label}>Sample formatted value</Text>
        <Text style={styles.amount}>{formatCurrency(SAMPLE_AMOUNT)}</Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fafafa' },
  body: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  heading: { fontSize: 28, fontWeight: '600', marginBottom: 24 },
  label: { fontSize: 14, color: '#666' },
  amount: { fontSize: 48, fontWeight: '700', marginTop: 8 },
});
