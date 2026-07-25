import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/** The npm package a module id belongs to, e.g. ".../node_modules/d3-array/src/x.js" -> "d3-array". */
function packageOf(id: string): string | undefined {
    const match = /node_modules\/(?:(@[^/]+)\/)?([^/]+)/.exec(id);
    if (!match) return undefined;
    return match[1] ? `${match[1]}/${match[2]}` : match[2];
}

const REACT_CORE = new Set(['react', 'react-dom', 'react-router', 'scheduler']);

// recharts' own dependency subtree (d3-* ships inside victory-vendor). Listed explicitly because
// the function form of manualChunks resolves per module id, not per package entry point, so
// transitive deps have to be named. Revisit alongside any major recharts bump.
const CHART_PACKAGES = new Set([
    'recharts', 'victory-vendor', 'decimal.js-light', 'eventemitter3', 'tiny-invariant',
    'es-toolkit', 'immer', 'reselect', '@reduxjs/toolkit', 'react-redux', 'clsx',
    'use-sync-external-store',
]);

export default defineConfig({
    plugins: [react()],
    build: {
        rollupOptions: {
            output: {
                // Isolate large, slow-changing third-party libraries into stable
                // vendor chunks so they stay cached across app deploys. recharts
                // (with its d3-* transitive deps) is the heaviest dependency and
                // is split out separately. See
                // docs/quality/2026-05-15-performance-findings.md finding 4.2.
                //
                // Function form (not the object form) because vite 8 builds with rolldown,
                // which rejects the object form outright: "manualChunks is not a function".
                manualChunks(id: string) {
                    const pkg = packageOf(id);
                    if (!pkg) return undefined;
                    if (CHART_PACKAGES.has(pkg) || pkg.startsWith('d3-')) return 'vendor-charts';
                    if (REACT_CORE.has(pkg)) return 'vendor-react';
                    return undefined;
                },
            },
        },
    },
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
    test: {
        globals: true,
        environment: 'jsdom',
        setupFiles: './src/test-setup.ts',
        exclude: ['e2e/**', 'node_modules/**'],
    },
});
