# End-to-End Tests (Playwright)

## 1. Requirement

> Write Playwright E2E tests for the main user journeys.

The tests drive the real UI in a browser against the full running stack: UI → BFF → Keycloak / claims-service → PostgreSQL, Redis, Kafka. No production code was changed. Defects are reported in section 4, not fixed.

## 2. Results

**29 tests: 22 pass, 7 fail.** Two full runs gave the same result. Every failure is a product defect (section 4), not a test problem.

| Spec | Journey | Tests | Pass | Fail |
|---|---|---|---|---|
| `auth.spec.ts` | Sign up, log in, role landing page, log out | 9 | 6 | 3 (D5 ×2, D6) |
| `claim-submission.spec.ts` | Claimant submits a claim with the 3-step wizard | 5 | 4 | 1 (D8) |
| `admin-claim-review.spec.ts` | Admin reviews a claim to a decision; claimant sees it | 4 | 4 | 0 |
| `realtime-updates.spec.ts` | Live updates over the WebSocket | 2 | 0 | 2 (D7) |
| `admin-dashboard.spec.ts` | Dashboard stats, cache demo, user search | 4 | 3 | 1 (D2) |
| `access-control.spec.ts` | Claimant cannot open admin pages | 3 | 3 | 0 |
| `auth.setup.ts` | Logs in once per role and saves the session | 2 | 2 | 0 |

Tests that fail because of a known defect say so in a comment above the test.

## 3. What was done

All files are under `code/demo-app-ui/tests/e2e/`.

### 3.1 Test coverage

- **Authentication:** an unauthenticated user is sent to login and keeps the `redirect` target. Claimant and admin land on their own pages with the right navigation. Wrong password and bad email format show errors (a bad email sends no request at all). Logout ends the session. Sign-up covers: new user, weak password, duplicate email.
- **Claim submission:** full wizard → review screen shows the entered values → submit → toast with claim id → row in My Claims → details modal. Also covers step 1 validation, the description minimum length, Back keeping entered data, and Cancel asking for confirmation.
- **Admin review:** SUBMITTED → Under Review → Approved → Closed through the UI, after which no more transitions are offered. Only allowed transitions are listed. Cancelling the confirmation saves nothing, even after a reload. A rejection is visible to the claimant in a separate browser session.
- **Real-time:** a claimant sees an admin's status change without reloading (toast and badge). An admin sees a newly submitted claim appear.
- **Admin dashboard:** stats cards are shown. Cache flush shows fresh totals. A second load is a cache hit. User search works.
- **Access control:** a claimant opening any `/admin/*` page gets "Access denied" and is sent to `/claims`.

### 3.2 Design

- **Page objects** are in `support/pages.ts`. Locators use roles and labels first, and test ids only where the UI provides them.
- **Logins are reused.** `auth.setup.ts` logs in once per role through the UI and saves the session to `tests/e2e/.auth/*.json`. That folder is gitignored, because the files contain tokens. Only the tests that are about logging in log in themselves. This is needed because Keycloak's brute-force protection locks the shared demo accounts after many rapid logins (see 5.3).
- **Test data comes from the API.** When a test needs an existing claim, or a claim in a given status, `support/api.ts` creates it through the same BFF API the UI uses, with the saved sessions. Each claim gets a unique description, so tests can run in parallel and never depend on data from other tests.
- **Readiness:** `waitUntilInteractive()` waits for the header's connection-status element. It only renders after React hydration, so clicks are not lost while the dev server is still compiling.
- **globalSetup** checks that the stack is reachable and fails with a clear message if it is not.

### 3.3 Config changes (`playwright.config.ts`)

- Added a `setup` project; the browser projects depend on it.
- The expect timeout is now 10 s, because the Docker UI runs `next dev` and compiles each page on first visit.
- Local runs use the installed Google Chrome (`channel: 'chrome'`), because Playwright's own Chromium download was not available on this machine. CI still uses bundled Chromium. Override with `PW_CHANNEL` (set it to empty for bundled Chromium).

## 4. Defects found

D1–D4 are in [INTEGRATION_TEST_NOTES.md](INTEGRATION_TEST_NOTES.md). D2 also shows up in the UI.

