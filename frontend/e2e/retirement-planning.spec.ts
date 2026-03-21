import { test, expect, type Page } from '@playwright/test';
import { login, registerUser, parseCurrency, waitForProjection } from './helpers';

// ─── Shared utilities ────────────────────────────────────────────────

const ts = Date.now();

/** Login a user via the UI login page. */
async function loginAsUser(page: Page, email: string, password: string) {
    await login(page, { email, password });
}

/** Helper to fill a labelled input (finds label, then sibling input). */
async function fillField(page: Page, labelText: string, value: string) {
    const label = page.locator(`label:has-text("${labelText}")`).first();
    const container = label.locator('..');
    const input = container.locator('input, select').first();
    await input.fill(value);
}

/** Helper to select a dropdown option by label text. */
async function selectField(page: Page, labelText: string, value: string) {
    const label = page.locator(`label:has-text("${labelText}")`).first();
    const container = label.locator('..');
    const select = container.locator('select').first();
    await select.selectOption(value);
}

/** Click a strategy/option card that contains the given text. */
async function clickCard(page: Page, text: string) {
    await page.locator('div').filter({ hasText: new RegExp(`^${text}`) }).first().click();
}

/** Check an income source checkbox by finding the container with the source name. */
async function checkIncomeSource(page: Page, name: string) {
    // Structure: div(border container) > div(flex) > [checkbox, div > strong(name)]
    // Find the strong with the name, go up to the flex container, then find the checkbox
    const strong = page.locator(`strong:has-text("${name}")`).first();
    const flexRow = strong.locator('..').locator('..');
    await flexRow.locator('input[type="checkbox"]').check();
}

/** Assert cross-cutting projection results are visible. */
async function assertProjectionCrosscuts(page: Page) {
    await expect(page.locator('text=Final Balance').first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=Years in Retirement').first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=Peak Balance').first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator('button:has-text("Balance Over Time")')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('button:has-text("Annual Flows")')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('button:has-text("Data Table")')).toBeVisible({ timeout: 5000 });
}

/** Assert state tax cards based on whether the state has income tax. */
async function assertStateTax(page: Page, hasIncomeTax: boolean) {
    if (hasIncomeTax) {
        await expect(page.locator('text=Lifetime Tax').first()).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Avg Effective Rate').first()).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Total State Tax').first()).toBeVisible({ timeout: 5000 });
    } else {
        await expect(page.locator('text=Total State Tax')).not.toBeVisible({ timeout: 3000 });
    }
}

/** Get text content from a summary card value by its label. */
async function getCardValue(page: Page, label: string): Promise<string> {
    const card = page.locator(`text=${label}`).first().locator('..').locator('div').nth(1);
    return (await card.textContent()) ?? '';
}

// ═════════════════════════════════════════════════════════════════════
// USER 1: "Comfortable Couple" — Sustainable Retirement (Success Case)
// ═════════════════════════════════════════════════════════════════════

