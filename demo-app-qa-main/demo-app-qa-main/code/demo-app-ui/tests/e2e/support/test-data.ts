import { faker } from '@faker-js/faker';

export interface Credentials {
  email: string;
  password: string;
}

export interface ClaimInput {
  /** ISO date, yyyy-mm-dd */
  incidentDate: string;
  incidentLocation: string;
  description: string;
  claimAmount: number;
}

/** Saved logged-in sessions written by auth.setup.ts. They hold tokens: tests/e2e/.auth/ is git-ignored. */
export const STORAGE_STATE = {
  admin: 'tests/e2e/.auth/admin.json',
  claimant: 'tests/e2e/.auth/claimant.json',
};

/** Seeded by the Keycloak realm import (docker/compose/keycloak). */
export const ADMIN: Credentials = {
  email: 'admin@demo.com',
  password: 'Admin123!',
};

/**
 * Seeded demo claimant, shared by all claimant journeys.
 *
 * Ideally each test would sign up its own claimant, but signup is broken on the Docker stack
 * (see auth.spec.ts). Tests sharing this account therefore never assert on list sizes; each one
 * creates its own claim and finds it by its unique id or description.
 */
export const CLAIMANT: Credentials & { name: string } = {
  email: 'claimant@demo.com',
  password: 'Claimant123!',
  name: 'Test Claimant',
};

/** A unique, valid signup payload. Unique emails keep parallel tests independent. */
export function newClaimantData() {
  const suffix = faker.string.alphanumeric(8).toLowerCase();
  return {
    name: `E2E ${faker.person.firstName()} ${suffix}`,
    email: `e2e.${suffix}@example.com`,
    // Meets the signup rules: 8+ chars, an uppercase letter and a number.
    password: `E2e-${suffix}-Pass1`,
  };
}

export function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().split('T')[0];
}

/** A valid claim with a unique, recognisable description. */
export function newClaim(overrides: Partial<ClaimInput> = {}): ClaimInput {
  return {
    incidentDate: isoDaysAgo(3),
    incidentLocation: `${faker.location.streetAddress()}, ${faker.location.city()}`,
    description: `E2E ${faker.string.alphanumeric(6)}: rear bumper damaged in a car park collision.`,
    claimAmount: 1234.56,
    ...overrides,
  };
}
