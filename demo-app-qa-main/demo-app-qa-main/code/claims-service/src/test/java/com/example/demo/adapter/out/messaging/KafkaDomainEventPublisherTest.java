package com.example.demo.adapter.out.messaging;

import com.example.demo.domain.DomainEvent;
import com.example.demo.domain.claim.ClaimStatus;
import com.example.demo.domain.claim.events.ClaimStatusChanged;
import com.example.demo.domain.claim.events.ClaimSubmitted;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link KafkaDomainEventPublisher}.
 *
 * <p>The publisher now wraps every domain event in an envelope
 * {@code {"eventType", "eventId", "payload": {...}}} built with {@code objectMapper.createObjectNode()}
 * — the shape the BFF's {@code ClaimEventKafkaConsumer} reads. The previous tests mocked
 * {@link ObjectMapper}, so {@code createObjectNode()} returned {@code null} and the tests failed with NPEs.
 * A real (spied) ObjectMapper is used so the actual envelope can be asserted, while still allowing a
 * serialization failure to be forced.
 */
@ExtendWith(MockitoExtension.class)
class KafkaDomainEventPublisherTest {

    private static final String TOPIC = "claim-events";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private KafkaMessageSchemaValidator schemaValidator;

    private ObjectMapper objectMapper;
    private final ObjectMapper reader = new ObjectMapper();
    private KafkaDomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = spy(new ObjectMapper());
        publisher = new KafkaDomainEventPublisher(kafkaTemplate, objectMapper, schemaValidator);
    }

    // TC-077: Event published to Kafka with correct topic, message key and schema
    @Test
    void should_PublishEventToKafka_When_ClaimSubmittedEventProvided() {
        ClaimSubmitted event = claimSubmitted();

        publisher.publish(event);

        String payload = capturePublishedPayload(event.claimId());
        verify(schemaValidator).validate(payload, "ClaimSubmittedMessage");
    }

    // TC-077b: ClaimStatusChanged published with correct key and schema
    @Test
    void should_PublishEventToKafka_When_ClaimStatusChangedEventProvided() {
        ClaimStatusChanged event = claimStatusChanged(UUID.randomUUID());

        publisher.publish(event);

        String payload = capturePublishedPayload(event.claimId());
        verify(schemaValidator).validate(payload, "ClaimStatusChangedMessage");
    }

    // TC-078: ClaimSubmitted envelope matches the contract consumed by the BFF
    @Test
    void should_BuildClaimSubmittedEnvelope_When_EventPublished() throws Exception {
        ClaimSubmitted event = claimSubmitted();

        publisher.publish(event);

        JsonNode envelope = reader.readTree(capturePublishedPayload(event.claimId()));
        assertThat(envelope.path("eventType").asText()).isEqualTo("claim-submitted");
        assertThat(envelope.path("eventId").asText()).isEqualTo(event.eventId());

        JsonNode payload = envelope.path("payload");
        assertThat(payload.path("claimId").asText()).isEqualTo(event.claimId().toString());
        assertThat(payload.path("userId").asText()).isEqualTo(event.userId().toString());
        assertThat(payload.path("correlationId").asText()).isEqualTo(event.eventId());
    }

    // TC-078b: ClaimStatusChanged envelope matches the contract consumed by the BFF
    @Test
    void should_BuildClaimStatusChangedEnvelope_When_EventPublished() throws Exception {
        UUID changedBy = UUID.randomUUID();
        ClaimStatusChanged event = claimStatusChanged(changedBy);

        publisher.publish(event);

        JsonNode envelope = reader.readTree(capturePublishedPayload(event.claimId()));
        assertThat(envelope.path("eventType").asText()).isEqualTo("claim-status-changed");
        assertThat(envelope.path("eventId").asText()).isEqualTo(event.eventId());

        JsonNode payload = envelope.path("payload");
        assertThat(payload.path("claimId").asText()).isEqualTo(event.claimId().toString());
        assertThat(payload.path("oldStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(payload.path("newStatus").asText()).isEqualTo("UNDER_REVIEW");
        assertThat(payload.path("userId").asText()).isEqualTo(changedBy.toString());
        assertThat(payload.path("correlationId").asText()).isEqualTo(event.eventId());
    }

    // TC-078c: changedBy is optional — userId omitted rather than serialized as "null"
    @Test
    void should_OmitUserId_When_ChangedByIsNull() throws Exception {
        ClaimStatusChanged event = claimStatusChanged(null);

        publisher.publish(event);

        JsonNode payload = reader.readTree(capturePublishedPayload(event.claimId())).path("payload");
        assertThat(payload.has("userId")).isFalse();
    }

    // TC-079: Failed serialization throws IllegalStateException and nothing is sent
    @Test
    void should_ThrowIllegalStateException_When_SerializationFails() throws JsonProcessingException {
        doThrow(new JsonProcessingException("test") {}).when(objectMapper).writeValueAsString(any(ObjectNode.class));

        assertThatThrownBy(() -> publisher.publish(claimSubmitted()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize domain event");

        verifyNoInteractions(schemaValidator, kafkaTemplate);
    }

    // TC-NEW-002: Validator rejection propagates and the message is NOT sent
    @Test
    void should_PropagateValidationException_When_ValidatorRejectsMissingRequiredField() {
        doThrow(new KafkaMessageValidationException("claimId: is missing but it is required"))
                .when(schemaValidator).validate(anyString(), eq("ClaimSubmittedMessage"));

        assertThatThrownBy(() -> publisher.publish(claimSubmitted()))
                .isInstanceOf(KafkaMessageValidationException.class)
                .hasMessageContaining("claimId");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    // TC-NEW-003: Validator rejection for status-changed events propagates and nothing is sent
    @Test
    void should_PropagateValidationException_When_ValidatorRejectsInvalidClaimStatus() {
        doThrow(new KafkaMessageValidationException("oldStatus: does not have a value in the enumeration"))
                .when(schemaValidator).validate(anyString(), eq("ClaimStatusChangedMessage"));

        assertThatThrownBy(() -> publisher.publish(claimStatusChanged(UUID.randomUUID())))
                .isInstanceOf(KafkaMessageValidationException.class)
                .hasMessageContaining("oldStatus");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    // Unknown event types are rejected before anything is validated or sent
    @Test
    void should_ThrowIllegalArgumentException_When_EventTypeUnknown() {
        DomainEvent unknown = new DomainEvent() { };

        assertThatThrownBy(() -> publisher.publish(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown domain event type");

        verifyNoInteractions(schemaValidator, kafkaTemplate);
    }

    // ---------------------------------------------------------------------------------------------

    private String capturePublishedPayload(UUID expectedKey) {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq(expectedKey.toString()), payloadCaptor.capture());
        return payloadCaptor.getValue();
    }

    private static ClaimSubmitted claimSubmitted() {
        return new ClaimSubmitted(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.now(),
                BigDecimal.TEN,
                Instant.now()
        );
    }

    private static ClaimStatusChanged claimStatusChanged(UUID changedBy) {
        return new ClaimStatusChanged(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                ClaimStatus.SUBMITTED,
                ClaimStatus.UNDER_REVIEW,
                changedBy,
                Instant.now()
        );
    }
}
