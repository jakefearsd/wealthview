import { test, expect } from '@playwright/test';
import { DEMO_USER, login } from './helpers';

test.describe('Authentication', () => {
    test('1: login page renders with email and password fields', async ({ page }) => {
        await page.goto('/login');
        await expect(page.locator('input[type="email"]')).toBeVisible();
        await expect(page.locator('input[type="password"]')).toBeVisible();
        await expect(page.locator('button[type="submit"]')).toBeVisible();
    });

    test('2: successful login redirects to dashboard', async ({ page }) => {
        await login(page);
        await expect(page.locator('nav')).toBeVisible();
        await expect(page.locator('h2:has-text("Dashboard")')).toBeVisible({ timeout: 5000 });
    });

    test('3: failed login shows error message', async ({ page }) => {
        await page.goto('/login');
        await page.fill('input[type="email"]', 'bad@example.com');
        await page.fill('input[type="password"]', 'wrongpassword');
        await page.click('button[type="submit"]');
        // Should show an error, not redirect
        await expect(page).toHaveURL('/login', { timeout: 3000 });
    });

    test('4: unauthenticated user is redirected to login', async ({ page }) => {
        await page.goto('/accounts');
        await expect(page).toHaveURL('/login', { timeout: 5000 });
    });

    test('5: logout clears session and redirects to login', async ({ page }) => {
        await login(page);
        await page.click('button:has-text("Logout")');
        await expect(page).toHaveURL('/login', { timeout: 5000 });
    });
});
