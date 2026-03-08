import { type Page, expect } from '@playwright/test';

export const DEMO_USER = {
    email: 'demo@wealthview.local',
    password: 'demo123',
};

export const SUPER_ADMIN = {
    email: 'admin@wealthview.local',
    password: 'admin123',
};

export async function login(page: Page, user = DEMO_USER) {
    await page.goto('/login');
    await page.fill('input[type="email"]', user.email);
    await page.fill('input[type="password"]', user.password);
    await page.click('button[type="submit"]');
    // Wait for redirect to dashboard
    await expect(page).toHaveURL('/', { timeout: 10000 });
}

export async function loginViaApi(page: Page, user = DEMO_USER) {
    // Login via API and set tokens in localStorage before navigating
    const response = await page.request.post('/api/v1/auth/login', {
        data: { email: user.email, password: user.password },
    });
    const body = await response.json();
    await page.goto('/');
    await page.evaluate((tokens) => {
        localStorage.setItem('access_token', tokens.access_token);
        localStorage.setItem('refresh_token', tokens.refresh_token);
    }, body);
    await page.goto('/');
    await expect(page.locator('nav')).toBeVisible({ timeout: 5000 });
}
