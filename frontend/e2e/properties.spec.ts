import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Properties', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('14: properties page lists seeded properties', async ({ page }) => {
        await page.click('nav >> text=Properties');
        await expect(page).toHaveURL('/properties');
        await expect(page.locator('text=742 Evergreen Terrace')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=2020 Beryl Street')).toBeVisible();
    });

    test('15: clicking a property navigates to detail', async ({ page }) => {
        await page.click('nav >> text=Properties');
        await page.locator('text=742 Evergreen Terrace').click();
        await expect(page).toHaveURL(/\/properties\//);
        // Should show property details
        await expect(page.locator('h2:has-text("742 Evergreen Terrace")')).toBeVisible({ timeout: 5000 });
    });

    test('16: property detail shows financial data', async ({ page }) => {
        await page.click('nav >> text=Properties');
        await page.locator('h3:has-text("742 Evergreen Terrace")').click();
        // Should show purchase price, current value, etc.
        await expect(page.locator('text=/\\$[0-9,]+/').first()).toBeVisible({ timeout: 5000 });
    });
});
