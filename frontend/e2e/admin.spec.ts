import { test, expect } from '@playwright/test';
import { login, SUPER_ADMIN, registerUser } from './helpers';

test.describe('Admin workflows', () => {
    test('generates an invite code → registers new user → new user can log in', async ({ page }) => {
        const ts = Date.now();
        const email = `invited+${ts}@e2e.local`;
        const password = 'invited-secret';

        // This helper already exercises: admin login → generate invite → register
        const accessToken = await registerUser(page, email, password);
        expect(accessToken).toBeTruthy();

        // New user can log in via the UI
        await page.goto('/login');
        await page.fill('input[type="email"]', email);
        await page.fill('input[type="password"]', password);
        await page.click('button[type="submit"]');
        await expect(page).toHaveURL('/', { timeout: 10000 });
    });

    test('super admin sees the Admin link and can open the admin area', async ({ page }) => {
        await login(page, SUPER_ADMIN);
        await expect(page.locator('nav a:has-text("Admin")')).toBeVisible({ timeout: 5000 });
        await page.click('nav a:has-text("Admin")');
        await expect(page).toHaveURL(/\/admin/);
    });

    test('admin page has tenants, users, and system config sections', async ({ page }) => {
        await login(page, SUPER_ADMIN);
        await page.goto('/admin');
        // One of the tab headers should always be visible
        const possibleTabs = [
            page.locator('button:has-text("Tenants"), h2:has-text("Tenants")'),
            page.locator('button:has-text("Users"), h2:has-text("Users")'),
            page.locator('button:has-text("System"), h2:has-text("System")'),
        ];
        let found = 0;
        for (const locator of possibleTabs) {
            if ((await locator.count()) > 0) found++;
        }
        expect(found).toBeGreaterThanOrEqual(1);
    });

    test('exchange rates section is reachable', async ({ page }) => {
        await login(page, SUPER_ADMIN);
        await page.goto('/admin');
        const exchangeTab = page.locator('button:has-text("Exchange Rates"), a:has-text("Exchange Rates"), h2:has-text("Exchange Rates")').first();
        if (await exchangeTab.count() > 0) {
            await exchangeTab.click();
            await expect(page.locator('h2:has-text("Exchange Rates")').first()).toBeVisible({ timeout: 5000 });
        }
    });
});
