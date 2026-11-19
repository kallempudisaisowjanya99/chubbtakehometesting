import { expect, request, type APIRequestContext } from '@playwright/test';
import { STORAGE_STATE, type ClaimInput } from './test-data';

/**
 * API shortcuts for test-data setup only.
 *
 * Each test drives the journey it is about through the UI. Anything it needs beforehand (an
 * existing claim, a claim in a given status) is created through the same public BFF API the UI
 * uses. That is faster, and keeps a failure pointing at the journey under test.
 *
 * Sessions reuse the logins saved by auth.setup.ts instead of logging in again: Keycloak's
 * brute-force protection temporarily locks an account after many rapid logins.
 */

const BASE_URL = 'http://localhost:3001';

export type Role = keyof typeof STORAGE_STATE;

interface ApiSession {
  api: APIRequestContext;
  userId: string;
}

const sessions = new Map<Role, Promise<ApiSession>>();

/** An API session with the role's saved auth cookies, as the browser has them. */
export function apiSessionFor(role: Role): Promise<ApiSession> {
  let session = sessions.get(role);
  if (!session) {
    session = createSession(role);
    sessions.set(role, session);
    session.catch(() => sessions.delete(role));
  }
  return session;
}

async function createSession(role: Role): Promise<ApiSession> {
  const api = await request.newContext({ baseURL: BASE_URL, storageState: STORAGE_STATE[role] });
  const state = await api.storageState();
  const authStore = state.origins
    .flatMap((origin) => origin.localStorage)
    .find((entry) => entry.name === 'auth-storage');
  const userId = authStore ? JSON.parse(authStore.value).state?.user?.userId : undefined;
  expect(userId, `saved ${role} session has a user (run the setup project)`).toBeTruthy();
  return { api, userId };
}

/**
 * Header for state-changing calls. The BFF uses Spring's cookie-to-header CSRF protection, so
 * like the UI's bff-client we echo the current XSRF-TOKEN cookie, fetching one if there is none.
 */
async function csrfHeader(api: APIRequestContext): Promise<Record<string, string>> {
  let token = (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  if (!token) {
    const response = await api.get('/api/auth/csrf');
    expect(response.status(), 'fetch CSRF token').toBe(200);
    token = (await response.json()).token as string;
  }
  return { 'X-XSRF-TOKEN': decodeURIComponent(token) };
}

/** Submits a claim as the demo claimant and returns its id. */
export async function submitClaim(claim: ClaimInput): Promise<string> {
  const { api } = await apiSessionFor('claimant');
  const response = await api.post('/api/claims', { data: claim, headers: await csrfHeader(api) });
  expect(response.status(), `submit claim: ${await response.text()}`).toBe(201);
  return (await response.json()).claimId;
}

/** Moves a claim to a new status as the demo admin. */
export async function setClaimStatus(claimId: string, newStatus: string): Promise<void> {
  const { api } = await apiSessionFor('admin');
  const response = await api.patch(`/api/admin/claims/${claimId}/status`, {
    data: { newStatus },
    headers: await csrfHeader(api),
  });
  expect(response.status(), `set ${claimId} -> ${newStatus}: ${await response.text()}`).toBe(200);
}
