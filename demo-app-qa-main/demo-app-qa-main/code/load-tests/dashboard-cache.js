// Does the Redis cache take load off the database for the admin dashboard?
//
// Two back-to-back phases at the same fixed request rate:
//   1. cached:   GET /api/admin/dashboard/stats              (should be served from Redis)
//   2. database: GET /api/admin/dashboard/stats?bypassCache=true
//
// Compare the two latency series, and read `dashboard_cache_hit`: after the first request
// the cached phase should be close to 100 % hits (cache TTL is 5 minutes).
//
// Run: ./run.sh dashboard-cache.js        (PROFILE is ignored; fixed 2 x 1 minute)

import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { ACCOUNTS, Client, login } from './lib/session.js';

const RATE = Number(__ENV.RATE || 20); // requests per second

export const options = {
  scenarios: {
    cached: {
      executor: 'constant-arrival-rate', exec: 'cached', rate: RATE, timeUnit: '1s',
      duration: '1m', preAllocatedVUs: 10, maxVUs: 50,
    },
    database: {
      executor: 'constant-arrival-rate', exec: 'database', rate: RATE, timeUnit: '1s',
      duration: '1m', startTime: '1m10s', preAllocatedVUs: 10, maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'dashboard_cache_hit{phase:cached}': ['rate>0.9'],
    // k6 tags every request with its scenario name.
    'http_req_duration{scenario:cached}': ['p(95)<100'],
    'http_req_duration{scenario:database}': ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const cacheHit = new Rate('dashboard_cache_hit');

export function setup() {
  return { admin: login(ACCOUNTS.admin) };
}

let api;

function stats(data, path, phase) {
  api = api || new Client(data.admin);
  const res = api.get(path, 'GET /api/admin/dashboard/stats');
  if (check(res, { 'dashboard: 200': (r) => r.status === 200 })) {
    cacheHit.add(res.json('cacheHit') === true, { phase });
  }
}

export function cached(data) {
  stats(data, '/api/admin/dashboard/stats', 'cached');
}

export function database(data) {
  stats(data, '/api/admin/dashboard/stats?bypassCache=true', 'database');
}
