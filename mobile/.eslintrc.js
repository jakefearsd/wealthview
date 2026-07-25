module.exports = {
  root: true,
  extends: '@react-native',
  rules: {
    // `void somePromise()` is this codebase's explicit marker for a deliberately
    // un-awaited promise (see apiClient's token-refresh callbacks and the screens'
    // load() calls). @react-native enables no-void, which flags exactly that idiom.
    // Type-aware linting is not configured here, so no-floating-promises is not
    // available as the alternative signal.
    'no-void': 'off',
  },
  overrides: [
    {
      // @react-native's own jest override only matches test files, so the Jest globals
      // used by the setup file (which jest.config.js loads via setupFiles) are otherwise
      // flagged as no-undef.
      files: ['jest.setup.js'],
      env: {'jest/globals': true},
    },
  ],
};
