// Authenticated BFF sessions for k6.
//
// The BFF keeps the Keycloak tokens in HTTP-only cookies and protects writes with a CSRF
// cookie + header, exactly as the browser uses it. We log in ONCE per account in setup():
// Keycloak's brute-force protection locks an account after many rapid logins, so virtual
// users never log in themselves. Each VU refreshes its own copy of the session on a 401
// (access tokens live 30 minutes), which keeps long soak runs going.

import http from 'k6/http';
import { fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';

export const ACCOUNTS = {
  claimant: { email: __ENV.CLAIMANT_EMAIL || 'claimant@demo.com', password: __ENV.CLAIMANT_PASSWORD || 'Claimant123!' },
  admin: { email: __ENV.ADMIN_EMAIL || 'admin@demo.com', password: __ENV.ADMIN_PASSWORD || 'Admin123!' },
};

function cookie(res, name) {
  const values = res.cookies[name];
  return values && values.length ? values[0].value : undefined;
}

/** Logs in through the BFF and returns the cookies a browser would hold. Call from setup() only. */
export function login(account) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify(account), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/auth/login' },
  });
  if (res.status !== 200) {
    fail(`login ${account.email} failed: ${res.status} ${res.body}`);
  }
  const session = {
    userId: res.json('userId'),
    access: cookie(res, 'access_token'),
    refresh: cookie(res, 'refresh_token'),
    xsrf: cookie(res, 'XSRF-TOKEN'),
  };
  if (!session.xsrf) {
    session.xsrf = http.get(`${BASE_URL}/api/auth/csrf`).json('token');
  }
  return session;
}

/**
 * A VU's handle on a session. Sends the auth and CSRF cookies explicitly (so the k6 cookie
 * jar can't mix sessions up) and transparently refreshes once on a 401.
 */
export class Client {
  constructor(session) {
    this.session = Object.assign({}, session);
  }

  params(name, write, type) {
    const s = this.session;
    const headers = {
      Accept: 'application/json',
      Cookie: `access_token=${s.access}; refresh_token=${s.refresh}; XSRF-TOKEN=${s.xsrf}`,
    };
    if (write) {
      headers['Content-Type'] = 'application/json';
      headers['X-XSRF-TOKEN'] = s.xsrf;
    }
    // "type" feeds the read/write thresholds; token refreshes are kept out of both.
    return { headers, tags: { name, type: type || (write ? 'write' : 'read') } };
  }

  refresh() {
    const res = http.post(`${BASE_URL}/api/auth/refresh`, null, this.params('POST /api/auth/refresh', false, 'auth'));
    if (res.status !== 200) {
      fail(`token refresh failed: ${res.status} ${res.body}`);
    }
    this.session.access = cookie(res, 'access_token') || this.session.access;
    this.session.refresh = cookie(res, 'refresh_token') || this.session.refresh;
  }

  /** `name` groups URLs with ids into one metric series, e.g. "GET /api/claims/{id}". */
  request(method, path, body, name) {
    const write = method !== 'GET';
    const send = () => http.request(method, `${BASE_URL}${path}`,
      body === undefined ? null : JSON.stringify(body), this.params(name, write));
    let res = send();
    if (res.status === 401) {
      this.refresh();
      res = send();
    }
    return res;
  }

  get(path, name) { return this.request('GET', path, undefined, name || `GET ${path}`); }
  post(path, body, name) { return this.request('POST', path, body, name || `POST ${path}`); }
  patch(path, body, name) { return this.request('PATCH', path, body, name || `PATCH ${path}`); }
}
