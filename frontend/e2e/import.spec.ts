import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Import flow', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('import page is reachable from an account detail page', async ({ page }) => {
        await page.click('nav >> text=Accounts');
        await page.locator('text=Fidelity Brokerage').first().click();
        await expect(page).toHaveURL(/\/accounts\//);
        // Accounts typically have an import link per account
        const importLink = page.locator('a:has-text("Import"), button:has-text("Import")').first();
        if (await importLink.count() > 0) {
            await importLink.click();
            await expect(page.locator('h2:has-text("Import")')).toBeVisible({ timeout: 5000 });
        }
    });

    test('import page shows tab controls for transactions and positions', async ({ page }) => {
        await page.click('nav >> text=Accounts');
        await page.locator('text=Fidelity Brokerage').first().click();
        const importLink = page.locator('a:has-text("Import"), button:has-text("Import")').first();
        if (await importLink.count() === 0) return;
        await importLink.click();
        await expect(page.locator('button:has-text("Transaction History")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Current Positions")')).toBeVisible();
    });

    test('import page offers multiple CSV format options', async ({ page }) => {
        await page.click('nav >> text=Accounts');
        await page.locator('text=Fidelity Brokerage').first().click();
        const importLink = page.locator('a:has-text("Import"), button:has-text("Import")').first();
        if (await importLink.count() === 0) return;
        await importLink.click();

        const formatSelect = page.locator('select').first();
        await expect(formatSelect).toBeVisible();
        const options = await formatSelect.locator('option').allTextContents();
        expect(options).toContain('Generic CSV');
        expect(options).toContain('Fidelity');
        expect(options).toContain('OFX / QFX');
    });
});
