# Manual Test Scripts

## 1. Purpose and scope

These scripts cover what TEST_STRATEGY.md §4.7 leaves to people: things that are slow, brittle or impossible to automate cheaply.

| Area | Why it is manual |
|---|---|
| Real-time updates across two browsers | Needs two live sessions, timing and the toast UX judged by eye |
| Multi-tab and session behaviour | Cookie, expiry and logout behaviour across tabs and over time |
| Responsive layout (mobile, tablet, desktop) | Visual judgement: overlap, truncation, tap targets |
| Keyboard and screen reader | Assistive-technology output and focus order can't be fully judged by a tool |
| Themes and dark mode | Contrast and readability judged by eye |
| Error and recovery behaviour | Stopping services mid-session, slow networks |

The main functional journeys (login, submitting a claim, the admin status change, validation) are automated in Playwright (E2E_TEST_NOTES.md). They are **not** repeated here, except as steps that set up a manual check.

Section 9 has the exploratory charters.

## 2. Environment and data

| Item | Value |
|---|---|
| Start | From the project root: `./start-infra.sh && ./start-app.sh` |
| UI | http://localhost:3001 |
| Admin | `admin@demo.com` / `Admin123!` |
| Claimant | `claimant@demo.com` / `Claimant123!` |
| Browsers | Chrome (latest) is required. Also run Firefox, Safari or Edge where a script says **(cross-browser)** |
| Second session | A second browser, or a private/incognito window. Two tabs in the same profile share cookies, so they are **one** session |

**Before you start:**
- If the load tests have been run, the demo claimant may have thousands of claims (LOAD_TEST_NOTES.md §5.3). Clean them up first, or lists will be slow and hard to read.
- Keycloak locks an account after several failed logins in a row. Don't use the seeded accounts for wrong-password tests. If an account gets locked, wait about a minute or restart Keycloak.
- Signup is broken (D5). Scripts that need a fresh claimant are marked **Blocked by D5** until it is fixed.

## 3. How to record results

- Copy the result table (section 10) for each test cycle.
- For each script, record **Pass**, **Fail**, **Blocked** (with the defect ID) or **N/A**.
- For a failure, add a screenshot or recording, the browser and version, and the time, then log a defect using the severity scale in TEST_STRATEGY.md §8.
- "Expected" describes the **correct** behaviour. Where a known defect makes a step fail, the script says so in **Known issue**, so testers don't re-raise it.

---

## 4. Real-time updates (two sessions)

Setup for every script in this section:
- **Session A** is Chrome, logged in as the **admin**.
- **Session B** is a second browser or an incognito window, logged in as the **claimant**.
- Arrange the windows side by side.

> **Known issue: D7.** In the default compose setup, the event path the UI listens on doesn't receive events, so the scripts in this section are expected to fail until D7 is fixed. Run them anyway to confirm the defect. After the fix, they become the acceptance check.

### MT-RT-01: Admin sees a new claim appear without refreshing

| # | Step | Expected |
|---|---|---|
| 1 | A: open **Claims Management** | The claims list loads. The header's connection indicator shows **Live** (green) |
| 2 | B: open **My Claims**, check the indicator | **Live** |
| 3 | B: submit a claim (any valid data). Note the claim ID from the success toast | Toast: "Claim submitted successfully! Claim ID: …". The new claim is at the top of My Claims |
| 4 | A: watch without refreshing, for up to 2 s | Toast: "New claim submitted: `<first chars of ID>`…". The claim appears in the list |
| 5 | A: open **Admin Dashboard** | Total Claims and the Submitted count include the new claim |

### MT-RT-02: Claimant sees the status change made by an admin

| # | Step | Expected |
|---|---|---|
| 1 | B: on **My Claims**, find a SUBMITTED claim | Badge shows **Submitted** |
| 2 | A: in Claims Management, change that claim to **Under Review** and confirm the dialog | Toast: "Claim status updated to Under Review" |
| 3 | B: watch without refreshing, for up to 2 s | Toast: "Your claim #…… status changed to UNDER_REVIEW". The badge changes to **Under Review** |
| 4 | B: open the claim details | Status is Under Review. "Last Updated" has changed |
| 5 | Repeat steps 2–4 for **Approved**, then **Closed** | Same behaviour for each change |

