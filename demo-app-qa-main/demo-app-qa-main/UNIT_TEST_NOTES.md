# Unit Tests — Fix & Extend

## 1. Requirement

From the README, *QA team scope*:

> Fix and extend unit tests (a broken test exists as a starting point)

How the task was read:

1. **Fix**: get every existing unit-test suite (claims-service, bff-service, demo-app-ui) to run and pass on a fresh checkout. Find the **root cause** of each failure instead of deleting or skipping tests.
2. **Extend**: add tests where real behaviour was untested, focusing on business rules and failure paths rather than coverage for its own sake. The project's own quality gates must pass: JaCoCo **≥ 90% line and branch** for the backend, and the Vitest thresholds for the UI.

Nothing was `@Disabled` or `skip`ped. Production code was changed in one place only, and only with sign-off (see §3.4).

---

## 2. Results

| Suite | Before | After |
|---|---|---|
| **claims-service** (`mvn verify`) | ❌ **Did not compile**. 0 tests ran | ✅ **239 / 239** pass · branch **91.0%** · line **93.0%** · JaCoCo gate met |
| **bff-service** (`mvn verify`) | ✅ 213 / 213 | ✅ 213 / 213 · branch 92.5% · line 94.5% (unchanged) |
| **demo-app-ui** (`npm test`) | ❌ **17 failed / 127 run**. 12 of 25 files failed | ✅ **276 / 276** pass, 26 files, stable over repeated runs |
| UI coverage (`npm run test:coverage`) | ❌ Script could not run | ✅ Statements **93.6%** · branches **85.2%** (thresholds 60 / 50) |

---

## 3. What was broken, why, and what was done

### 3.1 claims-service: the "broken test" (`UpdateClaimStatusUseCaseTest`)

**Symptom:** test compilation failed, so the whole module's test run aborted.

```
constructor UpdateClaimStatusUseCase ... cannot be applied to given types;
  required: ClaimRepository, UserRepository, MeterRegistry, ApplicationEventPublisher
  found:    ClaimRepository, UserRepository, MeterRegistry
```

**Root cause:** the use case gained a feature (direct event publishing when CDC is off, controlled by `app.events.cdc-enabled`). That feature added an `ApplicationEventPublisher` dependency and a `cdcEnabled` flag. The test was never updated.

**Fix:** pass a mocked `ApplicationEventPublisher`. `cdcEnabled` is injected with `@Value`, which isn't processed outside Spring, so the test sets it explicitly with `ReflectionTestUtils`. Leaving it implicit would mean the branch being tested depends on a Java default.

**Extension:** the new feature itself had no tests. Added:

| Test | Requirement it protects |
|---|---|
| CDC **off**: publishes one `ClaimStatusChanged` with correct claimId and old/new status | In direct-Kafka mode (the docker-compose default) the UI only gets real-time updates if this event is published |
| CDC off: events are **cleared** after publishing | Prevents the same event being published twice |
| CDC off: publish happens **after** `save` | An event must not announce a change that was never persisted |
| CDC **on**: publisher is never called and events are kept | In CDC mode Debezium publishes from the DB WAL. Publishing here too would send **duplicate notifications** (README warns about this) |
| Metric `claims.status.changed` incremented once with `oldStatus` / `newStatus` / `adminUserId` tags | Operational metric used for dashboards |
| **Full transition matrix**: all 7 allowed transitions succeed; all 18 disallowed pairs (including same-status) are rejected | The core business rule of claims handling. Previously only `CLOSED → APPROVED` was tested |
| Every failure path (not admin, user missing, claim missing, invalid transition) has **no side effects**: no save, no event, no metric | Failed requests must leave nothing half-done |

The disallowed set is generated from `ClaimStatus.canTransitionTo`, so the matrix stays complete if a status is added.

### 3.2 claims-service: two more stale tests, hidden behind the compile error

Once the module compiled, **9 more tests failed**. Nobody had seen them because compilation had failed first. They had the same root cause (code changed, tests not updated):

