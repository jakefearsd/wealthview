import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useAuth } from '../auth/AuthContext';
import { BootSplash } from '../screens/BootSplash';
import { ServerConfigScreen } from '../screens/ServerConfigScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { MainTabsNavigator } from './MainTabsNavigator';

export type RootStackParamList = {
    BootSplash: undefined;
    ServerConfig: undefined;
    Login: undefined;
    Main: undefined;
    Settings: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export function RootNavigator(): React.JSX.Element {
    const { status } = useAuth();

    if (status === 'restoring') {
        return (
            <Stack.Navigator screenOptions={{ headerShown: false }}>
                <Stack.Screen name="BootSplash" component={BootSplash} />
            </Stack.Navigator>
        );
    }

    if (status === 'needs_server') {
        return (
            <Stack.Navigator screenOptions={{ headerShown: false }}>
                <Stack.Screen name="ServerConfig" component={ServerConfigScreen} />
            </Stack.Navigator>
        );
    }

    if (status === 'unauthenticated') {
        return (
            <Stack.Navigator>
                <Stack.Screen
                    name="Login"
                    component={LoginScreen}
                    options={{ headerShown: false }}
                />
                <Stack.Screen
                    name="Settings"
                    component={SettingsScreen}
                    options={{ title: 'Settings' }}
                />
            </Stack.Navigator>
        );
    }

    // status === 'authenticated' — bottom tabs become the main surface.
    return (
        <Stack.Navigator screenOptions={{ headerShown: false }}>
            <Stack.Screen name="Main" component={MainTabsNavigator} />
        </Stack.Navigator>
    );
}
