package com.example.demo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claimant-facing claim API end to end: HTTP -> security -> use case -> JPA -> real PostgreSQL schema
 * (created by the real Liquibase changelog), and back.
 */
class ClaimApiIntegrationTest extends AbstractClaimsIntegrationTest {

    @Test
    void should_PersistClaimForTokenSubject_When_ClaimantSubmitsClaim() {
        ApiResponse response = post("/api/claims", claimantToken, validClaimRequest());

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode body = response.json();
        UUID claimId = UUID.fromString(body.path("claimId").asText());
        assertThat(response.location()).isEqualTo("/api/claims/" + claimId);
        assertThat(body.path("status").asText()).isEqualTo("SUBMITTED");
        // The owner comes from the verified token, never from the request body.
        assertThat(body.path("userId").asText()).isEqualTo(claimantId.toString());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM claims WHERE claim_id = ?", claimId);
        assertThat(row.get("user_id")).isEqualTo(claimantId);
        assertThat(row.get("status")).isEqualTo("SUBMITTED");
        assertThat(row.get("incident_location")).isEqualTo("123 Main St, Springfield, IL");
        assertThat((BigDecimal) row.get("claim_amount")).isEqualByComparingTo("2500.50");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void should_ReturnOwnClaim_When_OwnerRequestsIt() {
        UUID claimId = createClaim(claimantToken);

        ApiResponse response = get("/api/claims/" + claimId, claimantToken);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("claimId").asText()).isEqualTo(claimId.toString());
        assertThat(response.json().path("description").asText())
                .isEqualTo("Water damage to basement due to pipe burst");
    }

    @Test
    void should_ListOnlyOwnClaims_When_ClaimantListsClaims() {
        UUID ownClaim = createClaim(claimantToken);
        UUID someoneElsesClaim = createClaim(adminToken);

        ApiResponse response = get("/api/claims", claimantToken);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().findValuesAsText("claimId"))
                .contains(ownClaim.toString())
                .doesNotContain(someoneElsesClaim.toString());
    }

    @Test
    void should_Return403_When_UserReadsSomeoneElsesClaim() {
        UUID claimantsClaim = createClaim(claimantToken);

        ApiResponse response = get("/api/claims/" + claimantsClaim, adminToken);

        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void should_Return404WithProblemDetail_When_ClaimDoesNotExist() {
        ApiResponse response = get("/api/claims/" + UUID.randomUUID(), claimantToken);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().path("status").asInt()).isEqualTo(404);
    }

    @Test
    void should_Return400_When_TokenSubjectHasNoUserRecord() {
        // The BFF service account is a valid, authenticated principal, but not a provisioned user.
        long before = claimCount();

        ApiResponse response = post("/api/claims", bffServiceAccountToken(), validClaimRequest());

        assertThat(response.status()).isEqualTo(400);
        assertThat(claimCount()).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClaimRequests")
    void should_Return400AndPersistNothing_When_RequestViolatesSchema(String reason, String body) {
        long before = claimCount();

        ApiResponse response = post("/api/claims", claimantToken, body);

        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(claimCount()).isEqualTo(before);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidClaimRequests() {
        String validDescription = "Water damage to basement due to pipe burst";
        String validLocation = "123 Main St, Springfield, IL";
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("amount zero",
                        claimRequest("2026-02-15", validLocation, validDescription, 0)),
                org.junit.jupiter.params.provider.Arguments.of("amount above 1,000,000",
                        claimRequest("2026-02-15", validLocation, validDescription, 1_000_000.01)),
                org.junit.jupiter.params.provider.Arguments.of("location shorter than 5",
                        claimRequest("2026-02-15", "abc", validDescription, 100)),
                org.junit.jupiter.params.provider.Arguments.of("description shorter than 10",
                        claimRequest("2026-02-15", validLocation, "too short", 100)),
                org.junit.jupiter.params.provider.Arguments.of("missing description",
                        "{\"incidentDate\":\"2026-02-15\",\"incidentLocation\":\"" + validLocation
                                + "\",\"claimAmount\":100}"),
                org.junit.jupiter.params.provider.Arguments.of("malformed JSON", "{\"incidentDate\":"));
    }

    private long claimCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM claims", Long.class);
    }
}
