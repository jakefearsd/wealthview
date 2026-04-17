import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Data export', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('export page is reachable from the sidebar', async ({ page }) => {
        await page.click('nav >> text=Export');
        await expect(page).toHaveURL(/\/export/);
        await expect(page.locator('h2:has-text("Data Export")')).toBeVisible({ timeout: 5000 });
    });

    test('export page offers JSON and per-entity CSV downloads', async ({ page }) => {
        await page.goto('/export');
        await expect(page.locator('button:has-text("Download JSON")')).toBeVisible();
        await expect(page.locator('button:has-text("accounts")')).toBeVisible();
        await expect(page.locator('button:has-text("transactions")')).toBeVisible();
        await expect(page.locator('button:has-text("holdings")')).toBeVisible();
        await expect(page.locator('button:has-text("properties")')).toBeVisible();
    });

    test('clicking Download JSON triggers a download', async ({ page }) => {
        await page.goto('/export');
        const [download] = await Promise.all([
            page.waitForEvent('download', { timeout: 15000 }),
            page.click('button:has-text("Download JSON")'),
        ]);
        expect(await download.suggestedFilename()).toMatch(/\.json$/i);
    });
});
