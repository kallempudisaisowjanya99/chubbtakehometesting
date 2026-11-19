import { expect, test } from '@playwright/test';
import { submitClaim } from './support/api';
import { AppHeader, open, statValue } from './support/pages';
import { CLAIMANT, newClaim, STORAGE_STATE } from './support/test-data';

/**
 * Journey: an admin monitors the system: dashboard statistics, the cache demo and the user list.
 */
test.describe('Admin dashboard and users', () => {
  test.use({ storageState: STORAGE_STATE.admin });

  test.beforeEach(async ({ page }) => {
    await open(page, '/admin/dashboard');
  });

  test('dashboard shows totals and a breakdown by status', async ({ page }) => {
    await expect(page.getByTestId('stats-card-users')).toContainText('Registered users');
    await expect(page.getByTestId('stats-card-documents')).toContainText('All claims submitted');
    const byStatus = page.getByTestId('stats-card-chart');
    for (const label of ['Submitted', 'Under Review', 'Approved', 'Rejected', 'Closed']) {
      await expect(byStatus).toContainText(label);
    }
    expect(await statValue(page, 'users')).toBeGreaterThanOrEqual(2); // the two seeded users
  });

  test('flushing the cache shows fresh totals from the database', async ({ page }) => {
    const flush = page.getByRole('button', { name: 'Flush cache and reload dashboard stats from database' });
    await flush.click();
    await expect(page.getByText('Cache Miss')).toBeVisible();
    const before = await statValue(page, 'documents');

    await submitClaim(newClaim());
    await page.waitForTimeout(3_100); // the flush button is debounced for 3 s
    await flush.click();

    await expect(page.getByText('Cache Miss')).toBeVisible();
    // Other tests may add claims in parallel, so at least one more.
    await expect.poll(() => statValue(page, 'documents')).toBeGreaterThan(before);
  });

  // Fails today: cached stats are written to Redis but cannot be read back (INTEGRATION_TEST_NOTES.md, D2).
  test('loading again shortly after is served from the cache', async ({ page }) => {
    await page.getByRole('button', { name: 'Flush cache and reload dashboard stats from database' }).click();
    await expect(page.getByText('Cache Miss')).toBeVisible();

    await page.getByRole('button', { name: 'Load dashboard stats from cache' }).click();

    await expect(page.getByText('Cache Hit ⚡')).toBeVisible();
  });

  test('admin finds a user on the users page', async ({ page }) => {
    await new AppHeader(page).nav('Users').click();
    await expect(page.getByRole('heading', { name: 'User Management' })).toBeVisible();

    await page.getByLabel('Search users').fill(CLAIMANT.email);

    const rows = page.getByRole('row').filter({ hasText: CLAIMANT.email });
    await expect(rows).toHaveCount(1);
    await expect(rows).toContainText(CLAIMANT.name);
    await expect(rows).toContainText('CLAIMANT');
    await expect(page.getByText(/^1 users$/)).toBeVisible();
  });
});