| Test class | Root cause | Fix |
|---|---|---|
| `CreateClaimUseCaseTest` (3 errors) | `CreateClaimUseCase` gained the same `ApplicationEventPublisher` dependency. `@InjectMocks` silently passed `null`, causing an NPE | Added the `@Mock`. Replaced the old check "events are on the returned claim", which is only true in CDC mode, with explicit tests for both modes, publish-after-save, the `claims.submitted` metric, and no publish/metric when the user doesn't exist |
| `KafkaDomainEventPublisherTest` (6 failures) | The publisher now wraps events in an envelope `{eventType, eventId, payload{…}}` built with `objectMapper.createObjectNode()`. The test **mocked** `ObjectMapper`, so that returned `null` | Uses a real `ObjectMapper` (a Mockito spy, so serialization failure can still be forced). It now checks the **actual envelope the BFF's `ClaimEventKafkaConsumer` reads**: event type, ids, payload fields, `userId` left out when `changedBy` is null, nothing sent when validation fails, and unknown event types rejected |

The envelope assertions matter most here. The old tests only checked that "some string was sent", which is how a contract change between the two services went unnoticed.

### 3.3 claims-service: branch coverage gate (89% → 91%)

After the fixes, `mvn verify` failed the JaCoCo gate at **89.2%** branch coverage. The largest untested logic was `UserController.createUser`'s **idempotent user provisioning** (BFF signup → claims-service), which had 3 of 4 branches uncovered. Added:

- same email and same ID → **409** returning the existing user, with no writes
- same email but **new Keycloak ID** (Keycloak was recreated) → user is re-keyed with `updateUserId → flush → findById` in that order, and **201** returned
- re-key succeeds but the user can't be reloaded → `IllegalStateException`

These cover real user-provisioning behaviour; they weren't added just to reach the number.

### 3.4 demo-app-ui: 17 failures, four independent causes

| # | Cause | Affected | Fix | Type |
|---|---|---|---|---|
| 1 | **Generated API client missing.** `src/lib/api/generated/` is git-ignored and only produced by `npm run generate:api`, which ran before `build` but **not before `test`** | 8 files failed to import | Added `pretest` and `pretest:coverage` scripts so `npm test` works on a fresh clone | Setup |
| 2 | **`localStorage` is `undefined` on Node ≥ 25.** Node now has a built-in experimental `localStorage` global that returns `undefined` without `--localstorage-file`, and it hides jsdom's working one. zustand `persist` then crashes | auth, theme and realtime store tests | `tests/setup.ts` installs an in-memory Web Storage **only if** the runtime's storage is unusable (no change on Node 20/22). Storage is also cleared after each test to stop state leaking between tests | Environment |
| 3 | **Test timeout cascade in `wizard-step-2`.** One test typed 950 characters one key at a time with `user.type`, which takes more than the 5 s timeout. The timed-out typing loop **kept running** and slowed down the tests after it, so 5 more tests failed even though the component was correct (checked in isolation) | 6 tests | Use `user.click` + `user.paste` for the long input. It still goes through a real input event but takes milliseconds | Flaky test |
| 4 | **Code and test disagreed on the theme requirement.** The store persisted to `sessionStorage`, while the tests (and their names) require `localStorage` | 2 tests | **Product decision (approved):** a theme preference should survive new tabs and browser restarts, so the store now uses `localStorage` (one-line change in `use-theme-store.ts`) | **Defect** |

Also under #4: the existing test "restores theme from localStorage on initialization" was **tautological**. It wrote a value and read the same value back without ever touching the store. It now calls `useThemeStore.persist.rehydrate()` and checks the store state. A guard test also asserts the theme is **not** written to `sessionStorage`.

### 3.5 demo-app-ui: coverage script fixed and largest gap closed

