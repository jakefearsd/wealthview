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
  // Watch the entire monorepo, not just shared/. Metro builds its file map
  // by walking watched directories at startup; nodeModulesPaths alone tells
  // it WHERE to look up modules but not WHICH directories to scan. Without
  // monorepoRoot in here, the hoisted root node_modules/ never makes it
  // into the file map and resolution silently fails for any hoisted dep.
  watchFolders: [monorepoRoot],
  resolver: {
    // Look in both the project's local node_modules and the hoisted root.
    nodeModulesPaths: [
      path.resolve(projectRoot, 'node_modules'),
      path.resolve(monorepoRoot, 'node_modules'),
    ],
  },
};

module.exports = mergeConfig(getDefaultConfig(projectRoot), config);
