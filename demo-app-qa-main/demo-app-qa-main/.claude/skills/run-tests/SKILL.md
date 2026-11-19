---
name: run-tests
description: Run any test level of the demo claims app (unit, integration, contract, E2E, load) with the right command and environment workarounds, and report the results against the known defects. Use when the user asks to run tests, re-run a suite, check whether something still passes, or verify a fix.
---

# Run the demo-app test suites

The project root is the folder that holds `TEST_STRATEGY.md`. All paths below are relative to it.

## 1. Pick the level

| Level | Needs | Command |
|---|---|---|
| UI unit (Vitest) | Node | `cd code/demo-app-ui && npm test` (coverage: `npm run test:coverage`) |
| Backend unit | JDK | `cd code/claims-service && mvn test` / `cd code/bff-service && mvn test` |
| Backend full build (unit + coverage gate + PMD) | JDK | `mvn verify` in each service |
| Integration (Testcontainers) | Docker running | `mvn test "-Dgroups=integration" "-Dsurefire.excludedGroups="` in each service |
| Contract: consumer (writes the pact) | JDK | `cd code/bff-service && mvn test -Dtest=ClaimsServiceConsumerPactTest` |
| Contract: provider (verifies the pact) | Docker | `cd code/claims-service && mvn test -Dgroups=contract -Dsurefire.excludedGroups= -Djacoco.skip=true` |
| E2E (Playwright) | Full stack running | `cd code/demo-app-ui && npx playwright test` (one file: add the spec name; one test: `-g "title"`) |
| Load (k6 in Docker) | Full stack running + Docker | `cd code/load-tests && ./run.sh [script] [smoke\|load\|stress\|soak]` |

Run the contract **consumer before the provider** when the consumer test changed, because the provider reads `code/bff-service/pacts/`.

## 2. Environment workarounds (check these first)

- **JDK 25 + PMD:** PMD 7.7.0 fails with "Unsupported class file major version 69". Check `java -version`. On JDK 25, add `-Dpmd.skip=true -Dcpd.skip=true` to `mvn verify`, or switch to JDK 21. Report this as an environment issue, never as a test failure.
- **Integration tests are excluded by default.** Surefire excludes the `integration` group, so you must clear `surefire.excludedGroups` as shown.
- **The full stack for E2E and load:** from the project root, run `./start-infra.sh && ./start-app.sh`. Check the BFF (`curl -s localhost:8090/actuator/health`) and the UI (`localhost:3001`) before running.
- **Keycloak lockout:** repeated failed logins lock `admin@demo.com` / `claimant@demo.com`. If logins start failing in bulk, wait about a minute or restart Keycloak. Never loop logins in scripts.
- **Data from the load tests:** k6 leaves claims starting with `k6 load test claim`. Many of them slow down E2E and admin pages. Count them (read-only) before E2E runs, and tell the user if there are more than a few hundred. Only delete them with the user's OK (see LOAD_TEST_NOTES.md §5.3).
- **PowerShell:** quote `-D` arguments (`"-Dgroups=integration"`). Git Bash doesn't need the quotes.

## 3. Read the results against the known defects

These failures are **expected** until the defect is fixed. They are not regressions:

| Defect | Where it shows |
|---|---|
| D1 | Security integration tests that call claims-service directly as a claimant |
| D2 | Cache-hit integration test; `dashboard-cache.js` threshold |
| D3 | Pact interaction "invalid transition → 400"; the occasional 500 in load runs |
| D4 | Covered in the notes, not in the contract |
| D5 | E2E signup tests |
| D6 | E2E logout tests |
| D7 | E2E real-time tests |
| D8 | E2E wizard Back test |

The current list lives in TEST_STRATEGY.md §8. Always re-read it, as it may have changed.

## 4. Report

Report in this order:
1. The command run, with pass/fail/skip counts.
2. **Expected failures** (known defect IDs).
3. **New failures**: test name, the important assertion message, and a first guess (product bug, test bug, flaky, or environment).
4. Environment problems you hit and how you got around them.

Don't change production code to make a test pass. If a new failure looks like a product bug, suggest the `log-defect` skill.
