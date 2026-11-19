import { test as setup } from '@playwright/test';
import { loginAs } from './support/pages';
import { ADMIN, CLAIMANT, STORAGE_STATE } from './support/test-data';

/**
 * Logs in once per role through the real login page and saves the session (cookies + the UI's
 * persisted auth store) for the other tests to reuse.
 *
 * Why: Keycloak's brute-force protection is on in the demo-app realm, and dozens of rapid logins
 * to the same account temporarily lock it ("user_temporarily_disabled") even with the right
 * password. Tests that are about logging in still log in themselves (auth.spec.ts).
 */
setup('log in as claimant', async ({ page }) => {
  await loginAs(page, CLAIMANT, '/claims');
  await page.context().storageState({ path: STORAGE_STATE.claimant });
});

setup('log in as admin', async ({ page }) => {
  await loginAs(page, ADMIN, '/admin/dashboard');
  await page.context().storageState({ path: STORAGE_STATE.admin });
});
