import { expect, test } from '@playwright/test';
import { AppHeader, LoginPage, loginAs } from './support/pages';
import { ADMIN, CLAIMANT, newClaimantData } from './support/test-data';

/**
 * Journey: getting in and out of the app.
 * Sign up -> log in -> land on the page for your role -> log out.
 */
test.describe('Authentication', () => {
  test('unauthenticated visitor is sent to login, remembering the page they wanted', async ({ page }) => {
    await page.goto('/claims/new');

    await expect(page).toHaveURL(/\/login\?redirect=%2Fclaims%2Fnew$/);
    await expect(page.getByRole('heading', { name: 'Log In' })).toBeVisible();
  });

  test('claimant logs in and lands on My Claims with claimant navigation', async ({ page }) => {
    await loginAs(page, CLAIMANT, '/claims');

    const header = new AppHeader(page);
    await expect(page.getByRole('heading', { name: 'My Claims' })).toBeVisible();
    await expect(header.welcome(CLAIMANT.name)).toBeVisible();
    await expect(header.nav('My Claims')).toBeVisible();
    await expect(header.nav('Submit Claim')).toBeVisible();
    await expect(header.nav('Dashboard')).toHaveCount(0);
  });

  test('admin logs in and lands on the admin dashboard with admin navigation', async ({ page }) => {
    await loginAs(page, ADMIN, '/admin/dashboard');

    const header = new AppHeader(page);
    await expect(page.getByRole('heading', { name: 'Admin Dashboard' })).toBeVisible();
    for (const item of ['Dashboard', 'Claims', 'Users']) {
      await expect(header.nav(item)).toBeVisible();
    }
    await expect(header.nav('Submit Claim')).toHaveCount(0);
  });

  test('wrong password shows an error and keeps the user on the login page', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();

    // An unknown account gives the same message. Using the shared demo claimant here would trip
    // Keycloak's brute-force protection and lock it for every other test.
    await loginPage.login({ email: newClaimantData().email, password: 'Wrong-Password-1' });

    await expect(loginPage.alert).toHaveText('Invalid email or password');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('invalid email format is rejected before anything is sent to the server', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    let loginCalls = 0;
    page.on('request', (r) => r.url().includes('/api/auth/login') && loginCalls++);

    await loginPage.login({ email: 'not-an-email', password: 'whatever' });

    await expect(page.getByText('Invalid email format')).toBeVisible();
    expect(loginCalls).toBe(0);
  });

  test('logout ends the session: protected pages require login again', async ({ page }) => {
    await loginAs(page, CLAIMANT, '/claims');

    await new AppHeader(page).logout.click();
    await expect(page).toHaveURL(/\/login/);

    await page.goto('/claims');
    await expect(page).toHaveURL(/\/login\?redirect=%2Fclaims$/);
  });

  test.describe('Sign up', () => {
    test('new user signs up, logs in and sees an empty claims list', async ({ page }) => {
      const user = newClaimantData();
      await page.goto('/signup');

      await page.getByLabel('Name').fill(user.name);
      await page.getByLabel('Email').fill(user.email);
      await page.locator('#password').fill(user.password);
      await page.getByRole('button', { name: 'Sign Up' }).click();

      await expect(page.getByText('Account created! Please log in.')).toBeVisible();
      await expect(page).toHaveURL(/\/login$/);

      await new LoginPage(page).login(user);
      await expect(page).toHaveURL('/claims');
      await expect(new AppHeader(page).welcome(user.name)).toBeVisible();
      await expect(page.getByRole('heading', { name: 'No claims yet' })).toBeVisible();
    });

    test('weak password is rejected with the password rules', async ({ page }) => {
      await page.goto('/signup');

      await page.getByLabel('Name').fill('Weak Password User');
      await page.getByLabel('Email').fill('weak.password@example.com');
      await page.locator('#password').fill('short');
      await page.getByRole('button', { name: 'Sign Up' }).click();

      await expect(page.getByText('Password must be at least 8 characters')).toBeVisible();
      await expect(page).toHaveURL(/\/signup$/);
    });

    test('signing up with an existing email says the email is taken', async ({ page }) => {
      await page.goto('/signup');

      await page.getByLabel('Name').fill('Duplicate User');
      await page.getByLabel('Email').fill(CLAIMANT.email);
      await page.locator('#password').fill('Duplicate123');
      await page.getByRole('button', { name: 'Sign Up' }).click();

      await expect(page.getByText('Email already exists')).toBeVisible();
    });
  });
});
