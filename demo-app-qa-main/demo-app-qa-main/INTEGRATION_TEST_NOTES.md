# Integration Tests (Testcontainers)

## 1. Requirement

From the README, *QA team scope*:

> Add integration tests using Testcontainers

The README marks integration tests as "Scaffolded — empty". In practice the Testcontainers dependencies, a shared container configuration per service and four basic tests already existed. The task was read as:

- cover the behaviour that **only real infrastructure can prove**: real PostgreSQL + Liquibase schema, real Kafka, real Redis, real Keycloak tokens
- focus on what the mocked unit tests structurally cannot catch

The tests assert the behaviour **documented in the API spec** (`claims-api.yml`). Where the code disagrees with the spec, the test fails and the failure is reported as a defect (§4). No production code was changed and no test was disabled.

---

## 2. Results

| Suite | Tests | Result |
|---|---|---|
| claims-service integration (new + existing) | 51 | **42 pass, 9 fail.** All 9 failures trace to **4 product defects** (§4), identical across 2 runs |
| bff-service integration (new + existing) | 11 | ✅ 11 / 11 |
| Unit suites (`mvn test`, integration excluded) | 239 + 213 | ✅ unchanged and green. Integration tests stay out of the default build |

---

## 3. What was done

### 3.1 Baseline fix: pinned container images

The existing full-application tests (`HealthCheckIntegrationTest`, `contextLoads`) **failed on startup**:

```
PSQLException: FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"
```

The scaffold used floating `:latest` tags. `postgres:latest` rejects the legacy JVM timezone alias that this machine reports, while production's `postgres:15-alpine` accepts it.

Images in `TestcontainersConfiguration` (both services) are now pinned to the versions in `docker-compose.infra.yml`:

| Container | Image |
|---|---|
| PostgreSQL | `postgres:15-alpine` |
| Redis | `redis:7-alpine` |
| Keycloak | `keycloak:26.5.2` |
| Kafka | `apache/kafka-native:3.8.0` |

Kafka uses `kafka-native` because it is the image family that Testcontainers' `KafkaContainer` supports. Tests now run against the same versions as the real stack and are reproducible.

### 3.2 claims-service: new tests (`src/test/java/com/example/demo/integration/`)

**Shared setup:** `AbstractClaimsIntegrationTest` starts the **whole application** on a random port against real containers.

- Calls go over **real HTTP**, using **real Keycloak access tokens**:
  - password grant for `admin@demo.com` / `claimant@demo.com`
  - client-credentials for the BFF service account
