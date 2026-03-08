import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Accounts', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('10: accounts page lists seeded accounts', async ({ page }) => {
        await page.click('nav >> text=Accounts');
        await expect(page).toHaveURL('/accounts');
        // Should show the 3 seeded accounts
        await expect(page.locator('text=Fidelity Brokerage')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Fidelity 401(k)')).toBeVisible();
        await expect(page.locator('text=Chase Checking')).toBeVisible();
    });

    test('11: clicking an account navigates to detail page', async ({ page }) => {
        await page.click('nav >> text=Accounts');
        await page.locator('text=Fidelity Brokerage').click();
        await expect(page).toHaveURL(/\/accounts\//);
        // Should show holdings for the brokerage account
        await expect(page.locator('a:has-text("AAPL")').first()).toBeVisible({ timeout: 5000 });
    });

    test('12: account detail shows holdings and transactions', async ({ page }) => {
        await page.click('nav >> text=Accounts');
        await page.locator('text=Fidelity Brokerage').click();
        await expect(page).toHaveURL(/\/accounts\//);
        // Should have both holdings and transactions sections
        await expect(page.locator('text=Holdings').first()).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Transactions').first()).toBeVisible({ timeout: 5000 });
    });

    test('13: create new account', async ({ page }) => {
        await page.click('nav >> text=Accounts');
        await expect(page.locator('text=Fidelity Brokerage')).toBeVisible({ timeout: 5000 });

        // Open the create form
        await page.click('button:has-text("New Account")');

        // Fill in the create form
        await page.fill('input[placeholder="Name"]', 'Test E2E Account');
        await page.locator('select').first().selectOption('brokerage');
        await page.fill('input[placeholder="Institution"]', 'E2E Bank');
        await page.click('button:has-text("Create")');

        // Should appear in the list
        await expect(page.locator('text=Test E2E Account')).toBeVisible({ timeout: 5000 });
    });
});
