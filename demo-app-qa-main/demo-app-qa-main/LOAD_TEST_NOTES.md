# Load and Performance Tests (k6)

## 1. Requirement

> Add load/performance tests (K6, Gatling, etc.)

**k6** was chosen. The scripts are plain JavaScript and run from the official Docker image, so no local install is needed. Thresholds turn each run into a pass or fail, so the same scripts can gate CI. The tests drive the **BFF API the way the UI does**: cookie session, CSRF header, the same endpoints and payloads. The whole path is measured: BFF → Keycloak JWT → claims-service → PostgreSQL / Redis / Kafka.

No production code was changed.

## 2. What was built (`code/load-tests/`)

| File | Purpose |
|---|---|
| `claims-journey.js` | Main test. **Claimants** (open My Claims → submit a claim → open it) and **admins** (dashboard → review queue → move a claim to UNDER_REVIEW → sometimes the user list) run at the same time, about 4 claimants per admin, with 1–5 s think time |
| `dashboard-cache.js` | Fixed rate of 20 requests/s: 1 min of cached dashboard requests, then 1 min with `bypassCache=true`. Measures the cache hit rate and the latency difference |
| `lib/session.js` | Logs in **once per account** in `setup()` and passes the cookies to the virtual users (VUs). Refreshes a VU's token on 401 (tokens last 30 min). Adds the CSRF header to writes |
| `lib/profiles.js` | Load profiles and thresholds |
| `run.sh` | Runs k6 in Docker (`grafana/k6:0.54.0`) against the stack on the host. Works from Git Bash on Windows |

**Profiles** (`PROFILE=…`):

| Profile | Claimant / admin VUs | Duration | Purpose |
|---|---|---|---|
| `smoke` | 1 / 1 | 30 s | Is it wired up? |
| `load` | 20 / 5 | 4.5 min | Expected peak |
| `stress` | ramps to 150 / 30 | 8 min | Find the breaking point |
| `soak` | 10 / 3 | 60 min | Leaks, pool exhaustion, token refresh |

**Thresholds** (the proposed targets from TEST_STRATEGY.md §4.5):
- p95 < 500 ms for reads and < 1 s for writes, overall and per endpoint;
- < 1 % failed requests;
- > 99 % of checks pass.

**Design notes:**
- VUs never log in. Keycloak's brute-force protection locks the demo accounts after rapid logins (seen in the E2E work), so `setup()` logs in once per account.
- Signup is broken (D5), so all claimant VUs share the demo claimant account. Per-user data is therefore far more concentrated than in real traffic. See 4.3.
- Admins only change claims this test created (descriptions start with `k6 load test claim`). They pick one at random, so two admins rarely pick the same claim.

## 3. Results

Measured on one Windows laptop running the full Docker stack, which also runs k6. Treat the absolute numbers as indicative only. The **trends and ratios** are what to compare.

| Run | Requests | Throughput | p95 read | p95 write | Errors | Thresholds |
|---|---|---|---|---|---|---|
| smoke | 28 | 0.8 /s | 49 ms | 24 ms | 0 | ✅ pass (first run) |
| load (25 VUs) | 2,195 | 7.9 /s | 51 ms | 21 ms | 1 (0.04 %) | ✅ pass |
| dashboard-cache | 2,402 | 18.5 /s | – | – | 0 | ❌ **cache hit rate 0 %** (D2) |
| stress (up to 180 VUs) | 15,290 | 31.3 /s | **585 ms** | 57 ms | 1 (0.01 %) | ❌ read p95 |
| smoke (after stress, ~5,400 claims) | 27 | – | **550 ms** | 15 ms | 0 | ❌ admin claims list |

The soak profile is built but was not run (60 min).

**Stress run detail:**
- **Latency:** read p99 1.46 s, max 3.64 s. Writes stayed fast (p95 57 ms).
- **CPU, sampled every 15 s:** claims-service peaked at **≈ 560 %** (about 5.5 cores); the BFF peaked at ≈ 230 %.
- **Memory:** the BFF heap grew from 544 MB to **1.47 GB** and stayed there after the run. That is normal JVM behaviour, but the soak profile should check that it levels off.

## 4. Findings

### P1: The claim lists have no paging, so they get slower with every claim (High)

