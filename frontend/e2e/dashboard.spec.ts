import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Dashboard', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('6: dashboard shows summary cards', async ({ page }) => {
        // Should display summary information
        await expect(page.locator('h2:has-text("Dashboard")')).toBeVisible({ timeout: 5000 });
        // Should have summary cards with financial data
        await expect(page.locator('text=Net Worth').first()).toBeVisible({ timeout: 5000 });
    });

    test('7: dashboard loads without errors', async ({ page }) => {
        const errors: string[] = [];
        page.on('pageerror', (err) => errors.push(err.message));

        await page.waitForTimeout(3000);
        expect(errors).toEqual([]);
    });

    test('8: navigation sidebar shows correct links for admin', async ({ page }) => {
        const nav = page.locator('nav');
        await expect(nav.locator('text=Dashboard')).toBeVisible();
        await expect(nav.locator('text=Accounts')).toBeVisible();
        await expect(nav.locator('text=Projections')).toBeVisible();
        await expect(nav.locator('text=Properties')).toBeVisible();
        await expect(nav.locator('text=Prices')).toBeVisible();
        await expect(nav.locator('text=Export')).toBeVisible();
        await expect(nav.locator('text=Settings')).toBeVisible();
        await expect(nav.locator('text=Audit Log')).toBeVisible();
    });

    test('9: admin should NOT see Admin link (only super_admin)', async ({ page }) => {
        const nav = page.locator('nav');
        // demo user is admin, not super_admin — Admin link should be hidden
        await expect(nav.locator('a:has-text("Admin")')).toBeHidden();
    });
});
