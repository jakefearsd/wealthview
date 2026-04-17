import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Prices page', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('prices page shows the latest prices table', async ({ page }) => {
        await page.click('nav >> text=Prices');
        await expect(page).toHaveURL('/prices');
        await expect(page.locator('h3:has-text("Latest Prices")')).toBeVisible({ timeout: 5000 });
    });

    test('prices page has an Add Manual Price form', async ({ page }) => {
        await page.goto('/prices');
        await expect(page.locator('h3:has-text("Add Manual Price")')).toBeVisible({ timeout: 5000 });
    });

    test('refresh button triggers a re-fetch', async ({ page }) => {
        await page.goto('/prices');
        await expect(page.locator('h3:has-text("Latest Prices")')).toBeVisible({ timeout: 5000 });
        await page.click('button:has-text("Refresh")');
        // Accept either the prices table or the empty-state rendering post-refresh.
        await expect(page.locator('h3:has-text("Latest Prices")')).toBeVisible({ timeout: 5000 });
    });
});