test.describe.serial('User 1: Comfortable Couple — Sustainable Retirement', () => {
    let token: string;
    const userEmail = `couple-${ts}@e2e.local`;
    const userPassword = 'testpass123';

    test('1.1 Register user via API', async ({ page }) => {
        token = await registerUser(page, userEmail, userPassword);
        expect(token).toBeTruthy();
    });

    test('1.2 Create 3 investment accounts via UI', async ({ page }) => {
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Accounts');
        await expect(page).toHaveURL('/accounts', { timeout: 5000 });

        // Account 1: Traditional 401k
        await page.click('button:has-text("New Account")');
        await page.fill('input[placeholder="Name"]', 'Couple Traditional 401k');
        await page.locator('select').first().selectOption('401k');
        await page.fill('input[placeholder="Institution"]', 'Fidelity');
        await page.click('button:has-text("Create")');
        await expect(page.locator('text=Couple Traditional 401k').first()).toBeVisible({ timeout: 5000 });

        // Account 2: Roth IRA
        await page.click('button:has-text("New Account")');
        await page.fill('input[placeholder="Name"]', 'Couple Roth IRA');
        await page.locator('select').first().selectOption('roth');
        await page.fill('input[placeholder="Institution"]', 'Fidelity');
        await page.click('button:has-text("Create")');
        await expect(page.locator('text=Couple Roth IRA').first()).toBeVisible({ timeout: 5000 });

        // Account 3: Taxable Brokerage
        await page.click('button:has-text("New Account")');
        await page.fill('input[placeholder="Name"]', 'Couple Taxable Brokerage');
        await page.locator('select').first().selectOption('brokerage');
        await page.fill('input[placeholder="Institution"]', 'Schwab');
        await page.click('button:has-text("Create")');
        await expect(page.locator('text=Couple Taxable Brokerage').first()).toBeVisible({ timeout: 5000 });

        // Assert all 3 visible
        await expect(page.locator('text=Couple Traditional 401k').first()).toBeVisible();
        await expect(page.locator('text=Couple Roth IRA').first()).toBeVisible();
        await expect(page.locator('text=Couple Taxable Brokerage').first()).toBeVisible();
    });

    test('1.3 Create investment property via UI', async ({ page }) => {
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Properties');
        await expect(page).toHaveURL('/properties', { timeout: 5000 });

        await page.click('button:has-text("New Property")');

        // Basic fields
        await page.fill('input[placeholder="123 Main St"]', '456 Rental Way, San Diego, CA');

        // Purchase Price — CurrencyInput is type="text" with inputMode="decimal"
        const purchasePrice = page.locator('label:has-text("Purchase Price")').locator('..').locator('input');
        await purchasePrice.click();
        await purchasePrice.fill('500000');

        const purchaseDate = page.locator('label:has-text("Purchase Date")').locator('..').locator('input');
        await purchaseDate.fill('2020-01-15');

        const currentValue = page.locator('label:has-text("Current Value")').locator('..').locator('input');
        await currentValue.click();
        await currentValue.fill('620000');

        const mortgageBalance = page.locator('label:has-text("Mortgage Balance")').locator('..').locator('input');
        await mortgageBalance.click();
        await mortgageBalance.fill('350000');

        // Property Type
        await page.locator('label:has-text("Property Type")').locator('..').locator('select').selectOption('investment');

        // Expand Loan Details
        await page.click('button:has-text("Show Loan Details")');
        const loanAmount = page.locator('label:has-text("Loan Amount")').locator('..').locator('input');
        await loanAmount.click();
        await loanAmount.fill('400000');

        const interestRate = page.locator('label:has-text("Annual Interest Rate")').locator('..').locator('input');
        await interestRate.fill('4.5');

        const loanTerm = page.locator('label:has-text("Loan Term")').locator('..').locator('input');
        await loanTerm.fill('360');

        const loanStart = page.locator('label:has-text("Loan Start Date")').locator('..').locator('input');
        await loanStart.fill('2020-01-15');

        // Expand Financial Assumptions
        await page.click('button:has-text("Show Financial Assumptions")');

        const appreciation = page.locator('label:has-text("Annual Appreciation Rate")').locator('..').locator('input');
        await appreciation.fill('3');

        const propTax = page.locator('label:has-text("Annual Property Tax")').locator('..').locator('input');
        await propTax.click();
        await propTax.fill('6250');

        const insurance = page.locator('label:has-text("Annual Insurance Cost")').locator('..').locator('input');
        await insurance.click();
        await insurance.fill('1800');

        const maintenance = page.locator('label:has-text("Annual Maintenance Cost")').locator('..').locator('input');
        await maintenance.click();
        await maintenance.fill('3000');

        // Expand Depreciation
        await page.click('button:has-text("Show Depreciation")');

        await page.locator('label:has-text("Depreciation Method")').locator('..').locator('select').selectOption('straight_line');

        const inServiceDate = page.locator('label:has-text("In-Service Date")').locator('..').locator('input');
        await inServiceDate.fill('2020-01-15');

        const landValue = page.locator('label:has-text("Land Value")').locator('..').locator('input');
        await landValue.click();
        await landValue.fill('200000');

        // Submit
        await page.click('button:has-text("Create")');

        // Assert property appears in list
        await expect(page.locator('text=456 Rental Way, San Diego, CA').first()).toBeVisible({ timeout: 5000 });

        // Click into detail page and verify purchase price
        await page.locator('text=456 Rental Way, San Diego, CA').first().click();
        await expect(page.locator('text=/\\$500,000/').first()).toBeVisible({ timeout: 5000 });
    });

    test('1.4 Create income sources via API', async ({ page }) => {
        // Fetch property ID
        const propertiesResp = await page.request.get('/api/v1/properties', {
            headers: { Authorization: `Bearer ${token}` },
        });
        const properties = await propertiesResp.json();
        const propertyId = properties.find((p: any) => p.address.includes('456 Rental Way'))?.id;
        expect(propertyId).toBeTruthy();

        // Social Security
        const ssResp = await page.request.post('/api/v1/income-sources', {
            headers: { Authorization: `Bearer ${token}` },
            data: {
                name: 'Couple Social Security',
                income_type: 'social_security',
                tax_treatment: 'partially_taxable',
                annual_amount: 42000,
                start_age: 67,
                end_age: null,
                inflation_rate: 0.02,
                one_time: false,
            },
        });
        expect(ssResp.status()).toBe(201);

        // Rental Income
        const rentalResp = await page.request.post('/api/v1/income-sources', {
            headers: { Authorization: `Bearer ${token}` },
            data: {
                name: 'Couple Rental Income',
                income_type: 'rental_property',
                tax_treatment: 'rental_passive',
                annual_amount: 30000,
                start_age: 55,
                end_age: null,
                inflation_rate: 0.02,
                one_time: false,
                property_id: propertyId,
            },
        });
        expect(rentalResp.status()).toBe(201);
    });

    test('1.5 Create spending profile with 3 tiers via UI', async ({ page }) => {
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Spending');
        await expect(page.locator('h2:has-text("Spending Profiles")')).toBeVisible({ timeout: 5000 });

        await page.click('button:has-text("New Profile")');

        // Name
        await page.fill('input[placeholder="Retirement Spending"]', 'Couple Tiered Plan');

        // Essential and Discretionary defaults
        const essential = page.locator('label:has-text("Essential Expenses")').locator('..').locator('input');
        await essential.click();
        await essential.fill('70000');

        const discretionary = page.locator('label:has-text("Discretionary Expenses")').locator('..').locator('input');
        await discretionary.click();
        await discretionary.fill('20000');

        // Add Tier 1: Active (62-74)
        await page.click('button:has-text("+ Add Spending Tier")');
        const phaseNames = page.locator('input[placeholder="e.g., Go-Go Years"]');
        await phaseNames.nth(0).fill('Active');

        const startAges = page.locator('label:has-text("Start Age")').locator('..').locator('input');
        await startAges.nth(0).fill('62');

        const endAges = page.locator('label:has-text("End Age")').locator('..').locator('input');
        await endAges.nth(0).fill('74');

        const tierEssentials = page.locator('label:has-text("Essential (annual)")').locator('..').locator('input');
        await tierEssentials.nth(0).click();
        await tierEssentials.nth(0).fill('80000');

        const tierDiscretionaries = page.locator('label:has-text("Discretionary (annual)")').locator('..').locator('input');
        await tierDiscretionaries.nth(0).click();
        await tierDiscretionaries.nth(0).fill('25000');

        // Add Tier 2: Moderate (75-85)
        await page.click('button:has-text("+ Add Spending Tier")');
        await phaseNames.nth(1).fill('Moderate');
        await startAges.nth(1).fill('75');
        await endAges.nth(1).fill('85');
        await tierEssentials.nth(1).click();
        await tierEssentials.nth(1).fill('65000');
        await tierDiscretionaries.nth(1).click();
        await tierDiscretionaries.nth(1).fill('15000');

        // Add Tier 3: Late (86+)
        await page.click('button:has-text("+ Add Spending Tier")');
        await phaseNames.nth(2).fill('Late');
        await startAges.nth(2).fill('86');
        // End Age blank = forever
        await tierEssentials.nth(2).click();
        await tierEssentials.nth(2).fill('50000');
        await tierDiscretionaries.nth(2).click();
        await tierDiscretionaries.nth(2).fill('10000');

        await page.click('button:has-text("Create Profile")');
        await expect(page.locator('text=Couple Tiered Plan').first()).toBeVisible({ timeout: 5000 });
    });

    test('1.6 Create projection scenario via UI', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await expect(page.locator('h2:has-text("Retirement Projections")')).toBeVisible({ timeout: 5000 });

        await page.click('button:has-text("New Scenario")');

        // Basic fields
        await page.fill('input[placeholder="Retirement Plan"]', 'Couple CA Full Plan');

        const retDate = page.locator('label:has-text("Retirement Date")').locator('..').locator('input');
        await retDate.fill('2033-01-01');

        const birthYear = page.locator('label:has-text("Birth Year")').locator('..').locator('input');
        await birthYear.fill('1971');

        const endAge = page.locator('label:has-text("End Age")').locator('..').locator('input');
        await endAge.clear();
        await endAge.fill('92');

        const inflation = page.locator('label:has-text("Inflation Rate")').locator('..').locator('input');
        await inflation.clear();
        await inflation.fill('3');

        const withdrawal = page.locator('label:has-text("Withdrawal Rate")').locator('..').locator('input');
        await withdrawal.clear();
        await withdrawal.fill('4');

        // Spending Plan
        const spendingSelect = page.locator('label:has-text("Spending Plan")').locator('..').locator('select');
        await spendingSelect.selectOption({ label: 'Couple Tiered Plan' });

        // Income Sources — check both
        await checkIncomeSource(page, 'Couple Social Security');
        await checkIncomeSource(page, 'Couple Rental Income');

        // Withdrawal Strategy: Fixed Percentage
        await clickCard(page, 'Fixed Percentage');

        // Withdrawal Order: Taxable First
        await clickCard(page, 'Taxable First');

        // Roth Conversion: Fixed Amount (default)
        await clickCard(page, 'Fixed Amount');

        // Tax Configuration
        await selectField(page, 'State', 'CA');

        // Wait for conditional fields to appear
        const propTax = page.locator('label:has-text("Primary Residence Property Tax")').locator('..').locator('input');
        await propTax.click();
        await propTax.fill('9000');

        const mortgageInt = page.locator('label:has-text("Primary Residence Mortgage Interest")').locator('..').locator('input');
        await mortgageInt.click();
        await mortgageInt.fill('10000');

        // Accounts — the first one exists by default
        const accountTypes = page.locator('label:has-text("Account Type")').locator('..').locator('select');
        await accountTypes.nth(0).selectOption('traditional');

        const balances = page.locator('label:has-text("Initial Balance")').locator('..').locator('input');
        await balances.nth(0).click();
        await balances.nth(0).fill('800000');

        const contributions = page.locator('label:has-text("Annual Contribution")').locator('..').locator('input');
        await contributions.nth(0).click();
        await contributions.nth(0).fill('20000');

        const returns = page.locator('label:has-text("Expected Return")').locator('..').locator('input');
        await returns.nth(0).clear();
        await returns.nth(0).fill('7');

        // Add Account 2: Roth
        await page.click('button:has-text("+ Add Account")');
        await accountTypes.nth(1).selectOption('roth');
        await balances.nth(1).click();
        await balances.nth(1).fill('250000');
        await contributions.nth(1).click();
        await contributions.nth(1).fill('7000');
        await returns.nth(1).clear();
        await returns.nth(1).fill('7');

        // Add Account 3: Taxable
        await page.click('button:has-text("+ Add Account")');
        await accountTypes.nth(2).selectOption('taxable');
        await balances.nth(2).click();
        await balances.nth(2).fill('150000');
        await contributions.nth(2).click();
        await contributions.nth(2).fill('5000');
        await returns.nth(2).clear();
        await returns.nth(2).fill('7');

        await page.click('button:has-text("Create Scenario")');
        await expect(page.locator('text=Couple CA Full Plan').first()).toBeVisible({ timeout: 10000 });
    });

    test('1.7 Run projection and verify SUCCESS', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.locator('text=Couple CA Full Plan').first().click();
        await expect(page.locator('h2:has-text("Couple CA Full Plan")')).toBeVisible({ timeout: 5000 });

        await page.click('button:has-text("Run Projection")');
        await waitForProjection(page);

        // Cross-cutting assertions
        await assertProjectionCrosscuts(page);

        // SUCCESS outcome
        await expect(page.locator('text=Fully Sustainable').first()).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Spending Shortfall Detected')).not.toBeVisible({ timeout: 3000 });

        // Final balance > 0
        const finalBalanceText = await getCardValue(page, 'Final Balance');
        expect(parseCurrency(finalBalanceText)).toBeGreaterThan(0);

        // State tax assertions (CA has income tax)
        await assertStateTax(page, true);

        // SALT card
        await expect(page.locator('text=SALT Claimed').first()).toBeVisible({ timeout: 5000 });

        // Avg Effective Rate shows a percentage
        const avgRate = await getCardValue(page, 'Avg Effective Rate');
        expect(avgRate).toMatch(/%/);
    });

    test('1.8 Verify tabs and CSV download', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.locator('text=Couple CA Full Plan').first().click();

        // Re-run projection since results are not persisted across page loads
        await page.click('button:has-text("Run Projection")');
        await waitForProjection(page);

        // Verify tab bar is present
        await expect(page.locator('button:has-text("Balance Over Time")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Data Table")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Spending Analysis")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Income Streams")')).toBeVisible({ timeout: 5000 });

        // Data Table tab + CSV download
        await page.click('button:has-text("Data Table")');
        const [download] = await Promise.all([
            page.waitForEvent('download'),
            page.click('button:has-text("Download CSV")'),
        ]);
        expect(download.suggestedFilename()).toMatch(/\.csv$/);

        // Balance Over Time tab — verify chart renders
        await page.click('button:has-text("Balance Over Time")');
        await expect(page.locator('svg').first()).toBeVisible({ timeout: 5000 });
    });

    test('1.9 Run comparison: CA vs Federal-only', async ({ page }) => {
        test.slow();

        // Create second scenario via API (no state)
        const scenarioResp = await page.request.post('/api/v1/projections', {
            headers: { Authorization: `Bearer ${token}` },
            data: {
                name: 'Couple Federal Only',
                retirement_date: '2033-01-01',
                birth_year: 1971,
                end_age: 92,
                inflation_rate: 0.03,
                withdrawal_rate: 0.04,
                withdrawal_strategy: 'fixed_percentage',
                withdrawal_order: 'taxable_first',
                roth_conversion_strategy: 'fixed_amount',
                annual_roth_conversion: 0,
                accounts: [
                    { initial_balance: 800000, annual_contribution: 20000, expected_return: 0.07, account_type: 'traditional' },
                    { initial_balance: 250000, annual_contribution: 7000, expected_return: 0.07, account_type: 'roth' },
                    { initial_balance: 150000, annual_contribution: 5000, expected_return: 0.07, account_type: 'taxable' },
                ],
            },
        });
        expect(scenarioResp.status()).toBe(201);
        const fedScenario = await scenarioResp.json();

        // Fetch the CA scenario ID
        const listResp = await page.request.get('/api/v1/projections', {
            headers: { Authorization: `Bearer ${token}` },
        });
        const scenarios = await listResp.json();
        const caScenario = scenarios.find((s: any) => s.name === 'Couple CA Full Plan');

        // Run both projections via API (GET endpoint)
        await page.request.get(`/api/v1/projections/${caScenario.id}/run`, {
            headers: { Authorization: `Bearer ${token}` },
        });
        await page.request.get(`/api/v1/projections/${fedScenario.id}/run`, {
            headers: { Authorization: `Bearer ${token}` },
        });

        // Navigate to compare page
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.click('a:has-text("Compare")');
        await expect(page).toHaveURL('/projections/compare', { timeout: 5000 });

        // Select both scenarios
        const selects = page.locator('select');
        await selects.nth(0).selectOption({ label: 'Couple CA Full Plan' });
        await selects.nth(1).selectOption({ label: 'Couple Federal Only' });

        await page.click('button:has-text("Compare")');

        // Assert both scenario names visible in results table headers
        await expect(page.locator('th:has-text("Couple CA Full Plan")')).toBeVisible({ timeout: 10000 });
        await expect(page.locator('th:has-text("Couple Federal Only")')).toBeVisible({ timeout: 5000 });
    });
});