`GET /api/admin/claims` and `GET /api/claims` return **every** matching claim in one response.
- **During the stress run,** the admin "Submitted" list grew from about 900 to **4,616 claims (1.8 MB)**.
- **Measured one at a time with no load,** after the runs:

  | Endpoint | Response | Time |
  |---|---|---|
  | `GET /api/admin/claims` (all) | 2.2 MB | **0.88 s** |
  | `GET /api/admin/claims?status=SUBMITTED` | 1.8 MB | **0.53 s** |
  | `GET /api/claims` (one claimant) | 1.3 MB | 0.10 s |
  | `GET /api/admin/dashboard/stats` | 160 B | 0.01 s |

- A single admin at smoke load now misses the 500 ms read target. Every other endpoint stays under 165 ms.
- This is the main cause of the read-latency failure in the stress run (most of the 116 MB the load run downloaded was this list). It also drives the claims-service CPU peak: each response maps thousands of claims and joins user names and emails.
- **The response time grows with the size of the database, not with the number of users.** A real claims system would reach this with no extra traffic at all.
- **Fix:** add paging (`page`, `size`, a total count) to both list endpoints, plus a database index on `(status, created_at)`. The UI then loads page by page.

### P2: The dashboard cache never returns a hit (D2 under load)

`dashboard-cache.js`, 20 requests/s:

| Phase | Cache hit rate | avg | p95 |
|---|---|---|---|
| Cached (default) | **0 % (0 / 1,200)** | 13.3 ms | 14.5 ms |
| `bypassCache=true` | – | 11.0 ms | 11.6 ms |

- Every "cached" request reads Redis, fails to deserialize the value (D2), and then queries the database anyway, so it is **slower than bypassing the cache**.
- The dashboard query is cheap at this data size, so users don't notice yet. But the cache gives no protection: dashboard load goes straight to PostgreSQL.
- Fix D2 (see INTEGRATION_TEST_NOTES.md), then rerun this script; the threshold expects > 90 % hits.

### P3: Two admins changing the same claim → 500 (D3 under concurrency)

In both the load and stress runs, one status change failed. Two admin VUs picked the same SUBMITTED claim at the same moment. The second got `Cannot transition from UNDER_REVIEW to UNDER_REVIEW`, which returns **500** because of D3.

This is a realistic case: two admins working the same review queue. The admin should get a clear 400 or 409 ("this claim was already moved"), not a server error. Fixing D3 solves the status code. Also consider optimistic locking (a version check), so two simultaneous updates can't both start from the same old status.

### Observations

- **Writes scale well.** Claim submission p95 stayed under 60 ms even at 180 VUs, and Kafka publishing didn't hold back writes at this rate.
- **The tests pollute the shared demo database.** Every run adds claims to the demo claimant account (about 5,000 after these runs). The UI's My Claims and admin pages, and the E2E tests that use that account, become slower to load. Performance tests belong in a separate environment with production-like data. Locally, clean up after a run (5.3).

## 5. How to run

### 5.1 Prerequisites

- The full stack is running (`./start-infra.sh && ./start-app.sh`) and the BFF is healthy on port 8090.
- Docker. k6 itself is not needed locally.

### 5.2 Run

```bash
cd code/load-tests
./run.sh                                  # claims-journey.js, smoke
./run.sh claims-journey.js load           # expected peak (4.5 min)
./run.sh claims-journey.js stress         # breaking point (8 min)
./run.sh claims-journey.js soak           # 60 min
./run.sh dashboard-cache.js               # cache effectiveness (2 min); RATE=… to change the request rate
```

- k6 prints a summary with every threshold marked ✓ or ✗. The exit code is non-zero if any threshold fails, so CI can gate on it.
- The full metrics are saved in `results/<script>-<profile>.json` (gitignored).
- Use `BASE_URL=…` to target another environment.

### 5.3 Clean up test data

Every run leaves claims behind. To remove them from the local demo database:

```bash
docker exec demo-postgres psql -U postgres -d demo_app \
  -c "DELETE FROM claims WHERE description LIKE 'k6 load test claim%';"
```

Run the same query with `SELECT count(*)` first to check what it will remove. If other tables reference claims (e.g. a status history), delete those rows first.

## 6. Next steps

1. After P1 is fixed (paging), rerun `stress` to find the real limit, and run `soak` for an hour.
2. Run against a production-like environment with seeded data volumes (e.g. 100k claims), not a laptop.
3. Add these runs to CI: `smoke` on each PR, `load` nightly.
4. Add a WebSocket scenario (k6 `ws`): many connected admins receiving claim events, to load-test the Kafka → BFF → WebSocket fan-out.
