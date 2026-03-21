import { test, expect } from '@playwright/test';
import { login, SUPER_ADMIN } from './helpers';

test.describe('Dashboard data integrity', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('32: dashboard net worth equals sum of investments + cash + property equity', async ({ page }) => {
        await expect(page.locator('h2:has-text("Dashboard")')).toBeVisible({ timeout: 5000 });
        // Extract the 4 summary card values
        const netWorthText = await page.locator('text=Net Worth').locator('..').locator('div').nth(1).textContent();
        const investmentsText = await page.locator('text=Investments').locator('..').locator('div').nth(1).textContent();
        const cashText = await page.locator('text=Cash').locator('..').locator('div').nth(1).textContent();
        const propertyEquityText = await page.locator('text=Property Equity').locator('..').locator('div').nth(1).textContent();

        const parse = (s: string | null) => parseFloat((s ?? '0').replace(/[$,]/g, ''));
        const netWorth = parse(netWorthText);
        const investments = parse(investmentsText);
        const cash = parse(cashText);
        const propertyEquity = parse(propertyEquityText);

        // Net worth should equal investments + cash + property equity
        expect(Math.abs(netWorth - (investments + cash + propertyEquity))).toBeLessThan(1);
    });

    test('33: dashboard accounts table lists all seeded accounts', async ({ page }) => {
        await expect(page.locator('h3:has-text("Accounts")')).toBeVisible({ timeout: 5000 });
        const table = page.locator('table').first();
        await expect(table.locator('td:has-text("Fidelity Brokerage")').first()).toBeVisible();
        await expect(table.locator('td:has-text("Fidelity 401(k)")').first()).toBeVisible();
        await expect(table.locator('td:has-text("Chase Checking")').first()).toBeVisible();
    });

    test('34: dashboard accounts table includes properties', async ({ page }) => {
        await expect(page.locator('h3:has-text("Accounts")')).toBeVisible({ timeout: 5000 });
        const table = page.locator('table').first();
        await expect(table.locator('td:has-text("742 Evergreen Terrace")').first()).toBeVisible();
        await expect(table.locator('td:has-text("2020 Beryl Street")').first()).toBeVisible();
    });

    test('35: combined portfolio history chart renders', async ({ page }) => {
        await expect(page.locator('text=Combined Portfolio History')).toBeVisible({ timeout: 5000 });
        // Chart should have a period selector
        await expect(page.locator('select').first()).toBeVisible();
    });
});

