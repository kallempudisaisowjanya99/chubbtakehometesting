package com.example.demo.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication and authorization with <b>real Keycloak tokens</b>.
 *
 * <p>The unit tests use mocked JWTs, so they cannot prove that the service actually validates Keycloak's
 * signature and issuer, or that Keycloak's lowercase realm roles ({@code admin}, {@code claimant}) are mapped
 * to the {@code ROLE_ADMIN}/{@code ROLE_CLAIMANT} authorities that {@code @PreAuthorize} checks.
 */
class SecurityIntegrationTest extends AbstractClaimsIntegrationTest {

    @Test
    void should_AllowAnonymousHealthCheck() {
        assertThat(get("/actuator/health", null).status()).isEqualTo(200);
    }

    @Test
    void should_Return401_When_NoTokenProvided() {
        assertThat(get("/api/claims", null).status()).isEqualTo(401);
    }

    @Test
    void should_Return401_When_TokenIsNotAValidJwt() {
        assertThat(get("/api/claims", "not-a-real-token").status()).isEqualTo(401);
    }

    @Test
    void should_Return401_When_TokenSignatureTampered() {
        String[] parts = claimantToken.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();

        assertThat(get("/api/claims", tampered).status()).isEqualTo(401);
    }

    @Test
    void should_Return401_When_TokenIssuedByAnotherRealm() {
        // Genuine, correctly signed Keycloak token — but from the "master" realm (wrong issuer / keys).
        assertThat(get("/api/claims", foreignRealmToken()).status()).isEqualTo(401);
    }

    @ParameterizedTest(name = "claimant {0} {1} -> 403")
    @CsvSource({
            "GET,   /api/admin/claims",
            "GET,   /api/admin/dashboard/stats",
            "POST,  /api/admin/dashboard/flush-cache",
            "PATCH, /api/admin/claims/{claimId}/status",
            "GET,   /api/users"
    })
    void should_Return403_When_ClaimantCallsAdminEndpoint(String method, String pathTemplate) {
        UUID claimId = createClaim(claimantToken);
        String path = pathTemplate.replace("{claimId}", claimId.toString());

        ApiResponse response = switch (method) {
            case "GET" -> get(path, claimantToken);
            case "POST" -> post(path, claimantToken, null);
            case "PATCH" -> patch(path, claimantToken, "{\"newStatus\":\"APPROVED\"}");
            default -> throw new IllegalArgumentException(method);
        };

        assertThat(response.status()).as(response.body()).isEqualTo(403);
        // A forbidden status change must not have touched the claim.
        assertThat(dbStatusOf(claimId)).isEqualTo("SUBMITTED");
    }

    @ParameterizedTest(name = "admin GET {0} -> 200")
    @CsvSource({
            "/api/admin/claims",
            "/api/admin/dashboard/stats",
            "/api/users"
    })
    void should_Return200_When_AdminCallsAdminEndpoint(String path) {
        ApiResponse response = get(path, adminToken);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    @Test
    void should_AllowClaimantToUseClaimantEndpoints() {
        assertThat(get("/api/claims", claimantToken).status()).isEqualTo(200);
        assertThat(post("/api/claims", claimantToken, validClaimRequest()).status()).isEqualTo(201);
    }
}