### MT-RT-03: Click the status-change toast (suspected defect)

| # | Step | Expected |
|---|---|---|
| 1 | Do MT-RT-02 steps 1–3 | Toast shows on B |
| 2 | B: click the toast before it closes | The claim's details open |

> **To confirm:** the toast goes to `/claims/<id>`, but the UI has no route for that page. The click probably shows a 404. If it does, log it as a Low defect.

### MT-RT-04: Connection lost and recovered

| # | Step | Expected |
|---|---|---|
| 1 | A: on the dashboard, indicator shows **Live** | – |
| 2 | Stop the BFF (`docker stop` the BFF container, or `./stop-app.sh`) | Within a few seconds the indicator shows **Reconnecting...** |
| 3 | Leave it stopped for about 2 minutes | Once it stops retrying, it shows **Offline**, plus the banner "Real-time updates unavailable. Please refresh page manually." |
| 4 | Start the BFF again, then refresh the page | The indicator shows **Live** again and the banner is gone. No duplicate toasts appear |
| 5 | Repeat step 2, but restart the BFF after 10 s (before retries run out) | It reconnects by itself, back to **Live**, without a page refresh |

### MT-RT-05: Several admins at once

| # | Step | Expected |
|---|---|---|
| 1 | Open a third session C as the admin (another browser profile) | – |
| 2 | B: submit a claim | Both A and C get the "New claim submitted" toast and see the claim |
| 3 | A and C: both open the same claim's status menu. A picks **Under Review** and confirms. Then C picks **Under Review** and confirms | A succeeds. C gets a clear message that the claim has already changed, and C's list refreshes to Under Review |

> **Known issue: D3.** In step 3, C gets a server error (500) instead of a clear message. The load test found this too (LOAD_TEST_NOTES.md P3).

---

## 5. Sessions and multiple tabs

### MT-SES-01: Logout from one tab, then use another

| # | Step | Expected |
|---|---|---|
| 1 | Log in as the claimant. Open My Claims in tab 1 and tab 2 | Both show the claims |
| 2 | Tab 1: click **Logout** | Goes to /login |
| 3 | Tab 2: refresh, or start submitting a claim | Goes to /login. No data loads and nothing is submitted |
| 4 | Browser dev tools → Application → Cookies | `access_token` and `refresh_token` are gone |

> **Known issue: D6.** Logout returns 403, so the session stays active and tab 2 keeps working. This script is the manual acceptance check for D6.

### MT-SES-02: Two different users in one browser

| # | Step | Expected |
|---|---|---|
| 1 | Tab 1: log in as the claimant | My Claims |
| 2 | Tab 2 (same window): open /login and log in as the admin | Admin Dashboard |
| 3 | Tab 1: refresh | Tab 1 now acts as the **admin**, because the cookies are shared. No data from the two users is mixed on one page |
| 4 | Tab 1: open /claims/new and submit a claim | Either the claim is clearly made as the admin, or it is refused. It must not be filed under the claimant |

> This checks a known limitation and makes sure it fails safely. Record what the app actually does.

### MT-SES-03: Session lasts beyond the access token's lifetime

| # | Step | Expected |
|---|---|---|
| 1 | Log in as the claimant. Note the time | – |
| 2 | Leave the tab open and idle for **6 minutes** (the access cookie lasts 5 minutes) | – |
| 3 | Open My Claims, then submit a claim | Both work with no visible re-login (the token is refreshed silently) |
| 4 | Leave it idle for **31 minutes** (the refresh token lasts 30 minutes) | – |
| 5 | Click any page in the app | Goes to /login with "Your session has expired. Please log in again." Nothing half-loaded remains on screen |

### MT-SES-04: Return to the requested page after login (suspected defect)

