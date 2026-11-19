import { request, type FullConfig } from '@playwright/test';

/**
 * Fails fast with a clear message when the stack is not running.
 *
 * The E2E suite drives the real application (UI -> BFF -> claims-service -> Kafka -> WebSocket),
 * so without the backend every test would fail with confusing timeouts instead.
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'http://localhost:3001';
  const api = await request.newContext({ baseURL });
  try {
    // A login with bad credentials goes through the UI's /api rewrite to the BFF and Keycloak:
    // 401 proves the whole auth chain is up.
    const response = await api.post('/api/auth/login', {
      data: { email: 'healthcheck@e2e.invalid', password: 'not-a-real-password' },
      timeout: 15_000,
    });
    if (response.status() !== 401) {
      throw new Error(`expected 401 from /api/auth/login, got ${response.status()}`);
    }
  } catch (error) {
    throw new Error(
      `E2E stack is not reachable at ${baseURL} (${(error as Error).message}).\n` +
        'Start it first: ./start-infra.sh && ./start-app.sh (see README "Run E2E tests").'
    );
  } finally {
    await api.dispose();
  }
}
