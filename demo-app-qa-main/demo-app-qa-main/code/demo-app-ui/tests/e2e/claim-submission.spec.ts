import { expect, test } from '@playwright/test';
import { longDate, shortDate, usd } from './support/format';
import { ClaimWizard, MyClaimsPage } from './support/pages';
import { isoDaysAgo, newClaim, STORAGE_STATE } from './support/test-data';

/**
 * Journey: a claimant submits a claim through the 3-step wizard and then finds it in My Claims.
 */
test.describe('Claim submission', () => {
  test.use({ storageState: STORAGE_STATE.claimant });

  test('claimant submits a claim and sees it in My Claims with all details', async ({ page }) => {
    const claim = newClaim({ claimAmount: 2500.75 });
    const myClaims = new MyClaimsPage(page);
    const wizard = new ClaimWizard(page);

    await myClaims.goto();
    await myClaims.newClaim.click();
    await expect(page).toHaveURL('/claims/new');
    await wizard.completeUpToReview(claim);

    // Review step shows exactly what was entered
    await expect(wizard.reviewValue('Incident Date')).toHaveText(longDate(claim.incidentDate));
    await expect(wizard.reviewValue('Location')).toHaveText(claim.incidentLocation);
    await expect(wizard.reviewValue('Claim Amount')).toHaveText(usd(claim.claimAmount));
    await expect(wizard.reviewValue('Description')).toHaveText(claim.description);

    await wizard.submit.click();

    const toast = page.getByText(/^Claim submitted successfully! Claim ID: /);
    await expect(toast).toBeVisible();
    const claimId = (await toast.innerText()).split('Claim ID: ')[1].trim();
    await expect(page).toHaveURL('/claims');

    // The new claim is listed as Submitted
    const row = myClaims.row(claim.description);
    await expect(row).toHaveCount(1);
    await expect(row).toContainText(claimId.substring(0, 8));
    await expect(row).toContainText(shortDate(claim.incidentDate));
    await expect(row).toContainText(usd(claim.claimAmount));
    await expect(myClaims.status(row)).toHaveText('Submitted');

    // Details modal shows the stored claim
    const modal = await myClaims.openDetails(claim.description);
    await expect(modal).toContainText(claimId);
    await expect(modal).toContainText(claim.incidentLocation);
    await expect(modal).toContainText(claim.description);
    await expect(modal).toContainText(usd(claim.claimAmount));
    await expect(modal.getByRole('status', { name: 'Claim status: Submitted' })).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(modal).toBeHidden();
  });

  test('Next stays disabled and errors explain why until step 1 is valid', async ({ page }) => {
    const wizard = new ClaimWizard(page);
    await wizard.goto();
    await expect(wizard.next).toBeDisabled();

    await wizard.location.fill('Abc');
    await wizard.amount.fill('2000000');
    await wizard.date.fill(isoDaysAgo(-2)); // future date

    await expect(page.getByText('Location must be at least 5 characters')).toBeVisible();
    await expect(page.getByText('Claim amount cannot exceed $1,000,000')).toBeVisible();
    await expect(page.getByText('Incident date cannot be in the future')).toBeVisible();
    await expect(wizard.next).toBeDisabled();

    await wizard.fillIncidentDetails(newClaim());
    await expect(wizard.next).toBeEnabled();
  });

  test('description must be at least 10 characters before review', async ({ page }) => {
    const wizard = new ClaimWizard(page);
    await wizard.goto();
    await wizard.fillIncidentDetails(newClaim());
    await wizard.next.click();

    await wizard.description.fill('Too short');

    await expect(page.getByText('Description must be at least 10 characters')).toBeVisible();
    await expect(wizard.next).toBeDisabled();
  });

  test('going Back keeps what the claimant already entered', async ({ page }) => {
    const claim = newClaim();
    const wizard = new ClaimWizard(page);
    await wizard.goto();
    await wizard.completeUpToReview(claim);

    await wizard.back.click();
    await expect(wizard.description).toHaveValue(claim.description);
    await wizard.back.click();

    await expect(wizard.location).toHaveValue(claim.incidentLocation);
    await expect(wizard.amount).toHaveValue(String(claim.claimAmount));
    // Fails today: the date is kept as a Date object, which an <input type="date"> cannot display.
    await expect(wizard.date).toHaveValue(claim.incidentDate);
    await expect(wizard.next).toBeEnabled();
  });

  test('cancelling a started claim asks for confirmation before discarding it', async ({ page }) => {
    const claim = newClaim();
    const wizard = new ClaimWizard(page);
    await wizard.goto();
    await wizard.fillIncidentDetails(claim);
    await wizard.next.click();

    await wizard.cancel.click();
    const dialog = page.getByRole('dialog', { name: 'Cancel Claim Submission?' });
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: 'Keep Editing' }).click();
    await expect(dialog).toBeHidden();
    await expect(wizard.step(2)).toBeVisible();

    await wizard.cancel.click();
    await dialog.getByRole('button', { name: 'Discard & Leave' }).click();
    await expect(page).toHaveURL('/claims');

    // Nothing was submitted, and the wizard starts fresh next time
    await expect(new MyClaimsPage(page).row(claim.description)).toHaveCount(0);
    await wizard.goto();
    await expect(wizard.location).toHaveValue('');
  });
});
