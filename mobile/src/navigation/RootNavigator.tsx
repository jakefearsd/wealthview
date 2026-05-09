import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useAuth } from '../auth/AuthContext';
import { BootSplash } from '../screens/BootSplash';
import { ServerConfigScreen } from '../screens/ServerConfigScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { DashboardScreen } from '../screens/DashboardScreen';
import { SettingsScreen } from '../screens/SettingsScreen';

export type RootStackParamList = {
    BootSplash: undefined;
    ServerConfig: undefined;
    Login: undefined;
    Dashboard: undefined;
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

    // status === 'authenticated'
    return (
        <Stack.Navigator>
            <Stack.Screen
                name="Dashboard"
                component={DashboardScreen}
                options={{ title: 'WealthView' }}
            />
            <Stack.Screen
                name="Settings"
                component={SettingsScreen}
                options={{ title: 'Settings' }}
            />
        </Stack.Navigator>
    );
}
