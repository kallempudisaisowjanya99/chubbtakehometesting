# Test Strategy: Insurance Claims Management Demo App

| | |
|---|---|
| **Scope** | UI (Next.js), BFF, claims-service, and their infrastructure (Keycloak, PostgreSQL, Redis, Kafka, Debezium) |
| **Related** | [UNIT_TEST_NOTES.md](UNIT_TEST_NOTES.md) · [INTEGRATION_TEST_NOTES.md](INTEGRATION_TEST_NOTES.md) · [CONTRACT_TEST_NOTES.md](CONTRACT_TEST_NOTES.md) · [E2E_TEST_NOTES.md](E2E_TEST_NOTES.md) · [LOAD_TEST_NOTES.md](LOAD_TEST_NOTES.md) · [ARCHITECTURE.md](ARCHITECTURE.md) |

## 1. Purpose

This document sets out how the application is tested: what we test, at which level, with which tools, and what "good enough to release" means. The per-level notes linked above record the results.

The goal is to find, as cheaply and as early as possible, any defect that could:

1. let a user see or change data they should not (claims hold personal and financial data);
2. corrupt a claim or its status history;
3. break a core user journey (sign up, submit a claim, review a claim);
4. make the system silently wrong, for example stale data or lost events.

## 2. System under test

```
Browser ──► UI :3001 ──► BFF :8090 ──► claims-service :8080 ──► PostgreSQL
              ▲            │  ▲                 │                 │
              └─WebSocket──┘  └──── Kafka ◄─────┴─(direct or CDC)─┘
                              Keycloak (OIDC) · Redis (dashboard cache)
```

Key behaviours:
- **Roles:** a claimant sees only their own claims. An admin sees every claim and the dashboard, and can change a claim's status.
- **Claim lifecycle:** status changes follow a fixed transition map (`ClaimStatus.canTransitionTo`). CLOSED is final.
- **Events:** a claim change is published to Kafka, either directly or through Debezium CDC. The BFF consumes it and pushes it to browsers over a WebSocket.
- **Cache:** dashboard stats are cached in Redis with a TTL, and an admin can flush the cache.
- **Security:** the BFF holds the session (HTTP-only cookies), applies CSRF protection, and relays the JWT to claims-service. claims-service validates the JWT on its own.

## 3. Risk-based priorities

Each area gets test effort in proportion to its likelihood × impact.

| Area | Risk | Priority | Main levels |
|---|---|---|---|
| Authorization (role checks, own-claims only) | Data exposure across users | **Critical** | Integration, E2E, security |
| Claim status transitions | Wrong or irreversible decisions | **Critical** | Unit, integration, E2E |
| Authentication and session (login, logout, CSRF, token refresh) | Account takeover, sessions that never end | **High** | Integration, E2E |
| BFF ↔ claims-service API compatibility | A release breaks the other service | **High** | Contract |
| Event flow (Kafka → WebSocket) | Users see stale state | **High** | Integration, E2E |
| Claim submission validation | Bad data stored | High | Unit (UI and backend), E2E |
| Dashboard cache | Wrong or stale stats | Medium | Integration, E2E |
| Performance under load | Slow responses or outages at peak | Medium | Load |
| Accessibility, UI layout | Users excluded, poor experience | Medium | Unit (RTL), E2E, manual |

## 4. Test levels

The suite is a test pyramid: many fast, isolated tests at the bottom, and a few slow, realistic ones at the top. Each behaviour is tested at the **lowest level that can catch the defect**. Higher levels check how the parts fit together; they don't repeat logic already tested below.

| Level | What it proves | Tools | Runs against | Status |
|---|---|---|---|---|
| **Unit** | Business rules, mapping, validation, components, in isolation | JUnit 5, Mockito, AssertJ, JaCoCo · Vitest, React Testing Library | Nothing external | ✅ 728 tests, all pass |
| **Integration** | A service works with its real infrastructure: DB schema and queries, Redis, Kafka, JWT security | Spring Boot Test + **Testcontainers** | Real Postgres, Redis, Kafka in Docker | ✅ Done; found D1–D4 |
| **Contract** | The BFF and claims-service agree on the API, without deploying both | **Pact** (BFF = consumer, claims-service = provider; already in the POMs) | Mock provider; provider verification on Testcontainers | ✅ 15 interactions; 14 verify, 1 fails (D3) |
| **End-to-end** | Main user journeys work through the real UI on the full stack | **Playwright** | Full Docker stack | ✅ 29 tests; found D5–D8 |
| **Performance** | Response times and errors under expected and peak load | **k6** | Full stack (perf-sized) | ✅ Smoke, load and stress run; load passes, stress breaks the read target (P1: no paging) |
| **Security** | Authorization, CSRF, input handling, dependency CVEs | Integration and E2E negative tests, OWASP ZAP baseline, `mvn dependency-check` / `npm audit` | Integration env / full stack | 🟡 Part done (authorization and CSRF found D1, D6) |
| **Manual / exploratory** | Usability, visual issues, edge cases automation misses | Written manual test scripts, time-boxed exploratory sessions | Full stack | ⏳ Planned |