| # | Step | Expected |
|---|---|---|
| 1 | Logged out, open http://localhost:3001/claims/new | Goes to `/login?redirect=/claims/new` |
| 2 | Log in as the claimant | Arrives on **/claims/new** |

> **To confirm:** the login page doesn't seem to read `redirect`, so you'll probably land on the default page. If so, log it as a Low defect.

### MT-SES-05: Browser Back after logout

| # | Step | Expected |
|---|---|---|
| 1 | Log in as the admin and visit the dashboard, then Users | – |
| 2 | Log out, then press the browser **Back** button several times | No admin data appears, even from the browser cache. Any protected page goes to /login |

### MT-SES-06: Claimant opens admin URLs

| # | Step | Expected |
|---|---|---|
| 1 | Log in as the claimant | – |
| 2 | Type in turn: /admin/dashboard, /admin/claims, /admin/users | Each shows an "Access denied" toast and goes back to the claimant area. Admin data is never shown, not even briefly |
| 3 | The header | It never shows admin navigation |

---

## 6. Responsive layout (cross-browser)

Use the browser's device toolbar, then a real phone if you have one. Check at **375 px** (phone), **768 px** (tablet) and **1440 px** (desktop), in both portrait and landscape on the phone.

### MT-UI-01: Login and signup pages

At each width:
- The form is centred, nothing is cut off, and there is no horizontal scroll.
- The password show/hide button stays inside the field.
- Error messages wrap and don't push the button off the screen.

### MT-UI-02: My Claims (list and details)

| # | Step | Expected |
|---|---|---|
| 1 | Claimant with 5–20 claims, open My Claims at 1440 px | Table view. Columns are aligned. Long locations are truncated with an ellipsis |
| 2 | Shrink to 375 px | It switches to **cards**, with the same data on each card and no horizontal scroll |
| 3 | Open a claim's details on the phone width | The details window fits the screen, its content scrolls, and the **Close** (×) button is reachable |
| 4 | Claimant with no claims (blocked by D5 for a new user; otherwise skip) | "No claims yet" and a **Submit Your First Claim** button |

### MT-UI-03: Claim wizard

| # | Step | Expected |
|---|---|---|
| 1 | At 375 px, go through all 3 steps | The progress indicator is readable. Buttons are at least 44 px tall and don't overlap |
| 2 | Step 1, tap the date field on a phone | The phone's date picker opens. Future dates can't be picked |
| 3 | Step 1, tap the amount field | The number keypad opens |
| 4 | Step 2, enter 1,000 characters | The field grows or scrolls. The counter and any error stay visible |
| 5 | Step 3, review | Long descriptions wrap. The amount shows in currency format |

### MT-UI-04: Admin pages

| # | Step | Expected |
|---|---|---|
| 1 | Dashboard at 375 / 768 / 1440 px | The stats cards stack or grid properly. The "Claims by Status" numbers are readable. The cache buttons stay usable |
| 2 | Claims Management at 375 px | It switches to cards. The status filter, the User ID search and the per-claim status menu all work by touch |
| 3 | Copy buttons for Claim ID and User ID | The full ID is copied; paste it to check. There is visual feedback |
| 4 | Users at 375 px | The search box and the list are usable. Long emails wrap or truncate without overflowing |

### MT-UI-05: Themes and dark mode

| # | Step | Expected |
|---|---|---|
| 1 | Header → theme menu: switch through Amber, Blue and Teal | Colours change everywhere (buttons, links, badges). Nothing keeps the old colour |
| 2 | Switch on dark mode (🌙) | Every page is readable: text on cards, form fields, the details window, the status badges and the toasts |
| 3 | Refresh, then log out and back in | The theme and dark mode stay as chosen |
| 4 | Dark mode, look at the status badges and error text | The contrast looks clearly readable. Note any badge that is hard to read |

---

## 7. Keyboard and screen reader

Use **NVDA + Firefox** or **Narrator + Edge** on Windows, or **VoiceOver + Safari** on macOS. For the keyboard checks, don't use the mouse at all.

