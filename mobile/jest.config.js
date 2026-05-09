module.exports = {
  preset: '@react-native/jest-preset',
  setupFiles: ['<rootDir>/jest.setup.js'],
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|@react-navigation|react-native-keychain|react-native-screens|react-native-safe-area-context|@wealthview/shared)/)',
  ],
  moduleNameMapper: {
    '^@wealthview/shared$': '<rootDir>/../shared/src/index.ts',
    '^@wealthview/shared/(.*)$': '<rootDir>/../shared/src/$1',
  },
};
