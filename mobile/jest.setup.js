/**
 * Jest setup: stub native modules that have no JS implementation in the
 * default RN test environment. Tests that need richer behavior re-mock
 * these via jest.mock() at the top of the test file.
 */

jest.mock('react-native-keychain', () => ({
  setGenericPassword: jest.fn().mockResolvedValue({ service: 'svc', storage: 'mock' }),
  getGenericPassword: jest.fn().mockResolvedValue(false),
  resetGenericPassword: jest.fn().mockResolvedValue(true),
  hasGenericPassword: jest.fn().mockResolvedValue(false),
}));

jest.mock('react-native-screens', () => {
  const React = require('react');
  const passThrough = ({ children }) => React.createElement(React.Fragment, null, children);
  return {
    enableScreens: jest.fn(),
    enableFreeze: jest.fn(),
    Screen: passThrough,
    ScreenContainer: passThrough,
    ScreenStack: passThrough,
    ScreenStackHeaderConfig: passThrough,
    ScreenStackHeaderSubview: passThrough,
    NativeScreen: passThrough,
    NativeScreenContainer: passThrough,
  };
});

// The native-stack navigator depends on private native screen primitives
// that don't render in jsdom. Replace it with a stack navigator that uses
// plain Views so tests can still navigate by status branch.
jest.mock('@react-navigation/native-stack', () => {
  const React = require('react');
  const { View } = require('react-native');

  function createNativeStackNavigator() {
    const Navigator = ({ children }) => {
      // Render only the first Screen child — enough to verify the route the
      // navigator picks based on auth status.
      const screens = React.Children.toArray(children).filter(Boolean);
      const first = screens[0];
      if (!first || !first.props?.component) {
        return React.createElement(View, null);
      }
      const Component = first.props.component;
      return React.createElement(View, null, React.createElement(Component, {}));
    };
    const Screen = () => null;
    return { Navigator, Screen };
  }

  return { __esModule: true, createNativeStackNavigator };
});

jest.mock('react-native-safe-area-context', () => {
  const React = require('react');
  const SafeAreaProvider = ({ children }) => React.createElement(React.Fragment, null, children);
  const SafeAreaView = ({ children, ...props }) =>
    React.createElement('View', props, children);
  return {
    SafeAreaProvider,
    SafeAreaView,
    SafeAreaInsetsContext: { Consumer: ({ children }) => children({ top: 0, bottom: 0, left: 0, right: 0 }) },
    useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
    useSafeAreaFrame: () => ({ x: 0, y: 0, width: 0, height: 0 }),
  };
});