### MT-A11Y-01: Login and signup with the keyboard only

| # | Step | Expected |
|---|---|---|
| 1 | Tab through the login page | Order: email → password → show/hide → **Log In** → Sign up link. The focus outline is always visible |
| 2 | Submit empty with Enter | The errors are announced and focus moves to the first invalid field |
| 3 | Screen reader: read each field | Each field reads its label ("Email", "Password") and its error message |

### MT-A11Y-02: Claim wizard with the keyboard only

| # | Step | Expected |
|---|---|---|
| 1 | From My Claims, reach **Submit Claim** in the header by Tab and press Enter | The wizard opens. Focus is at the top or on the first field |
| 2 | Complete step 1, including the date, using the keyboard | The date can be typed or chosen without a mouse |
| 3 | Step changes | The new step is announced (e.g. "Step 2 of 3") and focus moves to its first field |
| 4 | Press **Cancel**, then Esc | The "Cancel Claim Submission?" dialog takes focus and keeps it inside. Esc or **Keep Editing** closes it and focus returns to Cancel |
| 5 | Submit | The success toast is announced by the screen reader (live region) |

### MT-A11Y-03: Claim details window

| # | Step | Expected |
|---|---|---|
| 1 | My Claims: reach a claim by Tab and open it with Enter | The details window opens and focus moves inside it |
| 2 | Tab repeatedly | Focus stays inside the window |
| 3 | Press Esc | The window closes and focus returns to the claim that opened it |
| 4 | Screen reader | The window is announced as a dialog with a title. Each field reads its label and value |

### MT-A11Y-04: Admin status change

| # | Step | Expected |
|---|---|---|
| 1 | Claims Management: reach a claim's status menu by Tab | The menu announces the current status and its options |
| 2 | Choose a new status with the arrow keys and Enter | "Confirm Status Change" opens with focus inside. Both buttons can be reached |
| 3 | Confirm | The success toast is announced. Focus returns to the menu, not the top of the page |
| 4 | A CLOSED claim | The menu is announced as "No status transitions available" |

### MT-A11Y-05: Page-level checks

For each page:
- There is a sensible page title and one main heading.
- Landmarks exist (header, main).
- The header navigation is reachable by keyboard.
- The connection indicator has a text label, not only a colour.
- At 200 % browser zoom, nothing is cut off or overlaps.

Also run an automated checker (axe DevTools or Lighthouse) as a first pass. Record the number of serious and critical issues.

---

## 8. Errors and recovery

### MT-ERR-01: The backend is down during a submit

| # | Step | Expected |
|---|---|---|
| 1 | Claimant: fill the wizard to step 3 | – |
| 2 | Stop claims-service (`docker stop` the container) | – |
| 3 | Click **Submit Claim** | Toast "Failed to submit claim. Please try again." The data is **kept** and the button works again |
| 4 | Start claims-service again, then click **Submit Claim** | Success. **Only one** claim is created; check My Claims |

### MT-ERR-02: Slow network

| # | Step | Expected |
|---|---|---|
| 1 | Dev tools → Network → "Slow 3G". Open My Claims | A loading skeleton shows, then the list loads |
| 2 | Submit a claim and double-click **Submit Claim** quickly | The button shows "Submitting..." and is disabled. **Only one** claim is created |
| 3 | Open the claim details | "Loading claim details..." shows, then the data |
| 4 | Admin dashboard: click **Load from Cache** several times quickly | Buttons are disabled while loading. No stacked error toasts |

### MT-ERR-03: Leaving the wizard part way

| # | Step | Expected |
|---|---|---|
| 1 | Fill step 1 and go to step 2 | – |
| 2 | Click **Back** | Step 1 still shows **all** values, including the date |
| 3 | Go forward again, click **Cancel**, then **Discard & Leave** in the dialog | Back on My Claims. No claim is created |
| 4 | Open **Submit Claim** again | The wizard starts empty at step 1 |
| 5 | Fill step 1, then refresh the browser | Note what happens (kept or cleared). Either is acceptable, but it must not crash |

