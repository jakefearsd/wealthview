import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './e2e',
    timeout: 30000,
    // Flake tolerance: retry failed tests twice on CI / interactive runs.
    // Set to 0 locally if you want hard failures while debugging new specs.
    retries: 2,
    workers: 3,
    use: {
        baseURL: 'http://localhost',
        headless: true,
        screenshot: 'only-on-failure',
    },
    projects: [
        {
            name: 'chromium',
            use: { browserName: 'chromium' },
        },
        {
            name: 'firefox',
            use: { browserName: 'firefox' },
        },
        {
            name: 'webkit',
            use: { browserName: 'webkit' },
        },
        // Mobile viewport — only smoke tests (tagged with @mobile) run here.
        {
            name: 'mobile-chrome',
            use: { ...devices['iPhone 14'] },
            grep: /@mobile/,
        },
    ],
});
