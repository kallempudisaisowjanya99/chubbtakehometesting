import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * Unit tests for the BFF API client wrapper (src/lib/api/bff-client.ts).
 *
 * Every store talks to the BFF through this module, and it owns two cross-cutting behaviours that had
 * no direct tests (~20% statement coverage):
 *   1. CSRF: echoes the XSRF-TOKEN cookie back as the X-XSRF-TOKEN header (Spring Security double-submit).
 *   2. Session expiry: on 401 -> POST /api/auth/refresh -> retry once; if refresh fails -> logout and
 *      redirect to /login?session_expired=true. Concurrent 401s must share ONE refresh call.
 *
 * The module keeps refresh state at module level, so each test re-imports it via vi.resetModules().
 */

type FetchMock = ReturnType<typeof vi.fn>;

const json = (status: number, body: unknown = []) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

async function loadClient() {
  vi.resetModules();
  const client = await import('./bff-client');
  const { useAuthStore } = await import('@/src/features/auth/stores/use-auth-store');
  return { client, useAuthStore };
}

function headersOf(fetchMock: FetchMock, callIndex: number): Record<string, string> {
  const init = fetchMock.mock.calls[callIndex][1] as RequestInit | undefined;
  return (init?.headers ?? {}) as Record<string, string>;
}

function urlOf(fetchMock: FetchMock, callIndex: number): string {
  return String(fetchMock.mock.calls[callIndex][0]);
}

describe('bff-client', () => {
  let fetchMock: FetchMock;
  const originalLocation = window.location;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    // jsdom does not implement navigation; replace location so the redirect can be asserted.
    Object.defineProperty(window, 'location', {
      value: { href: 'http://localhost/claims' },
      writable: true,
      configurable: true,
    });
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    Object.defineProperty(window, 'location', { value: originalLocation, configurable: true });
  });

  describe('CSRF middleware', () => {
    it('adds X-XSRF-TOKEN header from the XSRF-TOKEN cookie (URL-decoded)', async () => {
      document.cookie = 'XSRF-TOKEN=abc%2B123; path=/';
      fetchMock.mockResolvedValue(json(200));
      const { client } = await loadClient();

      await client.claimsApi.listClaims();

      expect(headersOf(fetchMock, 0)['X-XSRF-TOKEN']).toBe('abc+123');
    });

    it('does not add the header when no XSRF-TOKEN cookie is present', async () => {
      fetchMock.mockResolvedValue(json(200));
      const { client } = await loadClient();

      await client.claimsApi.listClaims();

      expect(headersOf(fetchMock, 0)).not.toHaveProperty('X-XSRF-TOKEN');
    });
  });

  describe('base path', () => {
    it('uses NEXT_PUBLIC_BFF_URL by default', async () => {
      vi.stubEnv('USE_WIREMOCK_BFF', '');
      vi.stubEnv('NEXT_PUBLIC_BFF_URL', 'http://bff.test');
      fetchMock.mockResolvedValue(json(200));
      const { client } = await loadClient();

      await client.claimsApi.listClaims();

      expect(urlOf(fetchMock, 0)).toMatch(/^http:\/\/bff\.test\//);
    });

    it('uses NEXT_PUBLIC_BFF_STUB_URL when USE_WIREMOCK_BFF=true', async () => {
      vi.stubEnv('USE_WIREMOCK_BFF', 'true');
      vi.stubEnv('NEXT_PUBLIC_BFF_STUB_URL', 'http://stub.test');
      fetchMock.mockResolvedValue(json(200));
      const { client } = await loadClient();

      await client.claimsApi.listClaims();

      expect(urlOf(fetchMock, 0)).toMatch(/^http:\/\/stub\.test\//);
    });
  });

  describe('401 handling (token refresh)', () => {
    it('passes non-401 responses through without refreshing', async () => {
      fetchMock.mockResolvedValue(json(200, [{ claimId: 'c1' }]));
      const { client } = await loadClient();

      const result = await client.claimsApi.listClaims();

      expect(result).toHaveLength(1);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('refreshes the token and retries the original request once on 401', async () => {
      fetchMock
        .mockResolvedValueOnce(json(401, {}))  // original
        .mockResolvedValueOnce(json(200, {}))  // POST /api/auth/refresh
        .mockResolvedValueOnce(json(200, [{ claimId: 'c1' }])); // retry
      const { client, useAuthStore } = await loadClient();
      const logout = vi.spyOn(useAuthStore.getState(), 'logout');

      const result = await client.claimsApi.listClaims();

      expect(result).toHaveLength(1);
      expect(fetchMock).toHaveBeenCalledTimes(3);
      expect(urlOf(fetchMock, 1)).toBe('/api/auth/refresh');
      expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: 'POST', credentials: 'include' });
      expect(urlOf(fetchMock, 2)).toBe(urlOf(fetchMock, 0));
      expect(logout).not.toHaveBeenCalled();
      expect(window.location.href).toBe('http://localhost/claims');
    });

    it('logs out and redirects to /login?session_expired=true when refresh is rejected', async () => {
      fetchMock
        .mockResolvedValueOnce(json(401, {}))  // original
        .mockResolvedValueOnce(json(401, {})); // refresh rejected
      const { client, useAuthStore } = await loadClient();
      useAuthStore.setState({ user: { email: 'a@b.c' } as never, isAuthenticated: true });

      await expect(client.claimsApi.listClaims()).rejects.toThrow();

      expect(fetchMock).toHaveBeenCalledTimes(2); // no retry after failed refresh
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
      expect(useAuthStore.getState().user).toBeNull();
      expect(window.location.href).toBe('/login?session_expired=true');
    });

    it('logs out and redirects when the refresh call itself throws (network error)', async () => {
      fetchMock
        .mockResolvedValueOnce(json(401, {}))
        .mockRejectedValueOnce(new TypeError('Failed to fetch'));
      const { client, useAuthStore } = await loadClient();
      useAuthStore.setState({ user: { email: 'a@b.c' } as never, isAuthenticated: true });

      await expect(client.claimsApi.listClaims()).rejects.toThrow();

      expect(useAuthStore.getState().isAuthenticated).toBe(false);
      expect(window.location.href).toBe('/login?session_expired=true');
    });

    it('shares a single refresh call between concurrent 401 responses', async () => {
      let resolveRefresh!: (r: Response) => void;
      const refreshResponse = new Promise<Response>((r) => { resolveRefresh = r; });

      fetchMock.mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === '/api/auth/refresh') return refreshResponse;
        const attempt = fetchMock.mock.calls.filter((c) => String(c[0]) === url).length;
        // first call for each URL -> 401, retry -> 200
        return Promise.resolve(attempt === 1 ? json(401, {}) : json(200, []));
      });
      const { client } = await loadClient();

      const first = client.claimsApi.listClaims();
      const second = client.claimsApi.getClaim({ claimId: '00000000-0000-0000-0000-000000000001' });

      // let both requests hit their 401 before the refresh resolves
      await vi.waitFor(() => {
        expect(fetchMock.mock.calls.filter((c) => String(c[0]) === '/api/auth/refresh')).toHaveLength(1);
      });
      resolveRefresh(json(200, {}));
      await Promise.allSettled([first, second]);

      const refreshCalls = fetchMock.mock.calls.filter((c) => String(c[0]) === '/api/auth/refresh');
      expect(refreshCalls).toHaveLength(1);
    });
  });
});