- `npm run test:coverage` was configured in `vitest.config.ts` but **could not run**: `@vitest/coverage-v8` wasn't installed. Added it as a dev dependency, pinned to the installed Vitest version.
- The lowest-covered file was **`src/lib/api/bff-client.ts` at 20%**, even though every API call in the app goes through it. Added `bff-client.test.ts` (9 tests), which takes it to **95%**:
  - **CSRF:** the `XSRF-TOKEN` cookie is sent back as the `X-XSRF-TOKEN` header (URL-decoded), and no header is sent when the cookie is absent
  - **Base URL:** `NEXT_PUBLIC_BFF_URL` by default, the WireMock stub URL when `USE_WIREMOCK_BFF=true`
  - **Session expiry:** 401 → `POST /api/auth/refresh` → original request retried once
  - refresh rejected, or refresh throws a network error → **logout** and redirect to `/login?session_expired=true`, with no retry
  - **concurrent 401s share a single refresh call** (protects against many parallel refreshes)

---

## 4. Files changed

**claims-service (tests only)**
- `src/test/.../application/usecases/UpdateClaimStatusUseCaseTest.java`: fixed and extended (5 → 38 tests)
- `src/test/.../application/usecases/CreateClaimUseCaseTest.java`: fixed and extended
- `src/test/.../adapter/out/messaging/KafkaDomainEventPublisherTest.java`: fixed and rewritten against the real envelope
- `src/test/.../adapter/in/web/UserControllerTest.java`: idempotent provisioning tests

**demo-app-ui**
- `package.json` / `package-lock.json`: `pretest` and `pretest:coverage` scripts; `@vitest/coverage-v8`
- `tests/setup.ts`: Web Storage shim and per-test storage reset
- `src/features/claims/components/wizard-step-2.test.tsx`: removed the timeout cascade
- `tests/unit/use-theme-store.test.ts`: real rehydration test and a guard against `sessionStorage`
- `src/lib/api/bff-client.test.ts`: **new**
- `src/features/theme/stores/use-theme-store.ts`: **product fix** (`sessionStorage` → `localStorage`)

---

## 5. Observations and open items

1. **Environment: use JDK 21.** The POM targets Java 21. On JDK 25 the tests and the JaCoCo gate work, but **PMD 7.7 can't read Java 25 class files** ("Unsupported class file major version 69"), so a full `mvn verify` fails at PMD. That's an environment problem, not a code one. The backend results above were taken with `-Dpmd.skip -Dcpd.skip`; on JDK 21 the plain `mvn verify` should pass. *Suggestion:* add a `.sdkmanrc` / Maven toolchain, or upgrade PMD.
2. **UI coverage numbers are optimistic.** `vitest.config.ts` has no `coverage.include`, so files no test imports, **all `app/` pages and `middleware.ts`**, don't appear in the report at all. *Suggestion:* add `include: ['src/**', 'app/**', 'middleware.ts']` and cover the route/auth logic in `middleware.ts` next.
3. **Naming question on `ClaimStatusChanged.changedBy`.** `Claim.updateStatus` fills it with the **claim owner's** ID, not the admin who made the change. It's sent as `payload.userId`, which the BFF probably uses to route the WebSocket notification to the claimant, so the behaviour may be intended. Either way the field name is misleading for audit purposes. Raise with the developers; not changed here.
4. **Process lesson.** One compile error hid 9 more failing tests in the same module. A CI job running `mvn test` / `npm test` on every PR would have caught all of these drifts when they were introduced.

---

## 6. How to run

```bash
# Backend: unit tests + JaCoCo gate (integration tests are excluded via the "integration" tag)
cd code/claims-service && mvn verify          # on JDK 25 add: -Dpmd.skip -Dcpd.skip
cd code/bff-service    && mvn verify

# Integration tests too (needs Docker for Testcontainers)
mvn test -Dsurefire.excludedGroups=

# Frontend (generates the API client automatically first)
cd code/demo-app-ui && npm test
cd code/demo-app-ui && npm run test:coverage
```

Coverage reports: `code/<service>/target/site/jacoco/index.html` and `code/demo-app-ui/coverage/`.
