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
