import { expect, test } from '@playwright/test';
import { apiSessionFor, submitClaim } from './support/api';
import { AdminClaimsPage, AppHeader, MyClaimsPage, open } from './support/pages';
import { CLAIMANT, newClaim, STORAGE_STATE } from './support/test-data';

/**
 * Journey: an admin reviews a submitted claim and takes it through to a decision,
 * and the claimant sees the outcome.
 */
test.describe('Admin claim review', () => {
  test.use({ storageState: STORAGE_STATE.admin });

  let claimantId: string;

  test.beforeAll(async () => {
    claimantId = (await apiSessionFor('claimant')).userId;
  });

  /** Admin claims page narrowed to the demo claimant's claims. */
  async function openClaimantClaims(page: import('@playwright/test').Page) {
    const admin = new AdminClaimsPage(page);
    await admin.goto();
    await admin.userSearch.fill(claimantId);
    return admin;
  }

  test('admin finds a new claim and approves it through every review step', async ({ page }) => {
    const claimId = await submitClaim(newClaim());
    await open(page, '/admin/dashboard');
    await new AppHeader(page).nav('Claims').click();

    const admin = new AdminClaimsPage(page);
    await expect(admin.heading).toBeVisible();

    // Narrow the list to the claimant's submitted claims
    await admin.statusFilter.selectOption('SUBMITTED');
    await admin.userSearch.fill(claimantId);
    await expect(admin.row(claimId)).toBeVisible();
    await expect(admin.row(claimId).getByRole('status')).toHaveText('Submitted');
    await admin.statusFilter.selectOption(''); // the claim leaves the "Submitted" filter after the first step

    for (const next of ['Under Review', 'Approved', 'Closed']) {
      const dialog = await admin.changeStatus(claimId, next);
      await dialog.getByRole('button', { name: 'Confirm' }).click();

      await expect(page.getByText(`Claim status updated to ${next}`)).toBeVisible();
      await expect(admin.rowByShortId(claimId).getByRole('status', { name: /Claim status/ })).toHaveText(next);
    }

    // Closed is final: no more transitions are offered
    await expect(admin.statusSelect(claimId)).toHaveCount(0);
    await expect(admin.rowByShortId(claimId).getByLabel('No status transitions available')).toBeVisible();
  });

  test('admin can only choose the transitions allowed from the current status', async ({ page }) => {
    const claimId = await submitClaim(newClaim());
    const admin = await openClaimantClaims(page);

    const options = admin.statusSelect(claimId).locator('option:not([disabled])');
    await expect(options).toHaveText(['Under Review', 'Rejected']);
  });

  test('cancelling the confirmation leaves the status unchanged', async ({ page }) => {
    const claimId = await submitClaim(newClaim());
    let admin = await openClaimantClaims(page);

    const dialog = await admin.changeStatus(claimId, 'Rejected');
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    await expect(dialog).toBeHidden();
    await expect(admin.row(claimId).getByRole('status')).toHaveText('Submitted');
    admin = await openClaimantClaims(page); // reload: nothing was saved
    await expect(admin.row(claimId).getByRole('status')).toHaveText('Submitted');
  });

  test('claimant sees the admin decision on their claim', async ({ page, browser }) => {
    const claim = newClaim();
    const claimId = await submitClaim(claim);

    const admin = await openClaimantClaims(page);
    await (await admin.changeStatus(claimId, 'Rejected')).getByRole('button', { name: 'Confirm' }).click();
    await expect(page.getByText('Claim status updated to Rejected')).toBeVisible();

    const claimantContext = await browser.newContext({ storageState: STORAGE_STATE.claimant });
    const myClaims = new MyClaimsPage(await claimantContext.newPage());
    await myClaims.goto();
    await expect(myClaims.status(myClaims.row(claim.description))).toHaveText('Rejected');
    await claimantContext.close();
  });
});
