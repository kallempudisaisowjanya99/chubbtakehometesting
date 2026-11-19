import { expect, type Locator, type Page } from '@playwright/test';
import type { ClaimInput, Credentials } from './test-data';

/**
 * Small page objects: one place for each screen's locators, so a UI change means one fix.
 * Locators prefer what a user sees (roles, labels, text) and fall back to the app's data-testids.
 */

export class LoginPage {
  readonly email: Locator;
  readonly password: Locator;
  readonly submit: Locator;
  readonly alert: Locator;

  constructor(private readonly page: Page) {
    this.email = page.getByLabel('Email');
    this.password = page.locator('#password');
    this.submit = page.getByRole('button', { name: 'Log In' });
    // Not getByRole('alert'): Next.js also renders an (empty) route announcer with role="alert".
    this.alert = page.locator('p[role="alert"]');
  }

  async goto() {
    await this.page.goto('/login');
    await expect(this.page.getByRole('heading', { name: 'Log In' })).toBeVisible();
  }

  async login({ email, password }: Credentials) {
    await this.email.fill(email);
    await this.password.fill(password);
    await this.submit.click();
  }
}

/** Logs in through the UI and waits for the role's landing page. */
export async function loginAs(page: Page, user: Credentials, landing: '/claims' | '/admin/dashboard') {
  const loginPage = new LoginPage(page);
  await loginPage.goto();
  await loginPage.login(user);
  await expect(page).toHaveURL(landing);
  await waitUntilInteractive(page);
}

/**
 * Opens a page with an already logged-in session and waits until React has hydrated.
 * The header (with the live-connection indicator) only renders client-side once the user is known,
 * so its presence means buttons react to clicks. In `next dev`, clicking earlier silently does nothing.
 */
export async function open(page: Page, path: string) {
  await page.goto(path);
  await waitUntilInteractive(page);
}

export async function waitUntilInteractive(page: Page) {
  await expect(page.getByRole('status', { name: /Connection status/ })).toBeVisible();
}

export class AppHeader {
  readonly logout: Locator;
  readonly connectionStatus: Locator;

  constructor(private readonly page: Page) {
    this.logout = page.getByRole('button', { name: 'Logout' });
    this.connectionStatus = page.getByRole('status', { name: /Connection status/ });
  }

  welcome(name: string) {
    return this.page.getByText(`Welcome, ${name}`);
  }

  nav(name: string) {
    return this.page.getByRole('banner').getByRole('button', { name, exact: true });
  }
}

export class ClaimWizard {
  readonly date: Locator;
  readonly location: Locator;
  readonly amount: Locator;
  readonly description: Locator;
  readonly next: Locator;
  readonly back: Locator;
  readonly submit: Locator;
  readonly cancel: Locator;

  constructor(private readonly page: Page) {
    this.date = page.getByLabel('When did the incident occur?');
    this.location = page.getByLabel('Where did the incident occur?');
    this.amount = page.getByLabel('Claim Amount');
    this.description = page.getByLabel('Describe what happened');
    // exact: in dev mode Next.js adds an "Open Next.js Dev Tools" button.
    this.next = page.getByRole('button', { name: 'Next', exact: true });
    this.back = page.getByRole('button', { name: 'Back', exact: true });
    // Scoped to main: the header has a "Submit Claim" nav button too.
    this.submit = page.getByRole('main').getByRole('button', { name: 'Submit Claim', exact: true });
    this.cancel = page.getByRole('button', { name: 'Cancel' });
  }

  step(n: 1 | 2 | 3) {
    return this.page.getByTestId(`wizard-step-${n}`);
  }

  async goto() {
    await open(this.page, '/claims/new');
    await expect(this.step(1)).toBeVisible();
  }

  async fillIncidentDetails(claim: ClaimInput) {
    await this.date.fill(claim.incidentDate);
    await this.location.fill(claim.incidentLocation);
    await this.amount.fill(String(claim.claimAmount));
  }

  async fillDescription(claim: ClaimInput) {
    await this.description.fill(claim.description);
  }

  /** Goes through all three steps and stops on the review step. */
  async completeUpToReview(claim: ClaimInput) {
    await this.fillIncidentDetails(claim);
    await this.next.click();
    await expect(this.step(2)).toBeVisible();
    await this.fillDescription(claim);
    await this.next.click();
    await expect(this.step(3)).toBeVisible();
  }

  /** Review-step value shown under a heading such as "Location". */
  reviewValue(heading: string) {
    return this.step(3).getByRole('heading', { name: heading, exact: true }).locator('xpath=following-sibling::p[1]');
  }
}

export class MyClaimsPage {
  readonly heading: Locator;
  readonly newClaim: Locator;

  constructor(private readonly page: Page) {
    this.heading = page.getByRole('heading', { name: 'My Claims' });
    this.newClaim = page.getByRole('button', { name: 'Submit New Claim' });
  }

  async goto() {
    await open(this.page, '/claims');
    await expect(this.heading).toBeVisible();
  }

  /** The table row of a claim, found by its unique description. */
  row(description: string) {
    return this.page.getByRole('row').filter({ has: this.page.locator(`span[title="${description}"]`) });
  }

  status(row: Locator) {
    return row.getByRole('status', { name: /Claim status/ });
  }

  async openDetails(description: string) {
    await this.row(description).getByTestId('view-details-button').click();
    const modal = this.page.getByRole('dialog', { name: 'Claim Details' });
    await expect(modal).toBeVisible();
    return modal;
  }
}

export class AdminClaimsPage {
  readonly heading: Locator;
  readonly statusFilter: Locator;
  readonly userSearch: Locator;
  readonly count: Locator;

  constructor(private readonly page: Page) {
    this.heading = page.getByRole('heading', { name: 'Claims Management' });
    this.statusFilter = page.getByLabel('Filter claims by status');
    this.userSearch = page.getByLabel('Search claims by user ID');
    this.count = page.getByText(/^\d+ claims$/);
  }

  async goto() {
    await open(this.page, '/admin/claims');
    await expect(this.heading).toBeVisible();
  }

  statusSelect(claimId: string) {
    // The page also renders a mobile card view (hidden on desktop) with its own select.
    return this.page.getByTestId(`status-select-${claimId.substring(0, 8)}`).filter({ visible: true });
  }

  row(claimId: string) {
    return this.page.getByRole('row').filter({ has: this.statusSelect(claimId) });
  }

  /** Row of a claim whose status has no further transitions (no select is rendered). */
  rowByShortId(claimId: string) {
    return this.page.getByRole('row').filter({ hasText: claimId.substring(0, 8) });
  }

  async changeStatus(claimId: string, label: string) {
    await this.statusSelect(claimId).selectOption({ label });
    const dialog = this.page.getByRole('dialog', { name: 'Confirm Status Change' });
    await expect(dialog).toContainText(`Change status to ${label}?`);
    return dialog;
  }
}

/** Number shown on a dashboard stats card ("users" or "documents"). */
export async function statValue(page: Page, card: 'users' | 'documents'): Promise<number> {
  const text = await page.getByTestId(`stats-card-${card}`).locator('p').first().innerText();
  return Number(text.trim());
}
