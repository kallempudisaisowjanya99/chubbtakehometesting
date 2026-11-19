---
name: qa-wrap-up
description: Finish a piece of QA work on the demo claims app — write or update its *_NOTES.md, keep TEST_STRATEGY.md in sync, check nothing sensitive is staged, and produce the commit message in the project's style. Use when a testing task is done, or the user asks for "a commit message", "message to commit", or to update the notes or strategy.
---

# Wrap up a QA task

## 1. Notes file

Each test level has a notes file in the project root: `UNIT_TEST_NOTES.md`, `INTEGRATION_TEST_NOTES.md`, `CONTRACT_TEST_NOTES.md`, `E2E_TEST_NOTES.md`, `LOAD_TEST_NOTES.md`, `MANUAL_TEST_SCRIPTS.md`. A new level gets a new `<LEVEL>_NOTES.md` with these sections:

1. **Requirement:** the README line it answers, quoted.
2. **What was built:** files and the approach. State "No production code was changed" if that's true.
3. **Results:** real numbers from the last run (counts, pass/fail, timings). Never estimate a number you didn't measure.
4. **Findings:** defects (D-numbers) and observations.
5. **How to run:** copy-paste commands, prerequisites, environment workarounds.
6. **Next steps.**

## 2. TEST_STRATEGY.md

Keep these in sync with the notes:
- the **Related** links line at the top;
- the level's row in the §4 test-levels table (status, ✅ / ⏳, one-line result);
- the level's §4.x subsection;
- the §8 defect table, for new defects (see the `log-defect` skill);
- §10 risks, if something new came up;
- §11 next steps: remove what's done and add what follows.

Careful with sed: headings contain `#`, so use `|` as the delimiter. Check each change with grep afterwards.

## 3. Before committing

- `git status`. Never stage the following, and warn the user if they're present:
  - `code/demo-app-ui/tests/e2e/.auth/` (saved logins);
  - any stray `tests/` folder at the repo root (tokens);
  - `code/load-tests/results/`;
  - `.env` files;
  - Playwright `test-results/` and `playwright-report/`.
- **Do** commit generated artefacts the tests depend on, such as the pact JSON.
- Commit only when the user asks. Otherwise give them the message.

## 4. Commit message format

```
<Imperative summary, max ~60 chars>

- <what was added/changed, one line per area>
- <findings worth knowing: new defects or key results>
- <docs updated>

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

Examples from this repo:
- "Add Pact contract tests between BFF and claims-service"
- "Add k6 load and performance tests"
- "Add manual test scripts and exploratory charters"
