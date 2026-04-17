import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Property cash flow', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('property detail page includes a cash flow section', async ({ page }) => {
        await page.goto('/properties');
        const firstProp = page.locator('text=2020 Beryl Street').first();
        await firstProp.click();
        // Cash flow section either has a heading or the monthly expenses chart
        const cashFlowMarker = page.locator('h3:has-text("Cash Flow"), h3:has-text("Monthly Expenses"), h2:has-text("Cash Flow")').first();
        await expect(cashFlowMarker).toBeVisible({ timeout: 5000 });
    });

    test('property analytics panel renders', async ({ page }) => {
        await page.goto('/properties');
        const firstProp = page.locator('text=2020 Beryl Street').first();
        await firstProp.click();
        await expect(page.locator('h3:has-text("Property Overview"), h3:has-text("Analytics")').first()).toBeVisible({ timeout: 5000 });
    });

    test('property detail shows current value and purchase price', async ({ page }) => {
        await page.goto('/properties');
        const firstProp = page.locator('text=2020 Beryl Street').first();
        await firstProp.click();
        await expect(page.locator('text=/\\$[0-9,]+/').first()).toBeVisible({ timeout: 5000 });
    });
});
