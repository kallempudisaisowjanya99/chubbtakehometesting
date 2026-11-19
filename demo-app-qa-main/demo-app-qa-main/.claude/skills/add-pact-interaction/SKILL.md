---
name: add-pact-interaction
description: Add or change a Pact contract interaction between bff-service (consumer) and claims-service (provider), including the provider state handler, then regenerate and verify the pact. Use when the BFF starts calling a new claims-service endpoint, a request or response shape changes, or the user asks to extend the contract tests.
---

# Add a Pact interaction (BFF → claims-service)

| Side | File |
|---|---|
| Consumer | `code/bff-service/src/test/java/com/example/demo/contract/ClaimsServiceConsumerPactTest.java` |
| Pact file (committed) | `code/bff-service/pacts/bff-service-claims-service.json` |
| Provider | `code/claims-service/src/test/java/com/example/demo/contract/ClaimsServiceProviderPactTest.java` |

Pact JVM 4.6.x, spec V3, `@Tag("contract")`. The consumer class is flat (no `@Nested`).

## 1. Consumer side

1. Add a `@Pact(consumer = "bff-service")` method that returns `RequestResponsePact`:
   - `.given("<provider state>", params)`: reuse an existing state name if one fits.
   - Put IDs in the path with `pathFromProviderState("/api/claims/${claimId}", "<example>")`, so the provider supplies real IDs.
   - Use the token placeholders `claimant-token`, `admin-token` or `service-account-token` for `Authorization`, matching what the BFF really sends. Admin calls go out with the **BFF service-account token**.
   - Match on type, not value (`stringType`, `uuid`, `decimalType`, `datetime`, `eachLike`). Only pin values the BFF actually depends on (e.g. `status`).
   - Put `.object(...)` blocks with `eachKeyLike` **last** in the body: `closeObject()` returns a `DslPart`, so you can't chain after it.
2. Add a `@Test @PactTestFor(pactMethod = "...")` that drives the **real BFF code** (`ClaimsServiceAdapter` or `AdminController`, built as in the existing tests) against `mockServer.getUrl()`. Assert what the BFF returns, not the raw HTTP.
3. Run `mvn test -Dtest=ClaimsServiceConsumerPactTest` in `code/bff-service`. Check that the pact JSON changed as you intended.

## 2. Provider side

1. A new provider state needs a `@State("<same name>")` method in the provider test. It creates the data through the real API or repository and **returns a Map** of the values the consumer asked for (e.g. `claimId`, `userId`).
2. State changes run in `beforeTestExecution`, **after** `@BeforeEach`. Don't rely on order with `@BeforeEach` data.
3. Clean up only data the state created (e.g. users with `pact.*` emails). Never wipe shared data.
4. The provider replaces the placeholder `Authorization` header with real Keycloak tokens. A new placeholder needs a mapping there.
5. Run: `cd code/claims-service && mvn test -Dgroups=contract -Dsurefire.excludedGroups= -Djacoco.skip=true` (needs Docker).

## 3. Interpreting a provider failure

- The provider returns something different from the contract: that is a **product defect or a deliberate API change**, never something to fix by loosening the matcher. D3 (500 instead of 400) is the known example.
- The provider can't set up the state: fix the state handler.
- The generated okhttp-gson client in the BFF **rejects unknown response fields**, which Pact can't detect. Note any new response field in `CONTRACT_TEST_NOTES.md`.

## 4. Afterwards

Update the interaction table and counts in `CONTRACT_TEST_NOTES.md`, and the Contract row in TEST_STRATEGY.md §4. Commit the pact JSON together with the tests.
