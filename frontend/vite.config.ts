import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

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
                manualChunks: {
                    'vendor-react': ['react', 'react-dom', 'react-router'],
                    'vendor-charts': ['recharts'],
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
