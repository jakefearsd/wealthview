import React from 'react';
import { StyleSheet, Text } from 'react-native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { PortfolioScreen } from '../screens/PortfolioScreen';
import { AccountDetailScreen } from '../screens/AccountDetailScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { colors, typography } from '../ui/theme';

export type PortfolioStackParamList = {
    Portfolio: undefined;
    AccountDetail: { account: import('@wealthview/shared').AccountResponse };
};

export type SettingsStackParamList = {
    Settings: undefined;
};

export type MainTabsParamList = {
    PortfolioTab: undefined;
    SettingsTab: undefined;
};

const PortfolioStack = createNativeStackNavigator<PortfolioStackParamList>();
const SettingsStack = createNativeStackNavigator<SettingsStackParamList>();
const Tabs = createBottomTabNavigator<MainTabsParamList>();

function PortfolioStackNavigator(): React.JSX.Element {
    return (
        <PortfolioStack.Navigator screenOptions={{ headerShown: false }}>
            <PortfolioStack.Screen name="Portfolio" component={PortfolioScreen} />
            <PortfolioStack.Screen name="AccountDetail" component={AccountDetailScreen} />
        </PortfolioStack.Navigator>
    );
}

function SettingsStackNavigator(): React.JSX.Element {
    return (
        <SettingsStack.Navigator>
            <SettingsStack.Screen
                name="Settings"
                component={SettingsScreen}
                options={{ title: 'Settings', headerShown: false }}
            />
        </SettingsStack.Navigator>
    );
}

function tabIcon(glyph: string) {
    return ({ focused }: { focused: boolean }) => (
        <Text
            style={[
                styles.tabIcon,
                { color: focused ? colors.tabActive : colors.tabInactive },
            ]}
            accessibilityElementsHidden>
            {glyph}
        </Text>
    );
}

export function MainTabsNavigator(): React.JSX.Element {
    return (
        <Tabs.Navigator
            screenOptions={{
                headerShown: false,
                tabBarActiveTintColor: colors.tabActive,
                tabBarInactiveTintColor: colors.tabInactive,
                tabBarStyle: {
                    backgroundColor: colors.tabBarBg,
                    borderTopColor: colors.tabBarBorder,
                },
                tabBarLabelStyle: { ...typography.caption, fontWeight: '600' },
            }}>
            <Tabs.Screen
                name="PortfolioTab"
                component={PortfolioStackNavigator}
                options={{ title: 'Portfolio', tabBarIcon: tabIcon('▤') }}
            />
            <Tabs.Screen
                name="SettingsTab"
                component={SettingsStackNavigator}
                options={{ title: 'Settings', tabBarIcon: tabIcon('⚙') }}
            />
        </Tabs.Navigator>
    );
}

const styles = StyleSheet.create({
    tabIcon: { fontSize: 22 },
});
