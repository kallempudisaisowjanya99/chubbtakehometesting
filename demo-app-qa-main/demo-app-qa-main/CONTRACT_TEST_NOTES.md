# Contract Tests (Pact): BFF ↔ Claims Service

## 1. Requirement

> Build out Pact contract tests between BFF and Claims Service.

The BFF (consumer) calls claims-service (provider) over REST. Contract tests check that the two agree on that API without deploying them together:

1. **Consumer test (bff-service):** the BFF's real outbound code runs against a Pact mock server. Each expectation it relies on is recorded in a pact file.
2. **Provider verification (claims-service):** every interaction in that pact file is replayed against the real claims-service, and the responses must match.

The Pact dependencies were already in both POMs, but there were no tests. No production code was changed.

## 2. Results

| Side | Test | Interactions | Result |
|---|---|---|---|
| Consumer | `bff-service/.../contract/ClaimsServiceConsumerPactTest` | 15 | ✅ 15 / 15 pass, pact written |
| Provider | `claims-service/.../contract/ClaimsServiceProviderPactTest` | 15 | ❌ **14 / 15 pass**, 1 fails because of **D3** |

The result was the same in two provider runs. The BFF build (`mvn verify`) still passes with the new tests: 228 tests, coverage gate met. See 5.3 for the PMD note.

### Interactions in the contract