- All classes share one Spring context, so the containers start once per run.
- Direct-Kafka mode is used (`app.events.cdc-enabled=false`, the README's documented default; note that `docker-compose.apps.yml` actually ships with CDC enabled, see `E2E_TEST_NOTES.md`).
- Tests use random IDs and assert on their own data or on deltas, so they are order-independent. No tables are truncated.

| Class | What it proves |
|---|---|
| `SecurityIntegrationTest` (14) | Requests are rejected without a token, with a malformed token, with a tampered signature, or with a valid token from another realm. Claimants get 403 on every admin endpoint. Admins get 200. Health check is public |
| `ClaimApiIntegrationTest` (12) | A claim is stored in Postgres for the token's subject (not the request body). The Location header is returned. Owners can read and list their own claims only. Unknown claim → 404. A token with no user record → 400 and nothing stored. 6 schema-violation cases → 400 and nothing stored |
| `ClaimLifecycleIntegrationTest` (6) | Full SUBMITTED → UNDER_REVIEW → APPROVED → CLOSED path over HTTP. The database and `updated_at` are checked at each step. **Exactly one Kafka event per transition**, in order, each with its own id, **read from the real topic**. An invalid transition changes nothing and publishes nothing. Unknown claim → 404. Unknown status → 400. The admin list filter returns owner details from the `users` table |
| `UserProvisioningIntegrationTest` (6) | BFF service-account provisioning → 201. Provisioning twice → 409 with no duplicate row. **Re-key on a new Keycloak id cascades to the user's existing claims** (relies on the `ON UPDATE CASCADE` foreign key; only a real database can prove this). No token → 401. Admin lookup works. Unknown user → 404 |
| `DashboardCacheIntegrationTest` (5) | Stats are written to Redis under `dashboard:stats` with a TTL of 300 s or less. A repeat request is a cache hit. `bypassCache` reads fresh counts. Flush deletes the key. Cached stats are not evicted when a claim is created |

`ClaimEventsProbe` reads `claim-events` like a real consumer would: a fresh consumer group, reading from the earliest offset, filtered by the claim-id key.

### 3.3 bff-service: new tests

`ClaimEventContractIntegrationTest` (7) covers the consumer side of the Kafka contract over a real broker. The messages are the **exact shape claims-service publishes**, plus Debezium's snake_case CDC shape.

It asserts **what** is broadcast over WebSocket and **to whom**:

- submitted claim → admins only
- status change → claim owner and admins
- no `userId` in the event → admins only
- a malformed message does not stop the listener
- unknown event types are ignored
- CDC updates and inserts are handled

The existing `ClaimEventKafkaConsumerIntegrationTest` only checked that *some* broadcast happened (`any()`). Its CDC test also used an `id` column where Debezium sends `claim_id`.

---

## 4. Defects found

| # | Severity | Defect | Evidence |
|---|---|---|---|
| **D1** | **High** | **Admin authorization is not enforced in claims-service.** A claimant token gets **200** on the admin endpoints: list all claims with other users' names and emails, dashboard stats, list all users. Cache flush returns **204**. Status change returns **500**, because only the use case's own admin check catches it and that exception is unhandled. claims-service is published on host port 8080, and any user can get a token from the public `demo-app-ui` client, so the APIs are reachable directly, not only through the BFF | `SecurityIntegrationTest` (5 failures) |
| **D2** | Medium | **The dashboard cache never returns a hit.** Values are written to Redis but cannot be read back: `LinkedHashMap cannot be cast to DashboardStats`. The error is caught and logged as *"Redis unavailable"*, so stats are recalculated on every request | `DashboardCacheIntegrationTest` (2 failures), WARN log |
| **D3** | Medium | **An invalid status transition returns 500**, but the spec documents **400**. `InvalidStatusTransitionException` has no exception handler. The database is correctly left unchanged and no event is sent | `ClaimLifecycleIntegrationTest` |
| **D4** | Low | **An unknown user returns 400**, but the spec documents **404**. `UserNotFoundException` is mapped to `BAD_REQUEST` | `UserProvisioningIntegrationTest` |

**Root causes and suggested fixes (for the developers):**

- **D1:** The OpenAPI generator puts `@RequestMapping` on a generated default method (e.g. `_listAllClaims`), which then calls the overridden `listAllClaims` on `this`. That is a self-invocation, so Spring's method-security proxy is bypassed and `@PreAuthorize` never runs.
  - The unit tests call controller methods directly, so they can't detect this.
  - Fix: enforce roles by URL in `SecurityConfig`, e.g. `.requestMatchers("/api/admin/**").hasRole("ADMIN")` plus `GET /api/users`, and/or change the generator setup (e.g. the delegate pattern). The `SecurityIntegrationTest` cases then act as the regression test.
- **D2:** `RedisConfig` builds `GenericJacksonJsonRedisSerializer` from the application `ObjectMapper`, which has no type information, so values read back as `Map`.
  - Fix: use a typed serializer (`JacksonJsonRedisSerializer<DashboardStats>`) or enable default typing.
  - Note: the existing `DashboardStatsCacheTest` mocks `RedisTemplate`, which is why it passes.
- **D3 / D4:** Add or correct the handlers in `ClaimExceptionHandler`.

**Other observations (not covered by failing tests):**

1. `SecurityConfig.jwtDecoder()` replaces the default JWT validators with a timestamp-only validator, so the **issuer is not checked**. Tokens from another realm are still rejected, but only because they are signed with different keys. Suggestion: add `JwtValidators.createDefaultWithIssuer(...)`.
2. Once D2 is fixed, cached dashboard stats will be **up to 5 minutes stale** after new claims, because nothing evicts the cache on write. `should_KeepServingStaleStats…` documents this design. If it is not wanted, evict in the create/update use cases and change that test.
3. `PATCH /api/claims/{claimId}` (`updateClaim`) is in the API spec but **not implemented** by `ClaimController`.
4. In the test realm, Keycloak user IDs are generated at import, so the `users` rows are seeded from each token's `sub` claim. In the real stack, the BFF creates these rows at signup.

---

## 5. How to run

Requires **Docker** running. The first run pulls images, which takes a few minutes. Afterwards a full run takes about 3–4 minutes, mostly Keycloak startup.

```powershell
# claims-service: integration tests only
cd code\claims-service
mvn test "-Dgroups=integration" "-Dsurefire.excludedGroups="

# one class
mvn test "-Dtest=SecurityIntegrationTest" "-Dgroups=integration" "-Dsurefire.excludedGroups="

# bff-service
cd code\bff-service
mvn test "-Dgroups=integration" "-Dsurefire.excludedGroups="

# unit + integration together
mvn test "-Dsurefire.excludedGroups="
```

- Plain `mvn test` still runs **unit tests only** (no Docker needed).
- On JDK 25 the output includes JaCoCo "Unsupported class file major version 69" stack traces. They are harmless; add `"-Djacoco.skip=true"` to silence them, or use JDK 21.
- The Maven wrapper's `.mvn/` folder is missing from the repository, so use the installed `mvn`.