// ═════════════════════════════════════════════════════════════════════
// USER 2: "Strategic Optimizer" — Roth Conversion Tax Impact
// ═════════════════════════════════════════════════════════════════════

test.describe.serial('User 2: Strategic Optimizer — Roth Conversion Tax Impact', () => {
    let token: string;
    const userEmail = `optimizer-${ts}@e2e.local`;
    const userPassword = 'testpass123';
    let scenarioALifetimeTax = 0;
    let scenarioBLifetimeTax = 0;

    test('2.1 Register user via API', async ({ page }) => {
        token = await registerUser(page, userEmail, userPassword);
        expect(token).toBeTruthy();
    });

    test('2.2 Create 2 accounts via UI', async ({ page }) => {
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Accounts');
        await expect(page).toHaveURL('/accounts', { timeout: 5000 });

        // Account 1: Traditional IRA
        await page.click('button:has-text("New Account")');
        await page.fill('input[placeholder="Name"]', 'Optimizer Traditional');
        await page.locator('select').first().selectOption('ira');
        await page.fill('input[placeholder="Institution"]', 'Fidelity');
        await page.click('button:has-text("Create")');
        await expect(page.locator('text=Optimizer Traditional').first()).toBeVisible({ timeout: 5000 });

        // Account 2: Roth IRA
        await page.click('button:has-text("New Account")');
        await page.fill('input[placeholder="Name"]', 'Optimizer Roth');
        await page.locator('select').first().selectOption('roth');
        await page.fill('input[placeholder="Institution"]', 'Vanguard');
        await page.click('button:has-text("Create")');
        await expect(page.locator('text=Optimizer Roth').first()).toBeVisible({ timeout: 5000 });
    });

    test('2.3 Create income sources via API', async ({ page }) => {
        // Consulting income
        const consultingResp = await page.request.post('/api/v1/income-sources', {
            headers: { Authorization: `Bearer ${token}` },
            data: {
                name: 'Optimizer Consulting',
                income_type: 'part_time_work',
                tax_treatment: 'self_employment',
                annual_amount: 60000,
                start_age: 55,
                end_age: 62,
                inflation_rate: 0.02,
                one_time: false,
            },
        });
        expect(consultingResp.status()).toBe(201);

        // Social Security
        const ssResp = await page.request.post('/api/v1/income-sources', {
            headers: { Authorization: `Bearer ${token}` },
            data: {
                name: 'Optimizer Social Security',
                income_type: 'social_security',
                tax_treatment: 'partially_taxable',
                annual_amount: 30000,
                start_age: 70,
                end_age: null,
                inflation_rate: 0.02,
                one_time: false,
            },
        });
        expect(ssResp.status()).toBe(201);
    });

    test('2.4 Create Scenario A: Oregon + Fill-Bracket Roth Conversion via UI', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.click('button:has-text("New Scenario")');

        await page.fill('input[placeholder="Retirement Plan"]', 'With Roth Conversion');

        const retDate = page.locator('label:has-text("Retirement Date")').locator('..').locator('input');
        await retDate.fill('2031-01-01');

        const birthYear = page.locator('label:has-text("Birth Year")').locator('..').locator('input');
        await birthYear.fill('1976');

        const endAge = page.locator('label:has-text("End Age")').locator('..').locator('input');
        await endAge.clear();
        await endAge.fill('95');

        const inflation = page.locator('label:has-text("Inflation Rate")').locator('..').locator('input');
        await inflation.clear();
        await inflation.fill('2.5');

        const withdrawal = page.locator('label:has-text("Withdrawal Rate")').locator('..').locator('input');
        await withdrawal.clear();
        await withdrawal.fill('3.5');

        // Income Sources
        await checkIncomeSource(page, 'Optimizer Consulting');
        await checkIncomeSource(page, 'Optimizer Social Security');

        // Withdrawal Strategy: Fixed Percentage
        await clickCard(page, 'Fixed Percentage');

        // Withdrawal Order: Traditional First
        await clickCard(page, 'Traditional First');

        // Roth Conversion: Fill Tax Bracket
        await clickCard(page, 'Fill Tax Bracket');

        // Target Bracket: 22%
        const targetBracket = page.locator('label:has-text("Target Tax Bracket")').locator('..').locator('select');
        await targetBracket.selectOption('22');

        // Filing Status
        const filingStatus = page.locator('label:has-text("Filing Status")').locator('..').locator('select');
        await filingStatus.selectOption('single');

        // State: Oregon
        await selectField(page, 'State', 'OR');

        const propTax = page.locator('label:has-text("Primary Residence Property Tax")').locator('..').locator('input');
        await propTax.click();
        await propTax.fill('4500');

        const mortgageInt = page.locator('label:has-text("Primary Residence Mortgage Interest")').locator('..').locator('input');
        await mortgageInt.click();
        await mortgageInt.fill('8000');

        // Account 1: Traditional $1.5M
        const accountTypes = page.locator('label:has-text("Account Type")').locator('..').locator('select');
        await accountTypes.nth(0).selectOption('traditional');

        const balances = page.locator('label:has-text("Initial Balance")').locator('..').locator('input');
        await balances.nth(0).click();
        await balances.nth(0).fill('1500000');

        const contributions = page.locator('label:has-text("Annual Contribution")').locator('..').locator('input');
        await contributions.nth(0).click();
        await contributions.nth(0).fill('0');

        const returns = page.locator('label:has-text("Expected Return")').locator('..').locator('input');
        await returns.nth(0).clear();
        await returns.nth(0).fill('7');

        // Account 2: Roth $100k
        await page.click('button:has-text("+ Add Account")');
        await accountTypes.nth(1).selectOption('roth');
        await balances.nth(1).click();
        await balances.nth(1).fill('100000');
        await contributions.nth(1).click();
        await contributions.nth(1).fill('0');
        await returns.nth(1).clear();
        await returns.nth(1).fill('7');

        await page.click('button:has-text("Create Scenario")');
        await expect(page.locator('text=With Roth Conversion').first()).toBeVisible({ timeout: 10000 });
    });

    test('2.5 Run Scenario A, capture lifetime tax', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.locator('text=With Roth Conversion').first().click();

        await page.click('button:has-text("Run Projection")');
        await waitForProjection(page);

        await assertProjectionCrosscuts(page);
        await assertStateTax(page, true);

        // Capture lifetime tax
        const lifetimeTaxText = await getCardValue(page, 'Lifetime Tax');
        scenarioALifetimeTax = parseCurrency(lifetimeTaxText);
        expect(scenarioALifetimeTax).toBeGreaterThan(0);

        // Verify Roth conversions in Data Table — "Conversion" column header visible
        await page.click('button:has-text("Data Table")');
        await expect(page.locator('text=Conversion').first()).toBeVisible({ timeout: 5000 });
    });

    test('2.6 Create Scenario B: Oregon + No Roth Conversion via UI', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.click('button:has-text("New Scenario")');

        await page.fill('input[placeholder="Retirement Plan"]', 'Without Roth Conversion');

        const retDate = page.locator('label:has-text("Retirement Date")').locator('..').locator('input');
        await retDate.fill('2031-01-01');

        const birthYear = page.locator('label:has-text("Birth Year")').locator('..').locator('input');
        await birthYear.fill('1976');

        const endAge = page.locator('label:has-text("End Age")').locator('..').locator('input');
        await endAge.clear();
        await endAge.fill('95');

        const inflation = page.locator('label:has-text("Inflation Rate")').locator('..').locator('input');
        await inflation.clear();
        await inflation.fill('2.5');

        const withdrawal = page.locator('label:has-text("Withdrawal Rate")').locator('..').locator('input');
        await withdrawal.clear();
        await withdrawal.fill('3.5');

        // Income Sources
        await checkIncomeSource(page, 'Optimizer Consulting');
        await checkIncomeSource(page, 'Optimizer Social Security');

        // Withdrawal Strategy: Fixed Percentage
        await clickCard(page, 'Fixed Percentage');

        // Withdrawal Order: Traditional First
        await clickCard(page, 'Traditional First');

        // Roth Conversion: Fixed Amount ($0 — no conversion)
        await clickCard(page, 'Fixed Amount');

        // State: Oregon
        await selectField(page, 'State', 'OR');

        const propTax = page.locator('label:has-text("Primary Residence Property Tax")').locator('..').locator('input');
        await propTax.click();
        await propTax.fill('4500');

        const mortgageInt = page.locator('label:has-text("Primary Residence Mortgage Interest")').locator('..').locator('input');
        await mortgageInt.click();
        await mortgageInt.fill('8000');

        // Account 1: Traditional $1.5M
        const accountTypes = page.locator('label:has-text("Account Type")').locator('..').locator('select');
        await accountTypes.nth(0).selectOption('traditional');

        const balances = page.locator('label:has-text("Initial Balance")').locator('..').locator('input');
        await balances.nth(0).click();
        await balances.nth(0).fill('1500000');

        const contributions = page.locator('label:has-text("Annual Contribution")').locator('..').locator('input');
        await contributions.nth(0).click();
        await contributions.nth(0).fill('0');

        const returns = page.locator('label:has-text("Expected Return")').locator('..').locator('input');
        await returns.nth(0).clear();
        await returns.nth(0).fill('7');

        // Account 2: Roth $100k
        await page.click('button:has-text("+ Add Account")');
        await accountTypes.nth(1).selectOption('roth');
        await balances.nth(1).click();
        await balances.nth(1).fill('100000');
        await contributions.nth(1).click();
        await contributions.nth(1).fill('0');
        await returns.nth(1).clear();
        await returns.nth(1).fill('7');

        await page.click('button:has-text("Create Scenario")');
        await expect(page.locator('text=Without Roth Conversion').first()).toBeVisible({ timeout: 10000 });
    });

    test('2.7 Run Scenario B, compare lifetime tax', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.locator('text=Without Roth Conversion').first().click();

        await page.click('button:has-text("Run Projection")');
        await waitForProjection(page);

        await assertProjectionCrosscuts(page);

        // Capture lifetime tax
        const lifetimeTaxText = await getCardValue(page, 'Lifetime Tax');
        scenarioBLifetimeTax = parseCurrency(lifetimeTaxText);
        expect(scenarioBLifetimeTax).toBeGreaterThan(0);

        // Roth conversion should reduce lifetime tax
        expect(scenarioALifetimeTax).toBeLessThan(scenarioBLifetimeTax);
    });

    test('2.8 Compare scenarios', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.click('a:has-text("Compare")');

        const selects = page.locator('select');
        await selects.nth(0).selectOption({ label: 'With Roth Conversion' });
        await selects.nth(1).selectOption({ label: 'Without Roth Conversion' });

        await page.click('button:has-text("Compare")');

        await expect(page.locator('th:has-text("With Roth Conversion")')).toBeVisible({ timeout: 10000 });
        await expect(page.locator('th:has-text("Without Roth Conversion")')).toBeVisible({ timeout: 5000 });
    });
});