### 4.1 Unit
- Backend: domain rules (the transition map, validation), use cases, mappers and exception handlers.
- UI: Zod schemas, hooks, stores and components.
- Gate: JaCoCo **≥ 90 % line and branch** for each backend service; Vitest coverage thresholds for the UI.
- Coverage is a floor, not the goal: tests target rules and failure paths.

### 4.2 Integration
- Each service runs against containers, not in-memory fakes:
  - real Flyway migrations on Postgres;
  - real Redis serialization (this is how D2 was found);
  - real Kafka publish and consume;
  - real Spring Security with signed test JWTs (this is how D1 was found).
- Container images are pinned, so results are repeatable.

### 4.3 Contract
- **Consumer side (done):** the BFF's real client code is tested against a Pact mock server for every claims-service call it makes (claims, users, admin), including the error responses it branches on (400, 403, 404, 409).
- **Provider side (done):** claims-service verifies the pact on the Testcontainers stack, with real Keycloak tokens and state handlers that create the data.
- **Planned:** share pacts through a Pact Broker (or as CI artifacts), and gate each release with `can-i-deploy`.
- **Events:** contract-test the Kafka messages against the existing AsyncAPI schemas (Pact message pacts, or schema validation, which is currently turned off).
- Contract tests take over the job of the WireMock stubs in `bff-service/mocks`. Without contract tests, those stubs can drift out of date.

### 4.4 End-to-end
- Only the main journeys are covered:
  - authentication;
  - claim submission;
  - admin review;
  - real-time updates;
  - admin dashboard;
  - access control.
- Edge cases belong at lower levels.
- Each test sets up its own data through the API and uses unique values, so tests can run in parallel and in any order.
- Logins are reused through Playwright storage state, which avoids Keycloak's brute-force lockout.
- Chromium runs on every build; Firefox runs in CI.

### 4.5 Performance (k6, `code/load-tests/`)
- **Scenarios** (through the BFF, as the UI calls it):
  - claimants browse, submit and view claims while admins review them at the same time;
  - the dashboard with and without the cache (`dashboard-cache.js`).
- Load profiles: smoke (1 user), load (expected peak), stress (find the breaking point), soak (1 h, to catch leaks and pool exhaustion). Smoke, load and stress have been run; soak is built but not run yet.
- **Proposed thresholds, to be agreed with the product owner:**
  - p95 < 500 ms for reads and < 1 s for writes, overall and per endpoint;
  - error rate < 1 %;
  - a WebSocket event delivered within 2 s (not yet scripted).
- **Results:**
  - Load (25 users) passes every threshold.
  - Stress (180 users) breaks the read target: p95 585 ms.
  - Main finding P1: the claim lists have no paging. The admin list now takes 0.5–0.9 s even for a single user, and it gets slower as claims accumulate.
  - D2 confirmed under load: the cache hit rate is 0 %.
  - D3 shows up when two admins review the same claim.
  - See [LOAD_TEST_NOTES.md](LOAD_TEST_NOTES.md).
- Watch: DB connections, Kafka consumer lag, Redis hit rate, JVM heap.

### 4.6 Security
- **Authorization matrix:** test every endpoint × {anonymous, claimant, other claimant, admin} at the BFF and **directly at claims-service**, because port 8080 is reachable on its own.
- **CSRF:** every state-changing BFF route needs the header. Logout is included (D6).
- **Input:** test oversized and malicious input (XSS in the description, SQL metacharacters), and that errors don't leak internal details.
- **Automated scans:** an OWASP ZAP baseline scan of the UI and BFF, plus dependency CVE scans in CI.

### 4.7 Manual and exploratory (planned)
- Scripted manual tests cover what is hard to automate cheaply:
  - visual layout on mobile and desktop;
  - screen-reader and keyboard navigation;
  - toast and real-time behaviour across two browsers;
  - multi-tab sessions.
- Exploratory sessions are time-boxed (60–90 min), each with a charter (e.g. "break the claim wizard", "abuse session expiry"), and findings are logged as defects or new automated tests.

## 5. Test environments and data

| Environment | Used for | Notes |
|---|---|---|
| Developer machine, no Docker | Unit | Seconds; runs on every change |
| Testcontainers (Docker) | Integration, contract provider verification | Fresh containers for each test class; no shared state |
| Full local stack (`start-infra.sh` + `start-app.sh`) | E2E, manual, exploratory | Seeded users: `admin@demo.com`, `claimant@demo.com` |
| Perf environment (production-like sizing) | Load | Not local: results from a laptop are not representative |

**Test data rules:**
- Each test creates the data it needs and tags it with unique values (faker plus a random suffix).
- Tests never depend on data created by another test.
- The seeded accounts are shared and must not be locked or changed by tests.
- Secrets used by tests (saved sessions) are gitignored and never committed.

