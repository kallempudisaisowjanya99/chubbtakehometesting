// Load profiles and pass/fail thresholds shared by all scripts.
//
// Pick a profile with -e PROFILE=smoke|load|stress|soak (default: smoke).
// VU counts are per scenario: claimants and admins run side by side, ~4 claimants per admin.

const PROFILES = {
  // Is everything wired up? 1 user of each kind, briefly.
  smoke: {
    claimants: [{ duration: '30s', target: 1 }],
    admins: [{ duration: '30s', target: 1 }],
  },
  // Expected peak: ramp up, hold, ramp down.
  load: {
    claimants: [
      { duration: '1m', target: 20 },
      { duration: '3m', target: 20 },
      { duration: '30s', target: 0 },
    ],
    admins: [
      { duration: '1m', target: 5 },
      { duration: '3m', target: 5 },
      { duration: '30s', target: 0 },
    ],
  },
  // Find the breaking point: keep adding users. Thresholds are expected to fail here;
  // the result to read is WHERE latency and errors start to climb.
  stress: {
    claimants: [
      { duration: '1m', target: 20 },
      { duration: '2m', target: 50 },
      { duration: '2m', target: 100 },
      { duration: '2m', target: 150 },
      { duration: '1m', target: 0 },
    ],
    admins: [
      { duration: '1m', target: 5 },
      { duration: '2m', target: 10 },
      { duration: '2m', target: 20 },
      { duration: '2m', target: 30 },
      { duration: '1m', target: 0 },
    ],
  },
  // Steady, moderate load for an hour: leaks, connection-pool exhaustion, token refresh.
  soak: {
    claimants: [
      { duration: '2m', target: 10 },
      { duration: '56m', target: 10 },
      { duration: '2m', target: 0 },
    ],
    admins: [
      { duration: '2m', target: 3 },
      { duration: '56m', target: 3 },
      { duration: '2m', target: 0 },
    ],
  },
};

export const PROFILE = __ENV.PROFILE || 'smoke';

export function stages(kind) {
  const profile = PROFILES[PROFILE];
  if (!profile) {
    throw new Error(`Unknown PROFILE "${PROFILE}". Use one of: ${Object.keys(PROFILES).join(', ')}`);
  }
  return profile[kind];
}

// Targets proposed in TEST_STRATEGY.md §4.5 (to be agreed with the product owner):
// p95 < 500 ms for reads, < 1 s for writes, < 1 % errors.
export const THRESHOLDS = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
  'http_req_duration{type:read}': ['p(95)<500'],
  'http_req_duration{type:write}': ['p(95)<1000'],
};
