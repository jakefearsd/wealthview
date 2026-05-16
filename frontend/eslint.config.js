import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import { reactRefresh } from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
    {
        ignores: ['dist', 'coverage', 'test-results', 'playwright-report'],
    },
    {
        files: ['**/*.{ts,tsx}'],
        extends: [
            js.configs.recommended,
            ...tseslint.configs.recommended,
            reactHooks.configs.flat['recommended-latest'],
        ],
        plugins: {
            'react-refresh': reactRefresh.plugin,
        },
        languageOptions: {
            ecmaVersion: 2022,
            globals: globals.browser,
        },
        rules: {
            '@typescript-eslint/no-explicit-any': 'error',
            'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
            // Underscore prefix marks intentionally-unused args/vars (existing codebase convention).
            '@typescript-eslint/no-unused-vars': [
                'error',
                { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
            ],
            // React Compiler is not enabled in this codebase (no babel-plugin-react-compiler).
            // These rules from react-hooks v7 only flag code the compiler cannot optimize —
            // they are advisory for a migration we are not doing, not correctness defects.
            // rules-of-hooks and exhaustive-deps stay ON: those are compiler-independent.
            'react-hooks/set-state-in-effect': 'off',
            'react-hooks/static-components': 'off',
        },
    },
    {
        // Test files: Vitest globals plus Node globals for fixtures.
        files: ['**/*.test.{ts,tsx}', 'src/test-setup.ts', 'src/test-utils.tsx'],
        languageOptions: {
            globals: { ...globals.node },
        },
        rules: {
            'react-refresh/only-export-components': 'off',
        },
    },
    {
        // Playwright e2e and config files run in Node.
        files: ['e2e/**/*.ts', '*.config.{ts,js}', 'playwright.config.ts'],
        languageOptions: {
            globals: { ...globals.node },
        },
        rules: {
            'react-refresh/only-export-components': 'off',
        },
    },
);
