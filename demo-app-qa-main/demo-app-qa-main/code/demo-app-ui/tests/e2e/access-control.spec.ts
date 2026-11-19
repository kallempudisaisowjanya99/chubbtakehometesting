import { expect, test } from '@playwright/test';
import { STORAGE_STATE } from './support/test-data';

/**
 * Journey guard rails: a claimant cannot reach admin screens, even by typing the URL.
 * (Claim ownership between users is covered at API level by the claims-service integration tests.)
 */
test.describe('Access control', () => {
  test.use({ storageState: STORAGE_STATE.claimant });

  for (const path of ['/admin/dashboard', '/admin/claims', '/admin/users']) {
    test(`claimant opening ${path} is denied and sent back to My Claims`, async ({ page }) => {
      await page.goto(path);

      await expect(page.getByText('Access denied')).toBeVisible();
      await expect(page).toHaveURL('/claims');
    });
  }
});
