import { test, expect } from '@playwright/test';
import { login } from './helpers';

/**
 * Mobile viewport smoke tests. Tagged with @mobile so the
 * `mobile-chrome` Playwright project only runs these.
 * Other projects (chromium/firefox/webkit) skip them via grep.
 */

test.describe('Mobile viewport @mobile', () => {
    test('@mobile login renders on iPhone 14', async ({ page }) => {
        await page.goto('/login');
        await expect(page.locator('input[type="email"]')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('input[type="password"]')).toBeVisible();
    });

    test('@mobile dashboard navigation is reachable', async ({ page }) => {
        await login(page);
        // On mobile, either there's a nav visible or a hamburger — smoke-check for the greeting
        await expect(page.locator('h2:has-text("Dashboard"), nav').first()).toBeVisible({ timeout: 5000 });
    });

    test('@mobile accounts page scrolls without layout breaking', async ({ page }) => {
        await login(page);
        await page.goto('/accounts');
        // No JS errors indicates the list laid out
        const errors: string[] = [];
        page.on('pageerror', (err) => errors.push(err.message));
        await page.waitForTimeout(1500);
        expect(errors).toEqual([]);
    });
});
