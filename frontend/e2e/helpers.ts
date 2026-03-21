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

/**
 * Register a new user via API: admin generates an invite code, then registers the new user.
 * Returns the access_token for the newly registered user.
 */
export async function registerUser(page: Page, email: string, password: string): Promise<string> {
    // Step 1: Login as admin to generate an invite code
    const adminLogin = await page.request.post('/api/v1/auth/login', {
        data: { email: SUPER_ADMIN.email, password: SUPER_ADMIN.password },
    });
    const { access_token: adminToken } = await adminLogin.json();

    // Step 2: Generate an invite code
    const inviteResp = await page.request.post('/api/v1/tenant/invite-codes', {
        headers: { Authorization: `Bearer ${adminToken}` },
    });
    const { code } = await inviteResp.json();

    // Step 3: Register the new user with the invite code
    const registerResp = await page.request.post('/api/v1/auth/register', {
        data: { email, password, invite_code: code },
    });
    const registerBody = await registerResp.json();
    return registerBody.access_token;
}

/**
 * Parse a currency string like "$1,234.56" or "-$500.00" into a number.
 */
export function parseCurrency(text: string | null): number {
    return parseFloat((text ?? '0').replace(/[$,]/g, ''));
}

/**
 * Wait for projection results to load after clicking Run Projection.
 * Waits for "Final Balance" text to appear with a 30s timeout.
 */
export async function waitForProjection(page: Page): Promise<void> {
    await expect(page.locator('text=Final Balance')).toBeVisible({ timeout: 30000 });
}
