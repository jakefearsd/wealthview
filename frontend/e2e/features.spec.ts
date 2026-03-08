import { test, expect } from '@playwright/test';
import { login, SUPER_ADMIN } from './helpers';

test.describe('Audit Log', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('17: audit log page renders with table', async ({ page }) => {
        await page.click('nav >> text=Audit Log');
        await expect(page).toHaveURL('/audit-log');
        await expect(page.locator('h2:has-text("Audit Log")')).toBeVisible({ timeout: 5000 });
        // Should have filter dropdown
        await expect(page.locator('select')).toBeVisible();
        // Should have a table
        await expect(page.locator('table')).toBeVisible();
    });

    test('18: audit log entity type filter works', async ({ page }) => {
        await page.click('nav >> text=Audit Log');
        await expect(page.locator('table')).toBeVisible({ timeout: 5000 });
        // Filter by 'account'
        await page.locator('select').selectOption('account');
        await page.waitForTimeout(1000);
        // Table should still be visible (even if empty)
        await expect(page.locator('table')).toBeVisible();
    });
});

test.describe('Data Export', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('19: export page renders with download buttons', async ({ page }) => {
        await page.click('nav >> text=Export');
        await expect(page).toHaveURL('/export');
        await expect(page.locator('h2:has-text("Data Export")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Download JSON")')).toBeVisible();
        // Should have CSV buttons
        await expect(page.locator('button:has-text("accounts")')).toBeVisible();
        await expect(page.locator('button:has-text("transactions")')).toBeVisible();
        await expect(page.locator('button:has-text("holdings")')).toBeVisible();
        await expect(page.locator('button:has-text("properties")')).toBeVisible();
    });

    test('20: JSON export returns valid data', async ({ page }) => {
        await login(page);
        // Test the API directly
        const loginResp = await page.request.post('/api/v1/auth/login', {
            data: { email: 'demo@wealthview.local', password: 'demo123' },
        });
        const { access_token } = await loginResp.json();

        const exportResp = await page.request.get('/api/v1/export/json', {
            headers: { Authorization: `Bearer ${access_token}` },
        });
        expect(exportResp.status()).toBe(200);
        const data = await exportResp.json();
        expect(data.accounts).toBeDefined();
        expect(data.transactions).toBeDefined();
        expect(data.holdings).toBeDefined();
        expect(data.properties).toBeDefined();
        expect(data.accounts.length).toBeGreaterThan(0);
    });

    test('21: CSV export returns valid CSV', async ({ page }) => {
        const loginResp = await page.request.post('/api/v1/auth/login', {
            data: { email: 'demo@wealthview.local', password: 'demo123' },
        });
        const { access_token } = await loginResp.json();

        const csvResp = await page.request.get('/api/v1/export/csv/accounts', {
            headers: { Authorization: `Bearer ${access_token}` },
        });
        expect(csvResp.status()).toBe(200);
        const csv = await csvResp.text();
        expect(csv).toContain('id,name,type,institution,created_at');
        expect(csv).toContain('Fidelity Brokerage');
    });
});

test.describe('Settings & Notifications', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('22: settings page shows notification preferences', async ({ page }) => {
        await page.click('nav >> text=Settings');
        await expect(page).toHaveURL('/settings');
        await expect(page.locator('h3:has-text("Notification Preferences")')).toBeVisible({ timeout: 5000 });
        // Should show checkboxes for notification types
        await expect(page.locator('text=Large transactions')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Import completed')).toBeVisible();
        await expect(page.locator('text=Import failed')).toBeVisible();
    });

    test('23: settings page shows invite codes section', async ({ page }) => {
        await page.click('nav >> text=Settings');
        await expect(page.locator('h3:has-text("Invite Codes")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Generate Code")')).toBeVisible();
    });

    test('24: settings page shows users section', async ({ page }) => {
        await page.click('nav >> text=Settings');
        await expect(page.locator('h3:has-text("Users")')).toBeVisible({ timeout: 5000 });
        // Should show demo user
        await expect(page.locator('td:has-text("demo@wealthview.local")')).toBeVisible({ timeout: 5000 });
    });
});

test.describe.serial('Admin Panel (super_admin)', () => {
    test('25: super admin can access admin page', async ({ page }) => {
        await login(page, SUPER_ADMIN);
        await page.click('nav >> text=Admin');
        await expect(page).toHaveURL('/admin');
        // Should show tenant management
        await expect(page.locator('td:has-text("Demo Family")')).toBeVisible({ timeout: 5000 });
    });

    test('26: super admin sees Admin nav link', async ({ page }) => {
        await login(page, SUPER_ADMIN);
        await expect(page.locator('nav a[href="/admin"]')).toBeVisible({ timeout: 5000 });
    });
});

test.describe('Prices', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('27: prices page loads', async ({ page }) => {
        await page.click('nav >> text=Prices');
        await expect(page).toHaveURL('/prices');
        // Should show the prices page with the add price form
        await expect(page.locator('h2:has-text("Prices")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Add Manual Price')).toBeVisible();
    });
});

test.describe('Holding Detail', () => {
    test('28: holding detail page shows holding info', async ({ page }) => {
        await login(page);
        await page.click('nav >> text=Accounts');
        await page.locator('h3:has-text("Fidelity Brokerage")').click();
        await expect(page).toHaveURL(/\/accounts\//, { timeout: 5000 });
        // Click on a holding symbol link (e.g., AAPL)
        await page.locator('a:has-text("AAPL")').first().click();
        await expect(page).toHaveURL(/\/holdings\//, { timeout: 5000 });
        // Should show holding details
        await expect(page.locator('h2:has-text("AAPL")')).toBeVisible({ timeout: 5000 });
    });
});

test.describe('Projections', () => {
    test('29: projections page loads', async ({ page }) => {
        await login(page);
        await page.click('nav >> text=Projections');
        await expect(page).toHaveURL('/projections');
        await expect(page.locator('h2:has-text("Projections")')).toBeVisible({ timeout: 5000 });
    });
});

test.describe('Spending Profiles', () => {
    test('30: spending profiles page loads', async ({ page }) => {
        await login(page);
        await page.click('nav >> text=Spending Profiles');
        await expect(page).toHaveURL('/spending-profiles');
        await expect(page.locator('h2:has-text("Spending Profiles")')).toBeVisible({ timeout: 5000 });
    });
});

test.describe('404 Page', () => {
    test('31: unknown route shows not found page', async ({ page }) => {
        await login(page);
        await page.goto('/nonexistent-page');
        await expect(page.locator('text=Page not found')).toBeVisible({ timeout: 5000 });
    });
});