> **Known issue: D8.** In step 2, the incident date is empty.

---

## 9. Exploratory charters

Each session is **60–90 min**, one tester, one charter. Record notes, bugs, questions and ideas for new automated tests. Use the session sheet at the end.

| ID | Charter | Ideas |
|---|---|---|
| EX-01 | Explore the **claim wizard** with odd input to find validation gaps between UI and API | Amount `0.001`, `1e6`, `-0`, a comma decimal (`12,50`); today's date at midnight across time zones (change the OS time zone); emoji, RTL text and HTML/`<script>` in the location and description; exactly 5, 200, 10 and 1,000 characters; paste instead of typing |
| EX-02 | Explore **sessions and expiry** to find ways to keep or lose access unexpectedly | Delete one cookie only; copy cookies to another browser; edit the `auth-storage` localStorage role to ADMIN; system clock changes; logout during a request |
| EX-03 | Explore the **admin claims list** at volume to find performance and usability limits | Thousands of claims (run k6 `smoke` a few times); filter and search speed; User ID search with partial or invalid IDs; status changes while a filter is on; "Details view coming soon" toast |
| EX-04 | Explore **real-time updates** with many events (after D7) to find missed, duplicate or out-of-order updates | Many fast status changes; browser tab in the background then foreground; laptop sleep and wake; 5+ open sessions |
| EX-05 | Explore **direct API access** (curl or Postman) to find authorization gaps | claims-service on :8080 without the BFF (D1); claimant reading another user's claim ID; status changes as a claimant; missing CSRF header |
| EX-06 | Explore the **admin dashboard cache** to check the numbers are right | Do Total Claims and the per-status counts match the database? Is the data stale after a status change? Do "Flush Cache" and the Cached/Database indicator match the timing (D2)? |

**Session sheet:**

```
Charter:              Tester:            Date / build:
Start – end:          Setup time %:      Test time %:      Bug investigation %:
Areas covered:
Bugs found (IDs):
Questions / issues:
Ideas for automated tests:
```

---

## 10. Result table

Copy this for each test cycle.

| ID | Title | Browser / device | Result | Defect / notes |
|---|---|---|---|---|
| MT-RT-01 | New claim reaches the admin live | | | expected Fail: D7 |
| MT-RT-02 | Status change reaches the claimant live | | | expected Fail: D7 |
| MT-RT-03 | Click the status toast | | | to confirm: missing route |
| MT-RT-04 | Connection lost and recovered | | | |
| MT-RT-05 | Several admins at once | | | expected Fail: D3 (and D7) |
| MT-SES-01 | Logout from one tab, then use another | | | expected Fail: D6 |
| MT-SES-02 | Two users in one browser | | | |
| MT-SES-03 | Session past the token lifetime | | | |
| MT-SES-04 | Return to the requested page after login | | | to confirm |
| MT-SES-05 | Back after logout | | | |
| MT-SES-06 | Claimant opens admin URLs | | | |
| MT-UI-01 | Login and signup layout | | | |
| MT-UI-02 | My Claims layout | | | |
| MT-UI-03 | Wizard layout | | | |
| MT-UI-04 | Admin layout | | | |
| MT-UI-05 | Themes and dark mode | | | |
| MT-A11Y-01 | Login with the keyboard and screen reader | | | |
| MT-A11Y-02 | Wizard with the keyboard and screen reader | | | |
| MT-A11Y-03 | Claim details window | | | |
| MT-A11Y-04 | Admin status change | | | |
| MT-A11Y-05 | Page-level checks | | | |
| MT-ERR-01 | Backend down during a submit | | | |
| MT-ERR-02 | Slow network | | | |
| MT-ERR-03 | Leaving the wizard part way | | | expected Fail: D8 |

**Status:** these scripts were written from the code, the requirements and the earlier test results. They have **not been run** yet. The first run will confirm or close the two "to confirm" items.
