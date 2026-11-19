package com.example.demo.integration;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User provisioning as called by the BFF at signup (service-account token), against real PostgreSQL.
 *
 * <p>The re-key case is the one only a real database can verify: changing a user's primary key relies on the
 * {@code claims.user_id} foreign key being {@code ON UPDATE CASCADE}, otherwise the user's existing claims would
 * block the update or be orphaned.
 */
class UserProvisioningIntegrationTest extends AbstractClaimsIntegrationTest {

    @Test
    void should_CreateUser_When_ProvisionedByBffServiceAccount() {
        UUID userId = UUID.randomUUID();
        String email = uniqueEmail("new");

        ApiResponse response = post("/api/users", bffServiceAccountToken(), userRequest(userId, email));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().path("userId").asText()).isEqualTo(userId.toString());
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE user_id = ?", String.class, userId))
                .isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE user_id = ?", String.class, userId))
                .isEqualTo("CLAIMANT");
    }

    @Test
    void should_Return409WithExistingUser_AndNotDuplicate_When_ProvisionedTwice() {
        UUID userId = UUID.randomUUID();
        String email = uniqueEmail("twice");
        post("/api/users", bffServiceAccountToken(), userRequest(userId, email));

        ApiResponse second = post("/api/users", bffServiceAccountToken(), userRequest(userId, email));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.json().path("userId").asText()).isEqualTo(userId.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email))
                .isEqualTo(1);
    }

    @Test
    void should_RekeyUser_AndCascadeToExistingClaims_When_KeycloakIdChanges() {
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        String email = uniqueEmail("rekey");
        post("/api/users", bffServiceAccountToken(), userRequest(oldId, email));
        UUID claimId = insertClaimFor(oldId);

        // Same email, new Keycloak id (e.g. Keycloak was recreated and the user signed up again)
        ApiResponse response = post("/api/users", bffServiceAccountToken(), userRequest(newId, email));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().path("userId").asText()).isEqualTo(newId.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id = ?", Long.class, oldId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM claims WHERE claim_id = ?", UUID.class, claimId))
                .as("existing claims must follow the user to the new id")
                .isEqualTo(newId);
    }

    @Test
    void should_Return401_When_ProvisioningWithoutToken() {
        ApiResponse response = post("/api/users", null, userRequest(UUID.randomUUID(), uniqueEmail("anon")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void should_ReturnUser_When_AdminLooksUpExistingUser() {
        ApiResponse response = get("/api/users/" + claimantId, adminToken);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("email").asText()).isEqualTo(CLAIMANT_EMAIL);
    }

    @Test
    void should_Return404_When_UserDoesNotExist() {
        // API spec documents 404 for an unknown user.
        ApiResponse response = get("/api/users/" + UUID.randomUUID(), adminToken);

        assertThat(response.status()).as(response.body()).isEqualTo(404);
    }

    private static String userRequest(UUID userId, String email) {
        return """
                {"userId":"%s","email":"%s","name":"Integration User","role":"CLAIMANT"}
                """.formatted(userId, email);
    }

    private UUID insertClaimFor(UUID userId) {
        UUID claimId = UUID.randomUUID();
        jdbc.update("INSERT INTO claims (claim_id, user_id, incident_date, incident_location, description, "
                        + "claim_amount, status) VALUES (?, ?, DATE '2026-02-15', ?, ?, 100.00, 'SUBMITTED')",
                claimId, userId, "1 Test Street, Testville", "Claim filed before the Keycloak id changed");
        return claimId;
    }
}