| # | BFF call | Caller token | Provider state | Expected |
|---|---|---|---|---|
| 1 | `POST /api/claims` | claimant | the admin and the claimant are registered | 201, ClaimResponse |
| 2 | `GET /api/claims` | claimant | the claimant has a submitted claim | 200, ≥ 1 ClaimSummary |
| 3 | `GET /api/claims/{id}` (own claim) | claimant | the claimant has a submitted claim | 200, ClaimResponse |
| 4 | `GET /api/claims/{id}` (another user's) | claimant | a claim owned by another user exists | **403** |
| 5 | `GET /api/claims/{unknown}` | claimant | no claim exists | **404** |
| 6 | `POST /api/users` (new) | BFF service account | no user exists with the given id and email | 201, UserResponse |
| 7 | `POST /api/users` (existing) | BFF service account | a user exists | **409 with the existing user** (the BFF parses it) |
| 8 | `GET /api/users/{id}` | BFF service account | a user exists | 200, UserResponse |
| 9 | `GET /api/users` | admin | the admin and the claimant are registered | 200, ≥ 1 user |
| 10 | `GET /api/admin/dashboard/stats?bypassCache=true` | BFF service account | the claimant has a submitted claim | 200, stats |
| 11 | `POST /api/admin/dashboard/flush-cache` | BFF service account | – | 204 |
| 12 | `GET /api/admin/claims?status=SUBMITTED&userId=…` | BFF service account | the claimant has a submitted claim | 200, ≥ 1 AdminClaimResponse |
| 13 | `PATCH /api/admin/claims/{id}/status` → UNDER_REVIEW | admin | the claimant has a submitted claim | 200, ClaimResponse |
| 14 | `PATCH …/status` → CLOSED (not allowed) | admin | the claimant has a submitted claim | **400** ❌ gets 500 (D3) |
| 15 | `PATCH /api/admin/claims/{unknown}/status` | admin | no claim exists | **404** |

These are all the calls the BFF makes to claims-service (`ClaimsServiceAdapter` and `AdminController`). Each one includes the error status codes the BFF code checks for.

## 3. How it works

### 3.1 Consumer (bff-service)

- The tests call the **real BFF classes**: `ClaimsServiceAdapter` and `AdminController`, with the OpenAPI-generated client pointed at the mock server. The contract therefore records what the code really sends and reads, not a hand-written copy of it.
- **The contract only contains what the BFF relies on:**
  - response fields use type and regex matchers, not fixed values;
  - error bodies are left out, except the 409 on user creation, which the BFF parses to log the user in;
  - extra fields in a response don't break the contract.
- **Tokens** are placeholders that name the caller: `claimant-token`, `admin-token`, `service-account-token`. The admin calls read the admin's JWT from the Spring security context, so those tests set one up.
- **IDs created by the provider** (claim ids, the claimant's Keycloak id) come from provider states: `pathFromProviderState`, `valueFromProviderState`, `queryParameterFromProviderState`. The pact never relies on fixed rows in the database.
- The pact is written to `bff-service/pacts/bff-service-claims-service.json` (Pact spec V3). **Commit this file:** claims-service verifies it from there.
- Tagged `contract`. It needs no Docker, takes about 4 s, and runs with a normal `mvn test`.

### 3.2 Provider (claims-service)

- It runs the **whole service** on the Testcontainers stack used by the integration tests (PostgreSQL, Redis, Kafka, Keycloak) and shares their Spring context. Responses come from the real controllers, security, database and exception handlers, not mocks.
- Before each interaction, the token placeholder is replaced with a **real Keycloak token** for that caller. So JWT validation, role mapping and the "own claims only" check all run as in production.
- **Provider state handlers** create the data each interaction needs through the real API, for example by submitting a claim as the claimant. They return the new IDs to Pact. Pact test users (`pact.*` emails) are deleted before they are set up again, because the containers are reused between runs.
- Tagged `contract` (and `integration` through the base class). It needs Docker and is skipped by a plain `mvn test`, like the other integration tests.

## 4. Findings

### 4.1 D3 breaks the contract (already logged; new effect found)

The BFF expects a **400** when an admin picks a status change that isn't allowed, and returns 400 to the UI. claims-service returns **500**, because `InvalidStatusTransitionException` has no exception handler (see [INTEGRATION_TEST_NOTES.md](INTEGRATION_TEST_NOTES.md)).

**Effect seen through the BFF:** the BFF treats any status other than 400, 403 and 404 as an unexpected error. It throws, so the admin gets a generic **500** instead of a validation error. The UI only offers allowed transitions, so users rarely hit this today. But any stale screen, or two admins changing the same claim, ends in a server error.

**Fix:** map `InvalidStatusTransitionException` to 400 in `ClaimExceptionHandler`. Interaction 14 then passes with no change to the contract.

### 4.2 Observations (not contract failures)

1. **A new response field breaks the BFF, and Pact can't catch it.**
   - The BFF's generated client (`okhttp-gson`) rejects any field in a response that it doesn't know. This showed up while writing the tests: `The field 'SUBMITTED' in the JSON string is not defined in the 'DashboardStatsResponseDTO' properties`.
   - So if claims-service adds a field to a response (normally a safe change), the BFF fails at runtime until it is regenerated.
   - Pact allows extra fields on purpose, so the contract tests won't catch this.
   - **Recommendation:** make the generated client accept unknown fields, or treat every response-field addition as a breaking change and update both services together.
2. **Most admin calls go to claims-service as the BFF's service account, not as the admin.**
   - Dashboard stats, cache flush and the admin claims list send the BFF's service-account token. Only the users list and status changes forward the admin's own token.
   - So claims-service can't record which admin read the data. If the BFF's role check were ever bypassed, claims-service would trust the service account anyway.
   - Worth a review together with D1.
3. **D4 (404 vs 400 for an unknown user) is not in the contract.** The BFF doesn't branch on that status, so no consumer relies on it. That is how consumer-driven contracts are meant to work.

## 5. How to run

### 5.1 Consumer (no Docker)

```bash
cd code/bff-service
mvn test -Dtest=ClaimsServiceConsumerPactTest     # writes pacts/bff-service-claims-service.json
```

It also runs as part of a normal `mvn test` / `mvn verify`.

### 5.2 Provider (Docker required)

```bash
cd code/claims-service
mvn test -Dgroups=contract -Dsurefire.excludedGroups= -Djacoco.skip=true
```

Run the consumer first whenever the BFF changes, so the pact file is up to date. The `-Djacoco.skip=true` flag is needed because coverage from one test class alone won't meet the module's 90 % gate.

### 5.3 Note: PMD fails on Java 25

`mvn verify` in bff-service fails in PMD with `Unsupported class file major version 69`. PMD 7.7.0 cannot read Java 25 class files, and this machine has only JDK 25 installed (the project targets Java 21).
- This failure is in the environment, not caused by these tests: PMD only scans `src/main`, which was not changed.
- Use JDK 21, or skip PMD locally with `-Dpmd.skip=true -Dcpd.skip=true`.

## 6. Next steps

- **Pact Broker:** publish pacts from the BFF build, verify them from claims-service, publish the verification results, and gate deploys with `can-i-deploy`. This replaces the shared `pacts/` folder.
- **Kafka event contracts:** add message pacts for the claim events (claims-service → BFF), checked against the AsyncAPI schemas in both services.
- **UI → BFF:** the same approach could cover the Next.js client (`pact-js`) against the BFF.