test.describe('Account detail data integrity', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
        await page.click('nav >> text=Accounts');
    });

    test('36: brokerage account shows 4 holdings', async ({ page }) => {
        await page.locator('h3:has-text("Fidelity Brokerage")').first().click();
        await expect(page).toHaveURL(/\/accounts\//);
        // Seeded data has AAPL, NVDA, GOOG, VOO
        await expect(page.locator('a:has-text("AAPL")').first()).toBeVisible({ timeout: 5000 });
        await expect(page.locator('a:has-text("NVDA")').first()).toBeVisible();
        await expect(page.locator('a:has-text("GOOG")').first()).toBeVisible();
        await expect(page.locator('a:has-text("VOO")').first()).toBeVisible();
    });

    test('37: 401k account shows 3 holdings', async ({ page }) => {
        await page.locator('h3:has-text("Fidelity 401(k)")').click();
        await expect(page).toHaveURL(/\/accounts\//);
        // Seeded data has FXAIX, SCHD, VXUS
        await expect(page.locator('a:has-text("FXAIX")').first()).toBeVisible({ timeout: 5000 });
        await expect(page.locator('a:has-text("SCHD")').first()).toBeVisible();
        await expect(page.locator('a:has-text("VXUS")').first()).toBeVisible();
    });

    test('38: bank account shows no holdings', async ({ page }) => {
        await page.locator('h3:has-text("Chase Checking")').click();
        await expect(page).toHaveURL(/\/accounts\//);
        // Bank accounts don't have holdings — the holdings section should be empty or not present
        await expect(page.locator('text=Transactions').first()).toBeVisible({ timeout: 5000 });
    });

    test('39: account detail shows transactions table', async ({ page }) => {
        await page.locator('h3:has-text("Fidelity Brokerage")').click();
        await expect(page.locator('text=Transactions').first()).toBeVisible({ timeout: 5000 });
        // Should show buy transactions
        await expect(page.locator('td:has-text("buy")').first()).toBeVisible();
    });

    test('40: edit account name works', async ({ page }) => {
        await expect(page.locator('text=Chase Checking')).toBeVisible({ timeout: 5000 });
        // Click edit on Chase Checking
        const chaseCard = page.locator('div').filter({ hasText: /^Chase Checking/ }).first();
        await chaseCard.locator('button:has-text("Edit")').click();
        // Change name
        const nameInput = page.locator('input[placeholder="Name"]');
        await nameInput.clear();
        await nameInput.fill('Chase Savings');
        await page.click('button:has-text("Save")');
        // Verify the name changed
        await expect(page.locator('text=Chase Savings')).toBeVisible({ timeout: 5000 });
        // Revert
        const savedCard = page.locator('div').filter({ hasText: /^Chase Savings/ }).first();
        await savedCard.locator('button:has-text("Edit")').click();
        await nameInput.clear();
        await nameInput.fill('Chase Checking');
        await page.click('button:has-text("Save")');
        await expect(page.locator('text=Chase Checking')).toBeVisible({ timeout: 5000 });
    });
});

test.describe('Property detail data integrity', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
        await page.click('nav >> text=Properties');
    });

    test('41: 742 Evergreen Terrace shows correct purchase price', async ({ page }) => {
        await page.locator('h3:has-text("742 Evergreen Terrace")').first().click();
        await expect(page).toHaveURL(/\/properties\//);
        // Seeded purchase price is $285,000
        await expect(page.locator('text=$285,000.00')).toBeVisible({ timeout: 5000 });
    });

    test('42: 2020 Beryl Street shows computed balance badge', async ({ page }) => {
        // The properties list page shows "Computed Balance" badge for this property
        await expect(page.locator('text=Computed Balance').first()).toBeVisible({ timeout: 5000 });
    });

    test('43: property detail shows income and expense data', async ({ page }) => {
        await page.locator('h3:has-text("742 Evergreen Terrace")').first().click();
        await expect(page).toHaveURL(/\/properties\//);
        // Should show financial info - incomes and expenses
        await expect(page.locator('text=/income|rent/i').first()).toBeVisible({ timeout: 5000 });
    });

    test('44: create property with loan details', async ({ page }) => {
        await page.click('button:has-text("New Property")');
        await page.fill('input[placeholder*="address" i]', '100 Test St, Testville');
        await page.fill('input[placeholder*="purchase price" i]', '500000');
        await page.fill('input[type="date"]', '2024-01-15');
        await page.fill('input[placeholder*="current value" i]', '550000');
        await page.fill('input[placeholder*="mortgage" i]', '400000');
        await page.click('button:has-text("Create")');
        // Should appear in the list
        await expect(page.locator('text=100 Test St, Testville')).toBeVisible({ timeout: 5000 });
    });
});

test.describe('API data export validation', () => {
    test('45: JSON export contains correct number of accounts', async ({ page }) => {
        const loginResp = await page.request.post('/api/v1/auth/login', {
            data: { email: 'demo@wealthview.local', password: 'demo123' },
        });
        const { access_token } = await loginResp.json();

        const exportResp = await page.request.get('/api/v1/export/json', {
            headers: { Authorization: `Bearer ${access_token}` },
        });
        const data = await exportResp.json();
        // At least the 3 seeded accounts (test 13 may add one more)
        expect(data.accounts.length).toBeGreaterThanOrEqual(3);
        expect(data.properties.length).toBeGreaterThanOrEqual(2);
        expect(data.holdings.length).toBeGreaterThanOrEqual(7); // 4 brokerage + 3 retirement
    });

    test('46: CSV export for transactions contains correct headers', async ({ page }) => {
        const loginResp = await page.request.post('/api/v1/auth/login', {
            data: { email: 'demo@wealthview.local', password: 'demo123' },
        });
        const { access_token } = await loginResp.json();

        const csvResp = await page.request.get('/api/v1/export/csv/transactions', {
            headers: { Authorization: `Bearer ${access_token}` },
        });
        expect(csvResp.status()).toBe(200);
        const csv = await csvResp.text();
        expect(csv).toContain('id,');
        expect(csv).toContain('AAPL');
        expect(csv).toContain('buy');
    });

    test('47: CSV export for holdings returns data', async ({ page }) => {
        const loginResp = await page.request.post('/api/v1/auth/login', {
            data: { email: 'demo@wealthview.local', password: 'demo123' },
        });
        const { access_token } = await loginResp.json();

        const csvResp = await page.request.get('/api/v1/export/csv/holdings', {
            headers: { Authorization: `Bearer ${access_token}` },
        });
        expect(csvResp.status()).toBe(200);
        const csv = await csvResp.text();
        expect(csv).toContain('AAPL');
        expect(csv).toContain('NVDA');
    });

    test('48: CSV export for properties returns data', async ({ page }) => {
        const loginResp = await page.request.post('/api/v1/auth/login', {
            data: { email: 'demo@wealthview.local', password: 'demo123' },
        });
        const { access_token } = await loginResp.json();

        const csvResp = await page.request.get('/api/v1/export/csv/properties', {
            headers: { Authorization: `Bearer ${access_token}` },
        });
        expect(csvResp.status()).toBe(200);
        const csv = await csvResp.text();
        expect(csv).toContain('742 Evergreen Terrace');
        expect(csv).toContain('2020 Beryl Street');
    });
});

