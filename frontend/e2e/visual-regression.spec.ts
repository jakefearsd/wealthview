import { test, expect } from '@playwright/test';
import { login } from './helpers';

/**
 * Visual-regression smoke tests. Captures baseline screenshots for a handful of
 * high-value pages. These will fail on unintended style drift.
 *
 * To refresh baselines after intentional UI work:
 *     npx playwright test e2e/visual-regression.spec.ts --update-snapshots
 */

test.describe('Visual regression', () => {
    test.use({ viewport: { width: 1280, height: 900 } });

    test('login page matches baseline', async ({ page }) => {
        await page.goto('/login');
        await expect(page).toHaveScreenshot('login.png', {
            maxDiffPixelRatio: 0.02,
            fullPage: false,
        });
    });

    test('dashboard matches baseline', async ({ page }) => {
        await login(page);
        // Let async data settle
        await page.waitForTimeout(1500);
        await expect(page).toHaveScreenshot('dashboard.png', {
            maxDiffPixelRatio: 0.03,
            fullPage: false,
            mask: [
                page.locator('text=/\\$[0-9,]+/'), // mask dollar amounts that can jitter
            ],
        });
    });

    test('accounts list matches baseline', async ({ page }) => {
        await login(page);
        await page.goto('/accounts');
        await page.waitForTimeout(1000);
        await expect(page).toHaveScreenshot('accounts.png', {
            maxDiffPixelRatio: 0.03,
            fullPage: false,
            mask: [page.locator('text=/\\$[0-9,]+/')],
        });
    });

    test('properties list matches baseline', async ({ page }) => {
        await login(page);
        await page.goto('/properties');
        await page.waitForTimeout(1000);
        await expect(page).toHaveScreenshot('properties.png', {
            maxDiffPixelRatio: 0.03,
            fullPage: false,
            mask: [page.locator('text=/\\$[0-9,]+/')],
        });
    });

    test('projections list matches baseline', async ({ page }) => {
        await login(page);
        await page.goto('/projections');
        await page.waitForTimeout(1000);
        await expect(page).toHaveScreenshot('projections.png', {
            maxDiffPixelRatio: 0.03,
            fullPage: false,
            mask: [page.locator('text=/\\$[0-9,]+/')],
        });
    });
});
