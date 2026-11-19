package com.example.demo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim lifecycle across the whole pipeline: admin status change over HTTP -> PostgreSQL -> transaction commit
 * -> {@code @TransactionalEventListener} -> real Kafka topic {@code claim-events}.
 *
 * <p>The Kafka assertions read the topic like the BFF does, so they verify the message that is really
 * delivered (key, envelope, fields) — not just what the publisher intended to send.
 */
class ClaimLifecycleIntegrationTest extends AbstractClaimsIntegrationTest {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(20);
    /** How long to keep listening when asserting that NO event was published. */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(5);

    private ClaimEventsProbe events;

    @BeforeEach
    void openProbe() {
        events = new ClaimEventsProbe(kafka.getBootstrapServers());
    }

    @AfterEach
    void closeProbe() {
        events.close();
    }

    @Test
    void should_PublishClaimSubmittedEvent_When_ClaimCreated() {
        UUID claimId = createClaim(claimantToken);

        List<JsonNode> published = events.awaitEvents(claimId, 1, EVENT_TIMEOUT);

        assertThat(published).hasSize(1);
        JsonNode envelope = published.get(0);
        assertThat(envelope.path("eventType").asText()).isEqualTo("claim-submitted");
        assertThat(envelope.path("eventId").asText()).isNotBlank();
        assertThat(envelope.path("payload").path("claimId").asText()).isEqualTo(claimId.toString());
        assertThat(envelope.path("payload").path("userId").asText()).isEqualTo(claimantId.toString());
        assertThat(envelope.path("payload").path("correlationId").asText())
                .isEqualTo(envelope.path("eventId").asText());
    }

    @Test
    void should_MoveThroughFullLifecycle_AndPublishOneEventPerTransition() {
        UUID claimId = createClaim(claimantToken);
        Timestamp createdUpdatedAt = updatedAtOf(claimId);

        for (String next : List.of("UNDER_REVIEW", "APPROVED", "CLOSED")) {
            ApiResponse response = updateStatus(claimId, next, adminToken);
            assertThat(response.status()).as("-> %s: %s", next, response.body()).isEqualTo(200);
            assertThat(response.json().path("status").asText()).isEqualTo(next);
            assertThat(dbStatusOf(claimId)).isEqualTo(next);
        }
        assertThat(updatedAtOf(claimId)).isAfter(createdUpdatedAt);

        // 1 x claim-submitted + 3 x claim-status-changed, in order, all keyed by the claim id
        List<JsonNode> published = events.awaitEvents(claimId, 4, EVENT_TIMEOUT);
        assertThat(published).extracting(e -> e.path("eventType").asText())
                .containsExactly("claim-submitted",
                        "claim-status-changed", "claim-status-changed", "claim-status-changed");
        assertThat(published.subList(1, 4))
                .extracting(e -> e.path("payload").path("oldStatus").asText() + "->"
                        + e.path("payload").path("newStatus").asText())
                .containsExactly("SUBMITTED->UNDER_REVIEW", "UNDER_REVIEW->APPROVED", "APPROVED->CLOSED");
        // Every event has its own id
        assertThat(published).extracting(e -> e.path("eventId").asText()).doesNotHaveDuplicates();
    }

    @Test
    void should_Return400_AndChangeNothing_When_TransitionNotAllowed() {
        UUID claimId = createClaim(claimantToken);
        assertThat(updateStatus(claimId, "REJECTED", adminToken).status()).isEqualTo(200);
        assertThat(updateStatus(claimId, "CLOSED", adminToken).status()).isEqualTo(200);
        events.awaitEvents(claimId, 3, EVENT_TIMEOUT);

        // CLOSED is terminal (ClaimStatus.canTransitionTo); the API spec documents 400 for this case.
        ApiResponse response = updateStatus(claimId, "APPROVED", adminToken);

        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(dbStatusOf(claimId)).isEqualTo("CLOSED");
        assertThat(events.eventsFor(claimId, QUIET_PERIOD))
                .as("a rejected transition must not publish an event")
                .hasSize(3);
    }

    @Test
    void should_Return404_When_UpdatingUnknownClaim() {
        ApiResponse response = updateStatus(UUID.randomUUID(), "UNDER_REVIEW", adminToken);

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void should_Return400_When_StatusValueUnknown() {
        UUID claimId = createClaim(claimantToken);

        ApiResponse response = updateStatus(claimId, "NOT_A_STATUS", adminToken);

        assertThat(response.status()).isEqualTo(400);
        assertThat(dbStatusOf(claimId)).isEqualTo("SUBMITTED");
    }

    @Test
    void should_FilterAdminClaimList_ByStatusAndUser_WithOwnerDetailsJoined() {
        UUID claimId = createClaim(claimantToken);
        updateStatus(claimId, "UNDER_REVIEW", adminToken);

        ApiResponse response = get("/api/admin/claims?status=UNDER_REVIEW&userId=" + claimantId, adminToken);

        assertThat(response.status()).isEqualTo(200);
        JsonNode match = null;
        for (JsonNode claim : response.json()) {
            assertThat(claim.path("status").asText()).isEqualTo("UNDER_REVIEW");
            assertThat(claim.path("userId").asText()).isEqualTo(claimantId.toString());
            if (claim.path("claimId").asText().equals(claimId.toString())) {
                match = claim;
            }
        }
        assertThat(match).as("updated claim must be in the filtered list").isNotNull();
        // Owner details come from a second repository (users table), joined per claim.
        assertThat(match.path("userEmail").asText()).isEqualTo(CLAIMANT_EMAIL);
        assertThat(match.path("userName").asText()).isEqualTo("Claimant User");
    }

    private Timestamp updatedAtOf(UUID claimId) {
        return jdbc.queryForObject("SELECT updated_at FROM claims WHERE claim_id = ?", Timestamp.class, claimId);
    }
}