// ═════════════════════════════════════════════════════════════════════
// USER 3: "Underfunded Early Retiree" — Failure and Recovery
// ═════════════════════════════════════════════════════════════════════

test.describe.serial('User 3: Underfunded Early Retiree — Failure and Recovery', () => {
    let token: string;
    const userEmail = `retiree-${ts}@e2e.local`;
    const userPassword = 'testpass123';

    test('3.1 Register user via API', async ({ page }) => {
        token = await registerUser(page, userEmail, userPassword);
        expect(token).toBeTruthy();
    });

    test('3.2 Create 1 account via UI', async ({ page }) => {
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Accounts');

        await page.click('button:has-text("New Account")');
        await page.fill('input[placeholder="Name"]', 'Retiree Traditional');
        await page.locator('select').first().selectOption('ira');
        await page.fill('input[placeholder="Institution"]', 'Vanguard');
        await page.click('button:has-text("Create")');
        await expect(page.locator('text=Retiree Traditional').first()).toBeVisible({ timeout: 5000 });
    });

    test('3.3 Create income sources via API', async ({ page }) => {
        const resp = await page.request.post('/api/v1/income-sources', {
            headers: { Authorization: `Bearer ${token}` },
            data: {
                name: 'Retiree Pension',
                income_type: 'pension',
                tax_treatment: 'taxable',
                annual_amount: 12000,
                start_age: 50,
                end_age: null,
                inflation_rate: 0,
                one_time: false,
            },
        });
        expect(resp.status()).toBe(201);
    });

    test('3.4 Create over-aggressive spending profile via UI', async ({ page }) => {
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Spending');
        await expect(page.locator('h2:has-text("Spending Profiles")')).toBeVisible({ timeout: 5000 });

        await page.click('button:has-text("New Profile")');

        await page.fill('input[placeholder="Retirement Spending"]', 'High Spending Plan');

        const essential = page.locator('label:has-text("Essential Expenses")').locator('..').locator('input');
        await essential.click();
        await essential.fill('50000');

        const discretionary = page.locator('label:has-text("Discretionary Expenses")').locator('..').locator('input');
        await discretionary.click();
        await discretionary.fill('20000');

        await page.click('button:has-text("Create Profile")');
        await expect(page.locator('text=High Spending Plan').first()).toBeVisible({ timeout: 5000 });
    });

    test('3.5 Create Scenario A: Underfunded plan, Nevada', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.click('button:has-text("New Scenario")');

        await page.fill('input[placeholder="Retirement Plan"]', 'Underfunded Plan');

        const retDate = page.locator('label:has-text("Retirement Date")').locator('..').locator('input');
        await retDate.fill('2031-01-01');

        const birthYear = page.locator('label:has-text("Birth Year")').locator('..').locator('input');
        await birthYear.fill('1981');

        const endAge = page.locator('label:has-text("End Age")').locator('..').locator('input');
        await endAge.clear();
        await endAge.fill('90');

        const inflation = page.locator('label:has-text("Inflation Rate")').locator('..').locator('input');
        await inflation.clear();
        await inflation.fill('3');

        const withdrawal = page.locator('label:has-text("Withdrawal Rate")').locator('..').locator('input');
        await withdrawal.clear();
        await withdrawal.fill('4');

        // Spending Plan
        const spendingSelect = page.locator('label:has-text("Spending Plan")').locator('..').locator('select');
        await spendingSelect.selectOption({ label: 'High Spending Plan' });

        // Income Sources
        await checkIncomeSource(page, 'Retiree Pension');

        // Withdrawal Strategy: Fixed Percentage
        await clickCard(page, 'Fixed Percentage');

        // Withdrawal Order: Taxable First
        await clickCard(page, 'Taxable First');

        // Roth Conversion: Fixed Amount
        await clickCard(page, 'Fixed Amount');

        // State: Nevada (no income tax)
        await selectField(page, 'State', 'NV');

        const propTax = page.locator('label:has-text("Primary Residence Property Tax")').locator('..').locator('input');
        await propTax.click();
        await propTax.fill('2000');

        // Account: Traditional $200k
        const accountTypes = page.locator('label:has-text("Account Type")').locator('..').locator('select');
        await accountTypes.nth(0).selectOption('traditional');

        const balances = page.locator('label:has-text("Initial Balance")').locator('..').locator('input');
        await balances.nth(0).click();
        await balances.nth(0).fill('200000');

        const contributions = page.locator('label:has-text("Annual Contribution")').locator('..').locator('input');
        await contributions.nth(0).click();
        await contributions.nth(0).fill('0');

        const returns = page.locator('label:has-text("Expected Return")').locator('..').locator('input');
        await returns.nth(0).clear();
        await returns.nth(0).fill('5');

        await page.click('button:has-text("Create Scenario")');
        await expect(page.locator('text=Underfunded Plan').first()).toBeVisible({ timeout: 10000 });
    });

    test('3.6 Run Scenario A and verify FAILURE', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.locator('text=Underfunded Plan').first().click();

        await page.click('button:has-text("Run Projection")');
        await waitForProjection(page);

        await assertProjectionCrosscuts(page);

        // FAILURE outcome — "Underfunded" or "Depleted"
        const outcomeVisible = await page.locator('text=/Underfunded|Depleted/').first().isVisible();
        expect(outcomeVisible).toBe(true);

        // Spending shortfall warning
        await expect(page.locator('text=Spending Shortfall Detected').first()).toBeVisible({ timeout: 5000 });

        // Nevada has no income tax
        await assertStateTax(page, false);

        // Final balance <= 0
        const finalBalanceText = await getCardValue(page, 'Final Balance');
        expect(parseCurrency(finalBalanceText)).toBeLessThanOrEqual(0);
    });

    test('3.7 Create modest spending profile for recovery', async ({ page }) => {
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Spending');

        await page.click('button:has-text("New Profile")');

        await page.fill('input[placeholder="Retirement Spending"]', 'Frugal Plan');

        const essential = page.locator('label:has-text("Essential Expenses")').locator('..').locator('input');
        await essential.click();
        await essential.fill('25000');

        const discretionary = page.locator('label:has-text("Discretionary Expenses")').locator('..').locator('input');
        await discretionary.click();
        await discretionary.fill('5000');

        await page.click('button:has-text("Create Profile")');
        await expect(page.locator('text=Frugal Plan').first()).toBeVisible({ timeout: 5000 });
    });

    test('3.8 Create Scenario B: Sustainable Roth-only plan', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.click('button:has-text("New Scenario")');

        await page.fill('input[placeholder="Retirement Plan"]', 'Sustainable Plan');

        const retDate = page.locator('label:has-text("Retirement Date")').locator('..').locator('input');
        await retDate.fill('2031-01-01');

        const birthYear = page.locator('label:has-text("Birth Year")').locator('..').locator('input');
        await birthYear.fill('1981');

        const endAge = page.locator('label:has-text("End Age")').locator('..').locator('input');
        await endAge.clear();
        await endAge.fill('90');

        const inflation = page.locator('label:has-text("Inflation Rate")').locator('..').locator('input');
        await inflation.clear();
        await inflation.fill('3');

        const withdrawal = page.locator('label:has-text("Withdrawal Rate")').locator('..').locator('input');
        await withdrawal.clear();
        await withdrawal.fill('4');

        // Spending Plan: Frugal Plan
        const spendingSelect = page.locator('label:has-text("Spending Plan")').locator('..').locator('select');
        await spendingSelect.selectOption({ label: 'Frugal Plan' });

        // Income Sources
        await checkIncomeSource(page, 'Retiree Pension');

        // Withdrawal Strategy
        await clickCard(page, 'Fixed Percentage');
        await clickCard(page, 'Taxable First');

        // Roth Conversion: Fixed Amount
        await clickCard(page, 'Fixed Amount');

        // No state tax (leave State as "None")

        // Account: Roth $400k
        const accountTypes = page.locator('label:has-text("Account Type")').locator('..').locator('select');
        await accountTypes.nth(0).selectOption('roth');

        const balances = page.locator('label:has-text("Initial Balance")').locator('..').locator('input');
        await balances.nth(0).click();
        await balances.nth(0).fill('400000');

        const contributions = page.locator('label:has-text("Annual Contribution")').locator('..').locator('input');
        await contributions.nth(0).click();
        await contributions.nth(0).fill('0');

        const returns = page.locator('label:has-text("Expected Return")').locator('..').locator('input');
        await returns.nth(0).clear();
        await returns.nth(0).fill('6');

        await page.click('button:has-text("Create Scenario")');
        await expect(page.locator('text=Sustainable Plan').first()).toBeVisible({ timeout: 10000 });
    });

    test('3.9 Run Scenario B and verify SUCCESS', async ({ page }) => {
        test.slow();
        await loginAsUser(page, userEmail, userPassword);
        await page.click('nav >> text=Projections');
        await page.locator('text=Sustainable Plan').first().click();

        await page.click('button:has-text("Run Projection")');
        await waitForProjection(page);

        await assertProjectionCrosscuts(page);

        // SUCCESS outcome
        const outcomeVisible = await page.locator('text=/Fully Sustainable|Never/').first().isVisible();
        expect(outcomeVisible).toBe(true);

        // No spending shortfall
        await expect(page.locator('text=Spending Shortfall Detected')).not.toBeVisible({ timeout: 3000 });

        // Final balance > 0
        const finalBalanceText = await getCardValue(page, 'Final Balance');
        expect(parseCurrency(finalBalanceText)).toBeGreaterThan(0);
    });
});
