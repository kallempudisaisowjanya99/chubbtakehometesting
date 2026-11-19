package com.example.demo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the real {@code claim-events} topic, the way a downstream consumer (the BFF) would.
 *
 * <p>Uses a fresh consumer group reading from the earliest offset, and filters by message key
 * (= claimId), so it only ever looks at the messages produced by the calling test.
 */
final class ClaimEventsProbe implements AutoCloseable {

    static final String TOPIC = "claim-events";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    ClaimEventsProbe(String bootstrapServers) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "it-probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC));
    }

    /** Polls for up to {@code timeout} and returns every envelope seen so far for this key. */
    List<JsonNode> eventsFor(UUID claimId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            consumer.poll(Duration.ofMillis(250)).forEach(seen::add);
        } while (System.nanoTime() < deadline);
        return envelopesFor(claimId);
    }

    /** Polls until at least {@code count} envelopes for this key arrived, or the timeout elapses. */
    List<JsonNode> awaitEvents(UUID claimId, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (envelopesFor(claimId).size() < count && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach(seen::add);
        }
        return envelopesFor(claimId);
    }

    private List<JsonNode> envelopesFor(UUID claimId) {
        return seen.stream()
                .filter(r -> claimId.toString().equals(r.key()))
                .map(r -> parse(r.value()))
                .toList();
    }

    private static JsonNode parse(String value) {
        try {
            return JSON.readTree(value);
        } catch (IOException e) {
            throw new AssertionError("Message on " + TOPIC + " is not valid JSON: " + value, e);
        }
    }

    @Override
    public void close() {
        consumer.close();
    }
}
