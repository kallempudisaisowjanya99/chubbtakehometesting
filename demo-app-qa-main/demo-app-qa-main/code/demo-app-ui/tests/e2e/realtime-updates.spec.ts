import { expect, test } from '@playwright/test';
import { setClaimStatus, submitClaim } from './support/api';
import { AdminClaimsPage, AppHeader, MyClaimsPage } from './support/pages';
import { newClaim, STORAGE_STATE } from './support/test-data';

/**
 * Journey: live updates. A status change made by an admin reaches an open claimant screen
 * without a reload (claims-service -> Kafka -> BFF -> WebSocket -> UI), and new claims reach
 * an open admin screen.
 *
 * Needs events on Kafka: either APP_EVENTS_CDC_ENABLED=false (direct publishing, the README's
 * default) or Debezium running (./start-cdc.sh).
 */
test.describe('Real-time updates', () => {
  test.describe('as claimant', () => {
    test.use({ storageState: STORAGE_STATE.claimant });

    test('claimant sees a status change live, without reloading', async ({ page }) => {
      const claim = newClaim();
      const claimId = await submitClaim(claim);
      const myClaims = new MyClaimsPage(page);
      await myClaims.goto();
      const row = myClaims.row(claim.description);
      await expect(myClaims.status(row)).toHaveText('Submitted');
      await expect(new AppHeader(page).connectionStatus).toHaveAccessibleName('Connection status: Live');

      await setClaimStatus(claimId, 'UNDER_REVIEW');

      await expect(
        page.getByText(`Your claim #${claimId.substring(0, 8)}… status changed to UNDER_REVIEW`)
      ).toBeVisible({ timeout: 15_000 });
      await expect(myClaims.status(row)).toHaveText('Under Review');
    });
  });

  test.describe('as admin', () => {
    test.use({ storageState: STORAGE_STATE.admin });

    test('admin sees a newly submitted claim appear live', async ({ page }) => {
      const admin = new AdminClaimsPage(page);
      await admin.goto();
      await expect(new AppHeader(page).connectionStatus).toHaveAccessibleName('Connection status: Live');

      const claimId = await submitClaim(newClaim());

      await expect(page.getByText(`New claim submitted: ${claimId.substring(0, 8)}…`)).toBeVisible({
        timeout: 15_000,
      });
      await expect(admin.row(claimId)).toBeVisible();
    });
  });
});
