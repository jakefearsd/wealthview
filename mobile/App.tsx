/**
 * WealthView Mobile — root component.
 *
 * Composes the providers (SafeArea, AuthContext, NavigationContainer) and
 * delegates routing to RootNavigator, which switches on auth status:
 * BootSplash → ServerConfig → Login → Dashboard.
 *
 * @format
 */

import React from 'react';
import { StatusBar } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AuthProvider } from './src/auth/AuthContext';
import { RootNavigator } from './src/navigation/RootNavigator';

export default function App(): React.JSX.Element {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <AuthProvider>
        <NavigationContainer>
          <RootNavigator />
        </NavigationContainer>
      </AuthProvider>
    </SafeAreaProvider>
  );
}
