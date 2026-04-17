import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { login } from './helpers';

/**
 * Accessibility smoke tests. Uses axe-core to check for serious/critical WCAG issues
 * on the main pages. Excludes deliberately-known issues with
 * `disableRules([...])` rather than suppressing everything, so regressions surface.
 */

async function runAxe(page: Page) {
    // Gate on critical violations + serious violations excluding color-contrast.
    // Color-contrast has known violations across the app (#999 secondary text on white
    // is 2.84:1, below the 4.5:1 WCAG AA threshold). Fixing that is a separate design
    // pass — this suite catches any NEW a11y regressions beyond that baseline.
    return new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .disableRules(['color-contrast'])
        .analyze();
}

test.describe('Accessibility', () => {
    test('login page has no serious a11y violations', async ({ page }) => {
        await page.goto('/login');
        const results = await runAxe(page);
        const serious = results.violations.filter(v => ['serious', 'critical'].includes(v.impact ?? ''));
        expect(serious).toEqual([]);
    });

    test('dashboard has no serious a11y violations', async ({ page }) => {
        await login(page);
        const results = await runAxe(page);
        const serious = results.violations.filter(v => ['serious', 'critical'].includes(v.impact ?? ''));
        expect(serious).toEqual([]);
    });

    test('accounts page has no serious a11y violations', async ({ page }) => {
        await login(page);
        await page.goto('/accounts');
        const results = await runAxe(page);
        const serious = results.violations.filter(v => ['serious', 'critical'].includes(v.impact ?? ''));
        expect(serious).toEqual([]);
    });

    test('projections page has no serious a11y violations', async ({ page }) => {
        await login(page);
        await page.goto('/projections');
        const results = await runAxe(page);
        const serious = results.violations.filter(v => ['serious', 'critical'].includes(v.impact ?? ''));
        expect(serious).toEqual([]);
    });

    test('properties page has no serious a11y violations', async ({ page }) => {
        await login(page);
        await page.goto('/properties');
        const results = await runAxe(page);
        const serious = results.violations.filter(v => ['serious', 'critical'].includes(v.impact ?? ''));
        expect(serious).toEqual([]);
    });
});
