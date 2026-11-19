package com.example.demo.adapter.in.messaging;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.adapter.in.websocket.ClaimWebSocketHandler;
import com.example.demo.adapter.in.websocket.WebSocketMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Consumer side of the {@code claim-events} contract, over a real Kafka broker.
 *
 * <p>The messages here are <b>byte-for-byte the shape claims-service publishes</b>
 * ({@code KafkaDomainEventPublisher}: {@code {"eventType","eventId","payload":{...}}}, keyed by claimId) and
 * the shape Debezium publishes for the {@code claims} table (snake_case column names). The pre-existing
 * {@link ClaimEventKafkaConsumerIntegrationTest} uses a richer, hand-written envelope and only checks that
 * <i>some</i> broadcast happened; these tests check <i>what</i> is broadcast and to <i>whom</i>.
 *
 * <p>Every test uses a fresh random claimId and matches on it, so messages from other tests (the topic is
 * shared within the run) cannot produce false passes.
 *
 * <p>The WebSocket handler is the only mock: it is the boundary to browser sessions, which are out of scope.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "kafka.consumer.auto-startup=true"
        }
)
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class ClaimEventContractIntegrationTest {

    private static final long TIMEOUT_MS = 30_000;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private ClaimWebSocketHandler webSocketHandler;

    @Test
    void should_BroadcastSubmittedClaimToAdmins_When_ClaimsServicePublishesClaimSubmitted() {
        String claimId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        kafkaTemplate.send("claim-events", claimId, claimSubmitted(eventId, claimId, UUID.randomUUID().toString()));

        WebSocketMessage message = awaitAdminBroadcast(claimId);
        assertThat(message.type()).isEqualTo("CLAIM_SUBMITTED");
        assertThat(message.newStatus()).isEqualTo("SUBMITTED");
        assertThat(message.oldStatus()).isNull();
        assertThat(message.correlationId()).isEqualTo(eventId);
        assertThat(message.timestamp()).isNotNull();
    }

    @Test
    void should_NotifyOwnerAndAdmins_When_ClaimsServicePublishesStatusChangeWithUserId() {
        String claimId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        kafkaTemplate.send("claim-events", claimId,
                claimStatusChanged(eventId, claimId, "SUBMITTED", "UNDER_REVIEW", ownerId));

        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(webSocketHandler, timeout(TIMEOUT_MS))
                .broadcastToUserAndAdmins(eq(ownerId), argThat(m -> claimId.equals(m.claimId())));
        verify(webSocketHandler).broadcastToUserAndAdmins(eq(ownerId), captor.capture());
        WebSocketMessage message = captor.getValue();
        assertThat(message.type()).isEqualTo("CLAIM_STATUS_CHANGED");
        assertThat(message.oldStatus()).isEqualTo("SUBMITTED");
        assertThat(message.newStatus()).isEqualTo("UNDER_REVIEW");
        assertThat(message.correlationId()).isEqualTo(eventId);
    }

    @Test
    void should_NotifyAdminsOnly_When_StatusChangeHasNoUserId() {
        // claims-service omits payload.userId when the event's changedBy is null.
        String claimId = UUID.randomUUID().toString();

        kafkaTemplate.send("claim-events", claimId,
                claimStatusChanged(UUID.randomUUID().toString(), claimId, "UNDER_REVIEW", "APPROVED", null));

        WebSocketMessage message = awaitAdminBroadcast(claimId);
        assertThat(message.newStatus()).isEqualTo("APPROVED");
        verify(webSocketHandler, never())
                .broadcastToUserAndAdmins(anyString(), argThat(m -> claimId.equals(m.claimId())));
    }

    @Test
    void should_KeepConsuming_When_MalformedMessageArrives() {
        String claimId = UUID.randomUUID().toString();

        // A poison message must not stop the listener...
        kafkaTemplate.send("claim-events", claimId, "{ this is not json");
        // ...so the next valid event for the same claim is still delivered.
        kafkaTemplate.send("claim-events", claimId,
                claimSubmitted(UUID.randomUUID().toString(), claimId, UUID.randomUUID().toString()));

        assertThat(awaitAdminBroadcast(claimId).type()).isEqualTo("CLAIM_SUBMITTED");
    }

    @Test
    void should_IgnoreUnknownEventType() {
        String claimId = UUID.randomUUID().toString();

        kafkaTemplate.send("claim-events", claimId, """
                {"eventType":"claim-archived","eventId":"%s","payload":{"claimId":"%s"}}
                """.formatted(UUID.randomUUID(), claimId));

        verify(webSocketHandler, after(3_000).never())
                .broadcastToAdmins(argThat(m -> claimId.equals(m.claimId())));
        verify(webSocketHandler, never())
                .broadcastToUserAndAdmins(any(), argThat(m -> claimId.equals(m.claimId())));
    }

    @Test
    void should_NotifyOwnerAndAdmins_When_DebeziumPublishesClaimUpdate() {
        // Debezium uses the table's column names (claim_id, user_id), not the domain's camelCase.
        String claimId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();

        kafkaTemplate.send("cdc.demo-app.public.claims", claimId, """
                {"op":"u",
                 "before":{"claim_id":"%1$s","user_id":"%2$s","status":"SUBMITTED"},
                 "after":{"claim_id":"%1$s","user_id":"%2$s","status":"REJECTED"},
                 "ts_ms":1771234567890}
                """.formatted(claimId, ownerId));

        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(webSocketHandler, timeout(TIMEOUT_MS))
                .broadcastToUserAndAdmins(eq(ownerId), argThat(m -> claimId.equals(m.claimId())));
        verify(webSocketHandler).broadcastToUserAndAdmins(eq(ownerId), captor.capture());
        assertThat(captor.getValue().oldStatus()).isEqualTo("SUBMITTED");
        assertThat(captor.getValue().newStatus()).isEqualTo("REJECTED");
        assertThat(captor.getValue().timestamp().toEpochMilli()).isEqualTo(1771234567890L);
    }

    @Test
    void should_BroadcastToAdmins_When_DebeziumPublishesClaimInsert() {
        String claimId = UUID.randomUUID().toString();

        kafkaTemplate.send("cdc.demo-app.public.claims", claimId, """
                {"op":"c","before":null,
                 "after":{"claim_id":"%s","user_id":"%s","status":"SUBMITTED"},
                 "ts_ms":1771234567890}
                """.formatted(claimId, UUID.randomUUID()));

        assertThat(awaitAdminBroadcast(claimId).type()).isEqualTo("CLAIM_SUBMITTED");
    }

    // ---------------------------------------------------------------------------------------------

    private WebSocketMessage awaitAdminBroadcast(String claimId) {
        verify(webSocketHandler, timeout(TIMEOUT_MS)).broadcastToAdmins(argThat(m -> claimId.equals(m.claimId())));
        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(webSocketHandler, org.mockito.Mockito.atLeastOnce()).broadcastToAdmins(captor.capture());
        return captor.getAllValues().stream()
                .filter(m -> claimId.equals(m.claimId()))
                .findFirst()
                .orElseThrow();
    }

    /** Exactly what claims-service's KafkaDomainEventPublisher produces for ClaimSubmitted. */
    private static String claimSubmitted(String eventId, String claimId, String userId) {
        return """
                {"eventType":"claim-submitted","eventId":"%1$s",
                 "payload":{"claimId":"%2$s","userId":"%3$s","correlationId":"%1$s"}}
                """.formatted(eventId, claimId, userId);
    }

    /** Exactly what claims-service's KafkaDomainEventPublisher produces for ClaimStatusChanged. */
    private static String claimStatusChanged(String eventId, String claimId, String oldStatus, String newStatus,
                                             String userId) {
        String userField = userId == null ? "" : ",\"userId\":\"" + userId + "\"";
        return """
                {"eventType":"claim-status-changed","eventId":"%1$s",
                 "payload":{"claimId":"%2$s","newStatus":"%4$s","oldStatus":"%3$s"%5$s,"correlationId":"%1$s"}}
                """.formatted(eventId, claimId, oldStatus, newStatus, userField);
    }
}
