---
name: add-load-test
description: Add or extend a k6 load or performance scenario for the demo claims app (code/load-tests), run it with the right profile, and read the results. Use when the user asks for a load, stress, soak or performance test of an endpoint or journey, or wants to re-measure performance after a fix.
---

# Add a k6 load scenario

The scripts are in `code/load-tests/`. k6 runs from Docker (`grafana/k6:0.54.0`) through `run.sh`, so nothing is installed locally.

## Building blocks

| File | Provides |
|---|---|
| `lib/session.js` | `login(account)`, which you may **only call in `setup()`** (Keycloak lockout); `ACCOUNTS.claimant` / `ACCOUNTS.admin`; `new Client(session)` with `get` / `post` / `patch`, which sends cookies and the CSRF header and refreshes once on 401 |
| `lib/profiles.js` | `PROFILE` (`smoke` / `load` / `stress` / `soak`), `stages('claimants' \| 'admins')`, and the shared `THRESHOLDS` |
| `claims-journey.js` | Example of mixed claimant and admin scenarios with per-endpoint thresholds |
| `dashboard-cache.js` | Example of a constant-arrival-rate A/B comparison |

## Rules

1. **Name every request** with a URL pattern (`api.get(\`/api/claims/${id}\`, 'GET /api/claims/{id}')`), so IDs don't create thousands of metric series. Add a per-endpoint threshold for each new name: reads p95 < 500 ms, writes p95 < 1000 ms.
2. **Tags in thresholds must be request tags** (`name`, `type`, `scenario`), not custom metric tags.
3. **Mark test data:** any claim the script creates gets a description starting `k6 load test claim`, so it can be found and cleaned up. Scripts must only change data they created.
4. **Think time** (1–5 s) for user journeys; `constant-arrival-rate` for throughput or A/B tests.
5. **Checks** must tolerate what concurrency legitimately changes (e.g. an admin already moved the claim).

## Run

```bash
cd code/load-tests
./run.sh <script>.js smoke      # always first: is it wired up?
./run.sh <script>.js load       # then the real profile
```

- The exit code is non-zero if a threshold fails. The JSON summary is saved in `results/`, which is gitignored.
- Sample `docker stats --no-stream` every 15 s during stress runs to record CPU and memory.

## Afterwards

1. Count the test claims (read-only):
   `docker exec demo-postgres psql -U postgres -d demo_app -c "SELECT status, count(*) FROM claims WHERE description LIKE 'k6 load test claim%' GROUP BY status;"`
   Tell the user the count. **Ask before deleting.**
2. Update `LOAD_TEST_NOTES.md`: results table, findings (P-numbers for performance findings, D-numbers for product defects), and how to run it.
3. Point out that one laptop running the stack and k6 together is not production-like. Compare trends and ratios, not absolute numbers.
