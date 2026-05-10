const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const path = require('path');

/**
 * Metro configuration for a monorepo workspace.
 *
 * Metro doesn't follow workspace symlinks by default and only resolves
 * `node_modules` from the project root. For our `npm workspaces` setup we
 * need it to also watch the sibling `shared/` package and look for modules
 * in the hoisted root `node_modules`.
 *
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const projectRoot = __dirname;
const monorepoRoot = path.resolve(projectRoot, '..');

const config = {
  // Watch the shared workspace so changes there trigger rebuilds.
  watchFolders: [path.resolve(monorepoRoot, 'shared')],
  resolver: {
    // Look in both the project's local node_modules and the hoisted root.
    nodeModulesPaths: [
      path.resolve(projectRoot, 'node_modules'),
      path.resolve(monorepoRoot, 'node_modules'),
    ],
    // DISABLE package-exports resolution. Metro's default conditions for
    // RN are ['react-native'], and packages like @babel/runtime declare
    // their exports map with only ['node','import','default'] conditions.
    // When Metro can't match a condition AND won't fall back to the plain
    // file path, even existing files become "unresolvable." With this off,
    // Metro uses traditional <package>/<subpath>.js lookup which Just Works.
    unstable_enablePackageExports: false,
  },
};

module.exports = mergeConfig(getDefaultConfig(projectRoot), config);
