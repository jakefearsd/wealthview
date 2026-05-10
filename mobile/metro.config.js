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
    // Disable hierarchical lookup so duplicate React installations don't fight.
    disableHierarchicalLookup: true,
    // Honor the package.json `exports` field — required for modern packages
    // like @babel/runtime that expose subpaths (./helpers/...) only via the
    // exports map and not via direct file paths.
    unstable_enablePackageExports: true,
  },
};

module.exports = mergeConfig(getDefaultConfig(projectRoot), config);
