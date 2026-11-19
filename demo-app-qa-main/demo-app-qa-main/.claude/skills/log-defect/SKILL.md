---
name: log-defect
description: Record a new product defect found while testing the demo claims app in the project's D-number format, and update every place that tracks defects (TEST_STRATEGY.md §8, the relevant *_NOTES.md, the failing test's comment). Use when a test or manual check exposes a product bug, or when the user says "log this", "raise a defect" or "add D9".
---

# Log a defect

Production code is **not** changed here. Fixes need the user's sign-off; this skill only records the defect.

## 1. Confirm it is a new product defect

- Re-read the table in TEST_STRATEGY.md §8 ("Defects found so far"). If this is an existing D-number (often the same root cause seen at a new level, e.g. D3 under load), **don't create a new one**. Add the new evidence to that defect instead.
- Rule out the test itself and the environment: re-run once, and check for Keycloak lockout, a stack that isn't running, or JDK 25/PMD.
- Find the root cause in the code where you can (file and line). One sentence is enough.

## 2. Choose the ID and severity

- The ID is the next free `D<n>` after the highest in §8.
- Severity uses the scale in §8:
  - **Critical:** security breach, data loss, or a core journey fully blocked;
  - **High:** a core feature broken or a security weakness, with a workaround or limited reach;
  - **Medium:** partly wrong, or the API breaks its spec;
  - **Low:** cosmetic or a minor deviation.
- A suspected defect that isn't reproduced yet is **not** given an ID. Keep it as "to confirm" in the notes.

## 3. Write the report

Use this block in the notes file for the test level that found it:

```markdown
### D<n>: <one-line summary> (<Severity>)

**Found by:** <test name / script ID / k6 run>
**Steps to reproduce:**
1. …
**Expected:** …
**Actual:** … (status code, message, screenshot or trace path)
**Root cause:** <file:line and one sentence>, or "not yet known"
**Environment:** <stack, browser, JDK>
**Suggested fix:** <one or two sentences; not implemented>
```

## 4. Update every tracker

1. **TEST_STRATEGY.md §8:** add a row to "Defects found so far", and add the ID to the example column of the severity table.
2. **The notes for the level** (`UNIT_`, `INTEGRATION_`, `CONTRACT_`, `E2E_`, `LOAD_TEST_NOTES.md`, or `MANUAL_TEST_SCRIPTS.md`): add the report block and update any results table that counts failures.
3. **The failing test:** add a one-line comment naming the defect (e.g. `// Fails until D9 is fixed: …`). Never skip or disable the test to make the build green.
4. **Other places it affects:** if the defect makes other scripts fail (e.g. a manual script), add a "Known issue: D<n>" note there.

## 5. Tell the user

Give the ID, severity, a one-line summary, the root cause, and the files you updated. Ask whether they want it fixed. Don't fix it unasked.
