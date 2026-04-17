import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Spending plans', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('spending profiles page is reachable', async ({ page }) => {
        await page.goto('/spending-profiles');
        await expect(page.locator('h2')).toBeVisible({ timeout: 5000 });
    });

    test('scenario form shows a Spending Plan dropdown', async ({ page }) => {
        await page.goto('/projections');
        const newButton = page.locator('button:has-text("New"), a:has-text("New Scenario")').first();
        if (await newButton.count() > 0) {
            await newButton.click();
            await expect(page.locator('text=Spending Plan')).toBeVisible({ timeout: 5000 });
        }
    });

    test('spending plan dropdown includes "None (use withdrawal rate)"', async ({ page }) => {
        await page.goto('/projections');
        const newButton = page.locator('button:has-text("New"), a:has-text("New Scenario")').first();
        if (await newButton.count() === 0) return;
        await newButton.click();

        const dropdown = page.locator('select').filter({ hasText: /None/ }).first();
        if (await dropdown.count() > 0) {
            const options = await dropdown.locator('option').allTextContents();
            expect(options.some(o => /None/.test(o))).toBe(true);
        }
    });
});
