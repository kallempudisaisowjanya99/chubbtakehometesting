---
name: add-e2e-test
description: Write a new Playwright end-to-end test for the demo claims app UI following the suite's conventions (shared page objects, API set-up helpers, saved logins, unique test data). Use when the user asks for an E2E or Playwright test, a browser test for a user journey, or to automate a manual script.
---

# Add a Playwright E2E test

The tests are in `code/demo-app-ui/tests/e2e/`. Read the existing spec closest to the new journey before writing anything.

## Building blocks (reuse them, don't duplicate them)

| File | Use it for |
|---|---|
| `support/test-data.ts` | `ADMIN` and `CLAIMANT` credentials; `STORAGE_STATE` (saved logins); `newClaim(overrides)` for unique claim data; `newClaimantData()`; `isoDaysAgo(n)` |
| `support/api.ts` | Set-up through the API, not the UI: `submitClaim(claim)` (as the claimant) returns the claim ID; `setClaimStatus(id, status)` (as the admin); `apiSessionFor(role)` for anything else. Handles the CSRF header |
| `support/pages.ts` | Page objects: `LoginPage`, `AppHeader`, `ClaimWizard`, `MyClaimsPage`, `AdminClaimsPage`; `loginAs`, `open`, `waitUntilInteractive`, `statValue` |
| `support/format.ts` | `shortDate`, `longDate`, `usd`, so expected values match what the UI shows |
| `auth.setup.ts` | Logs in once per role and saves the state. Tests reuse it with `test.use({ storageState: STORAGE_STATE.admin })` |

## Rules

1. **Log in through saved state**, never through the form, except in tests *about* login. Keycloak locks accounts after rapid logins.
2. **Set up data through the API**, and only drive the UI for the part under test.
3. **Every test creates its own data** with unique values, so tests run in parallel and in any order. Never depend on seeded claims, and never assume a list is empty.
4. **Locators:** role and label first (`getByRole`, `getByLabel`), scoped to a region (e.g. `page.getByRole('main')`) when the header has a button with the same name. Add new locators to the page object, not inline in specs.
5. **No fixed waits** (`waitForTimeout`). Use web-first assertions (`await expect(...).toBeVisible()`). Real-time checks can use a timeout of a few seconds on the assertion.
6. **A test that fails because of a known defect** stays in the suite with a comment `// Fails until D<n> is fixed`. Don't use `test.skip` or `test.fixme` to hide it.
7. **Test names** describe the user outcome, e.g. `admin moves a submitted claim to under review`.

## Run and check

```bash
cd code/demo-app-ui
npx playwright test <spec>.ts            # needs the full stack running
npx playwright test <spec>.ts --repeat-each=3   # check it's stable
```

Then update `E2E_TEST_NOTES.md` (test count, pass/fail, and any new defect). Use the `log-defect` skill for any product bug the test exposes.