**Environment findings to fix:**
- The CDC flag in compose doesn't match the README (D7).
- The BFF's Keycloak admin password doesn't match Keycloak's (D5).
- The UI image needs the generated API client before it can be built.
- The UI container runs in dev mode.

## 6. CI pipeline (proposed)

| Stage | Trigger | Contents | Must pass to merge |
|---|---|---|---|
| 1. Fast checks | Every push / PR | Lint, type-check, Checkstyle/PMD, unit tests and coverage gates | ✅ |
| 2. Integration | Every PR | Testcontainers suites for both services | ✅ |
| 3. Contract | Every PR | Pact consumer tests → publish; provider verification; `can-i-deploy` | ✅ |
| 4. E2E | Every PR (Chromium); nightly (Chromium and Firefox) | Build images → start the stack → Playwright → upload the HTML report and traces | ✅ |
| 5. Security scan | Nightly | ZAP baseline, dependency CVE scan | Report; block on High |
| 6. Performance | Nightly / before release | k6 load profile against the perf environment | Block on threshold breach |

- Flaky tests are quarantined within one day, with a ticket. They are never just retried until green.
- Playwright retries (2 in CI) are a safety net, and any retry is reported.

## 7. Entry and exit criteria

**Entry (testing of a change starts):**
- the change builds;
- its unit tests pass;
- the environment is healthy (`/actuator/health` returns UP for both services, and the stack reachability check in E2E global setup passes).

**Exit (a release can go out):**
- all CI gates pass, and coverage gates are met;
- no open **Critical** or **High** defects (or each one is explicitly accepted by the product owner with a documented reason);
- every Critical/High risk area in §3 has passing automated tests at the levels listed;
- performance thresholds are met;
- the manual regression checklist is done for UI changes.

## 8. Defect management

**Severity:**

| Severity | Definition | Example |
|---|---|---|
| Critical | Security breach, data loss or corruption, a core journey fully blocked | — |
| High | A core feature broken or a security weakness, with a workaround or limited reach | D1, D5, D6, D7 |
| Medium | A feature partly wrong, or the API breaks its spec | D2, D3, D8 |
| Low | Cosmetic, or a minor deviation from the spec | D4 |

**Each defect report includes:** steps to reproduce, expected vs actual, environment, and evidence (logs, Playwright trace, failing test name).

**When a defect is found by an automated test:**
- the test stays in the suite and fails visibly;
- a comment links the test to the defect;
- the test is never disabled to make the build green.

**Defects found so far:**

| ID | Severity | Summary |
|---|---|---|
| D1 | High | claims-service doesn't enforce the admin role on its own API |
| D2 | Medium | The dashboard cache never returns a hit (Redis read-back fails) |
| D3 | Medium | An invalid status transition returns 500 instead of 400 |
| D4 | Low | An unknown user returns 400 instead of 404 |
| D5 | High | Sign-up returns 500 on the Docker stack (Keycloak admin password mismatch) |
| D6 | High | Logout returns 403 (missing CSRF header) and the session stays active |
| D7 | High | No real-time updates on the default stack (CDC enabled, Debezium not started) |
| D8 | Medium | The claim wizard loses the incident date on Back |

## 9. Metrics and reporting

- **Pass rate and flake rate** for each suite, per build, with the trend over time.
- **Coverage:** line and branch for each service, and changed-line coverage on PRs.
- **Defects:** found per level, open by severity, and escaped defects (found after release) as the main signal of test-strategy gaps.
- **Performance:** p95/p99 latency and error rate per scenario, compared with the previous release.
- Reports come from JaCoCo HTML, Vitest coverage, the Playwright HTML report (with traces), and the k6 summary. They are uploaded as CI artifacts.

## 10. Risks and assumptions

| Risk or assumption | Mitigation |
|---|---|
| Keycloak brute-force protection locks the shared accounts during test runs | Reuse sessions (storage state); create new users for login tests once D5 is fixed |
| The E2E suite needs the full stack, so it is slow to start and can be flaky | Health-check global setup; keep E2E to the main journeys only |
| Two event paths (direct and CDC) behave differently | Test both: integration tests use direct publishing; add a CDC profile run to nightly E2E |
| claims-service is reachable without going through the BFF | Security tests target claims-service directly (D1) |
| No agreed performance targets | The thresholds in §4.5 are proposals, to be confirmed. Load tests write to the shared demo DB, so run them in a separate environment |
| The demo environment is not production-like (dev mode, in-memory Keycloak) | Run performance and security scans against a production-like build |

## 11. Next steps

1. Fix D1–D8 (with sign-off), then rerun all suites to confirm.
2. Publish pacts to a Pact Broker and add Kafka message pacts (§4.3).
3. Add paging to the claim lists (P1), then rerun stress and run soak; add a WebSocket load scenario (§4.5).
4. Write the manual test scripts (§4.7).
5. Set up the CI pipeline (§6).
