package com.example.demo.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.example.demo.integration.AbstractClaimsIntegrationTest;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider side of the BFF → claims-service contract: replays every interaction in the BFF's pact against the
 * real claims-service.
 *
 * <p>Runs on the same Testcontainers stack as the integration tests (PostgreSQL, Redis, Kafka, Keycloak), and
 * shares their Spring context. So responses come from the real controllers, security, database and error
 * handlers, not from mocks.
 *
 * <p>The pact's bearer tokens are placeholders naming the caller. Before each interaction they are replaced
 * with a real Keycloak token for that caller, so authentication and the ownership checks run as in production.
 *
 * <p>Pact source: {@code ../bff-service/pacts}, written by {@code ClaimsServiceConsumerPactTest} in the BFF.
 * Run the BFF consumer test first.
 */
@Tag("contract")
@Provider("claims-service")
@PactFolder("../bff-service/pacts")
class ClaimsServiceProviderPactTest extends AbstractClaimsIntegrationTest {

    @BeforeEach
    void pointPactAtTheRunningService(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyInteraction(PactVerificationContext context, HttpRequest request) {
        var header = request.getFirstHeader("Authorization");
        if (header != null) {
            request.setHeader("Authorization", "Bearer " + realTokenFor(header.getValue()));
        }
        context.verifyInteraction();
    }

    private String realTokenFor(String placeholder) {
        return switch (placeholder.substring("Bearer ".length())) {
            case "claimant-token" -> claimantToken;
            case "admin-token" -> adminToken;
            case "service-account-token" -> bffServiceAccountToken();
            default -> throw new IllegalArgumentException("Unknown token placeholder in pact: " + placeholder);
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Provider states. The base class has already seeded the admin and the claimant for each
    // interaction (they run after @BeforeEach).
    // ---------------------------------------------------------------------------------------------

    @State("the admin and the claimant are registered")
    void usersRegistered() {
        // Seeded by AbstractClaimsIntegrationTest
    }

    @State("the claimant has a submitted claim")
    Map<String, Object> claimantHasASubmittedClaim() {
        UUID claimId = createClaim(claimantToken);
        return Map.of("claimId", claimId.toString(), "userId", claimantId.toString());
    }

    @State("a claim owned by another user exists")
    Map<String, Object> claimOwnedByAnotherUser() {
        // Any user can submit a claim, so the admin owns this one; the claimant must not see it.
        UUID claimId = createClaim(adminToken);
        return Map.of("claimId", claimId.toString());
    }

    @State("no claim exists with the requested id")
    void noSuchClaim() {
        // The pact uses an id that is never generated (a fixed, non-random UUID).
    }

    @State("a user exists")
    void userExists(Map<String, Object> params) {
        UUID userId = UUID.fromString((String) params.get("userId"));
        String email = (String) params.get("email");
        deleteUser(userId, email);
        seedUser(userId, email, "Pact New User", "CLAIMANT");
    }

    @State("no user exists with the given id and email")
    void noSuchUser(Map<String, Object> params) {
        deleteUser(UUID.fromString((String) params.get("userId")), (String) params.get("email"));
    }

    /** Removes a test user left by a previous run (containers are reused across runs). */
    private void deleteUser(UUID userId, String email) {
        assertThat(email).as("only pact test users may be deleted").startsWith("pact.");
        jdbc.update("DELETE FROM claims WHERE user_id IN (SELECT user_id FROM users WHERE user_id = ? OR email = ?)",
                userId, email);
        jdbc.update("DELETE FROM users WHERE user_id = ? OR email = ?", userId, email);
    }
}
