// Main load test: claimants and admins using the app at the same time, through the BFF,
// the way the UI calls it.
//
//   claimants: open My Claims -> submit a claim -> open the claim
//   admins:    open the dashboard -> list submitted claims -> review one -> sometimes list users
//
// Run (see README.md): ./run.sh claims-journey.js load

import { check, group, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { ACCOUNTS, Client, login } from './lib/session.js';
import { PROFILE, stages, THRESHOLDS } from './lib/profiles.js';

export const options = {
  scenarios: {
    claimants: { executor: 'ramping-vus', exec: 'claimant', stages: stages('claimants'), gracefulRampDown: '10s' },
    admins: { executor: 'ramping-vus', exec: 'admin', stages: stages('admins'), gracefulRampDown: '10s' },
  },
  // One threshold per request type, so the summary shows (and exports) each endpoint separately.
  thresholds: Object.assign({}, THRESHOLDS, {
    'http_req_duration{name:GET /api/claims}': ['p(95)<500'],
    'http_req_duration{name:POST /api/claims}': ['p(95)<1000'],
    'http_req_duration{name:GET /api/claims/{id}}': ['p(95)<500'],
    'http_req_duration{name:GET /api/admin/dashboard/stats}': ['p(95)<500'],
    'http_req_duration{name:GET /api/admin/claims?status}': ['p(95)<500'],
    'http_req_duration{name:PATCH /api/admin/claims/{id}/status}': ['p(95)<1000'],
    'http_req_duration{name:GET /api/admin/users}': ['p(95)<500'],
  }),
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: { profile: PROFILE },
};

const claimsCreated = new Counter('claims_created');
const statusChanges = new Counter('claim_status_changes');
// The admin claims list has no paging, so its payload grows with every claim in the system.
const adminListBytes = new Trend('admin_claims_list_bytes');
const adminListSize = new Trend('admin_claims_list_items');

export function setup() {
  return { claimant: login(ACCOUNTS.claimant), admin: login(ACCOUNTS.admin) };
}

function thinkTime(min = 1, max = 3) {
  sleep(min + Math.random() * (max - min));
}

function isoDaysAgo(days) {
  return new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);
}

let claimantClient;
let adminClient;

export function claimant(data) {
  claimantClient = claimantClient || new Client(data.claimant);
  const api = claimantClient;
  let claimId;

  group('My Claims', () => {
    const res = api.get('/api/claims');
    check(res, {
      'list claims: 200': (r) => r.status === 200,
      'list claims: is a list': (r) => Array.isArray(r.json()),
    });
  });
  thinkTime();

  group('Submit a claim', () => {
    const res = api.post('/api/claims', {
      incidentDate: isoDaysAgo(1 + Math.floor(Math.random() * 30)),
      incidentLocation: `${100 + __VU} Load Test Street, Springfield`,
      description: `k6 load test claim (VU ${__VU}, iteration ${__ITER}): windscreen cracked by road debris.`,
      claimAmount: Math.round((100 + Math.random() * 9900) * 100) / 100,
    });
    if (check(res, { 'submit claim: 201': (r) => r.status === 201 })) {
      claimId = res.json('claimId');
      claimsCreated.add(1);
    }
  });
  thinkTime();

  if (claimId) {
    group('Claim details', () => {
      const res = api.get(`/api/claims/${claimId}`, 'GET /api/claims/{id}');
      check(res, {
        'claim details: 200': (r) => r.status === 200,
        // An admin VU may already have picked it up for review.
        'claim details: is SUBMITTED or UNDER_REVIEW': (r) => ['SUBMITTED', 'UNDER_REVIEW'].includes(r.json('status')),
      });
    });
  }
  thinkTime(2, 5);
}

export function admin(data) {
  adminClient = adminClient || new Client(data.admin);
  const api = adminClient;

  group('Dashboard', () => {
    const res = api.get('/api/admin/dashboard/stats');
    check(res, { 'dashboard: 200': (r) => r.status === 200 });
  });
  thinkTime();

  let candidates = [];
  group('Review queue', () => {
    const res = api.get('/api/admin/claims?status=SUBMITTED', 'GET /api/admin/claims?status');
    if (check(res, { 'admin claims: 200': (r) => r.status === 200 })) {
      const claims = res.json();
      adminListBytes.add(res.body.length);
      adminListSize.add(claims.length);
      // Only claims this test created, so seeded or manual data is never touched.
      candidates = claims.filter((c) => c.description.startsWith('k6 load test claim'));
    }
  });
  thinkTime();

  if (candidates.length) {
    group('Review a claim', () => {
      // Random pick keeps admins from racing for the same claim.
      const claim = candidates[Math.floor(Math.random() * candidates.length)];
      const res = api.patch(`/api/admin/claims/${claim.claimId}/status`, { newStatus: 'UNDER_REVIEW' },
        'PATCH /api/admin/claims/{id}/status');
      if (check(res, { 'status change: 200': (r) => r.status === 200 })) {
        statusChanges.add(1);
      }
    });
    thinkTime();
  }

  if (Math.random() < 0.2) {
    group('Users', () => {
      check(api.get('/api/admin/users'), { 'users: 200': (r) => r.status === 200 });
    });
  }
  thinkTime(2, 5);
}