| ID | Severity | Defect | Found by |
|---|---|---|---|
| **D5** | High | **Sign-up fails with 500 on the Docker stack.** The Keycloak admin password is `Admin123!` (`docker-compose.infra.yml`), but the BFF's `keycloak.admin.password` defaults to `admin` and `docker-compose.apps.yml` never sets `KEYCLOAK_ADMIN_PASSWORD`. The BFF cannot create users in Keycloak (`invalid_user_credentials` for `admin-cli`). Because of this, the duplicate-email case also gets 500 instead of "Email already exists" | `auth.spec.ts` sign-up ×2 |
| **D6** | High | **Logout does not end the session.** The header calls `fetch('/api/auth/logout')` without the CSRF header. `/api/auth/logout` is not in the BFF's CSRF-ignore list, so it returns **403** and the auth cookies stay. The UI still goes to the login page, so it looks like logout worked, but `/claims` is still accessible afterwards. Confirmed with curl | `auth.spec.ts` logout |
| **D7** | High | **Real-time updates never arrive on the default stack.** The README says `APP_EVENTS_CDC_ENABLED=false` is the default, but `docker-compose.apps.yml` sets `"true"`. Debezium is not started by `start-infra.sh`/`start-app.sh`, so no events reach Kafka and no WebSocket messages are sent | `realtime-updates.spec.ts` ×2 |
| **D8** | Medium | **Going Back in the claim wizard loses the incident date.** Step 1 stores the date as a `Date` object (`valueAsDate`). When the step is shown again, the `<input type="date">` cannot display it, so the field is empty and Next is disabled until the date is entered again | `claim-submission.spec.ts` Back |
| D2 | Medium | Dashboard "Load from cache" never shows **Cache Hit** (Redis read-back fails; see integration notes) | `admin-dashboard.spec.ts` cache hit |

### Suggested fixes (need sign-off)

- **D5:** set `KEYCLOAK_ADMIN_PASSWORD: Admin123!` for the BFF in `docker-compose.apps.yml`.
- **D6:** send the `X-XSRF-TOKEN` header from the header's logout call (for example, through `bff-client`), or add `/api/auth/logout` to the CSRF-ignore list.
- **D7:** either set `APP_EVENTS_CDC_ENABLED: "false"` in `docker-compose.apps.yml`, or change the README and start Debezium as part of the default setup.
- **D8:** keep the date as the `yyyy-mm-dd` string in the form state and convert it only when submitting.

### Other observations

- **A fresh clone gets a broken UI image.** The Dockerfile does not generate `src/lib/api/generated` (gitignored), so the UI shows a "Module not found: './generated'" build-error overlay. Generate the client first, then rebuild: `docker compose -f docker-compose.apps.yml up -d --build ui`.
- **Real-time toasts link to `/claims/{id}`**, a page that does not exist.
- **The Docker UI runs in dev mode** (`next dev`). It is slow on first visit and shows Next.js dev tools.
- **The BFF correctly returns 403** to a claimant on `/api/admin/**`, so D1 cannot be reached through the UI or BFF. It is reachable only on claims-service's own port.

## 5. How to run

### 5.1 Prerequisites

The whole stack must be running (from `demo-app-qa-main/`, where the start scripts are):

```bash
./start-infra.sh && ./start-app.sh
```

For the real-time tests to pass, either set `APP_EVENTS_CDC_ENABLED=false` for claims-service (D7) or start Debezium with `./start-cdc.sh`.

### 5.2 Run

Run from `code/demo-app-ui`, not the repo root (otherwise Playwright gives the error "did not expect test.describe() to be called here"):

```powershell
cd code\demo-app-ui
npx playwright test                              # all tests
npx playwright test claim-submission.spec.ts     # one file
npx playwright test -g "admin finds a user"      # one test by name
npx playwright test --headed                     # watch it in a browser
npx playwright show-report                       # open the HTML report
```

### 5.3 Troubleshooting

- **"Executable doesn't exist … chromium_headless_shell"**: Playwright's browser is not installed. Local runs use installed Chrome, so this should not happen. Otherwise run `npx playwright install chromium`, typing the command yourself rather than copying it from the terminal box.
- **Logins return 401 / `user_temporarily_disabled`**: Keycloak has locked the demo account after too many logins. Wait a few minutes, or unlock the user in the Keycloak admin console (realm `demo-app` → Users → the user → turn off "Temporarily locked").
- **"E2E stack is not reachable"**: the stack is not running (5.1).