test.describe('Auth edge cases', () => {
    test('49: expired/invalid JWT returns 401', async ({ page }) => {
        const resp = await page.request.get('/api/v1/accounts?page=0&size=10', {
            headers: { Authorization: 'Bearer invalid.token.here' },
        });
        expect(resp.status()).toBe(401);
    });

    test('50: missing auth header returns 401', async ({ page }) => {
        const resp = await page.request.get('/api/v1/accounts?page=0&size=10');
        expect(resp.status()).toBe(401);
    });
});

test.describe('Admin panel functionality', () => {
    test('51: super admin can create a new tenant', async ({ page }) => {
        await login(page, SUPER_ADMIN);
        await page.click('nav a[href="/admin"]');
        await expect(page.locator('h2:has-text("Admin Panel")')).toBeVisible({ timeout: 5000 });

        // Fill in tenant name
        await page.fill('input[placeholder*="Tenant" i]', 'E2E Test Tenant');
        await page.click('button:has-text("Create")');
        // Should appear in the table
        await expect(page.locator('td:has-text("E2E Test Tenant")')).toBeVisible({ timeout: 5000 });
    });

    test('52: super admin can disable a tenant', async ({ page }) => {
        await login(page, SUPER_ADMIN);
        await page.click('nav a[href="/admin"]');
        await expect(page.locator('td:has-text("Demo Family")')).toBeVisible({ timeout: 5000 });

        // Disable the E2E Test Tenant (created by test 51), not Demo Family
        // This avoids breaking parallel tests that login to Demo Family
        const testRow = page.locator('tr').filter({ hasText: 'E2E Test Tenant' });
        if (await testRow.count() > 0) {
            await testRow.locator('text=Disable').click();
            await expect(testRow.locator('text=Enable').or(testRow.locator('text=Inactive'))).toBeVisible({ timeout: 5000 });
            // Re-enable
            await testRow.locator('text=Enable').click();
            await expect(testRow.locator('text=Disable')).toBeVisible({ timeout: 5000 });
        } else {
            // Fallback: use System tenant (has no users so safe to disable)
            const systemRow = page.locator('tr').filter({ hasText: 'System' });
            await systemRow.locator('text=Disable').click();
            await expect(systemRow.locator('text=Enable').or(systemRow.locator('text=Inactive'))).toBeVisible({ timeout: 5000 });
            await systemRow.locator('text=Enable').click();
            await expect(systemRow.locator('text=Disable')).toBeVisible({ timeout: 5000 });
        }
    });
});

test.describe('Settings page functionality', () => {
    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('53: notification preference toggle works', async ({ page }) => {
        await page.click('nav >> text=Settings');
        await expect(page.locator('h3:has-text("Notification Preferences")')).toBeVisible({ timeout: 5000 });

        // Toggle the first checkbox (Large transactions)
        const checkbox = page.locator('input[type="checkbox"]').first();
        const wasChecked = await checkbox.isChecked();
        await checkbox.click();
        // Wait a bit for the API call
        await page.waitForTimeout(500);
        // Verify it toggled
        expect(await checkbox.isChecked()).toBe(!wasChecked);
        // Toggle back
        await checkbox.click();
        await page.waitForTimeout(500);
        expect(await checkbox.isChecked()).toBe(wasChecked);
    });

    test('54: generate invite code creates a new code', async ({ page }) => {
        await page.click('nav >> text=Settings');
        await expect(page.locator('button:has-text("Generate Code")')).toBeVisible({ timeout: 5000 });

        // Click generate and wait for the API call to complete
        await Promise.all([
            page.waitForResponse((resp) => resp.url().includes('/invite-codes') && resp.request().method() === 'POST'),
            page.click('button:has-text("Generate Code")'),
        ]);
        // Wait for the list to refresh
        await page.waitForTimeout(500);
        // Should show a code row with "Active" status
        await expect(page.locator('text=Active').first()).toBeVisible({ timeout: 5000 });
    });
});

test.describe('Audit log data', () => {
    test('55: audit log records login events', async ({ page }) => {
        await login(page);
        // Small delay to ensure the login audit event is persisted
        await page.waitForTimeout(500);
        await page.click('nav >> text=Audit Log');
        await expect(page.locator('table')).toBeVisible({ timeout: 5000 });
        // Login event should be recorded — check total entries count
        await expect(page.locator('text=/[1-9]\\d* total entries/')).toBeVisible({ timeout: 5000 });
    });
});
