package com.example.demo.integration;

import com.example.demo.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for claims-service integration tests.
 *
 * <p>Runs the <b>whole application</b> on a random port against real containers (PostgreSQL + Liquibase,
 * Kafka, Redis, Keycloak with the {@code demo-app} realm imported). Requests go over real HTTP with
 * <b>real Keycloak access tokens</b>, so JWT signature, issuer and role mapping are exercised exactly as in
 * production.
 *
 * <p>All subclasses share this exact configuration, so Spring caches <b>one</b> application context (and one
 * set of containers) for the whole suite. Subclasses must therefore not add their own context configuration
 * (no {@code @MockitoBean}, {@code @TestPropertySource}, etc.).
 *
 * <p>{@code app.events.cdc-enabled=false} selects direct-Kafka publishing (the docker-compose default); in CDC
 * mode events come from Debezium, which is not part of this test setup.
 *
 * <p>Tests never truncate shared tables: each test creates its own data with random IDs and asserts on that
 * data (or on deltas), so classes can run in any order.
 */
@Tag("integration")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.events.cdc-enabled=false")
public abstract class AbstractClaimsIntegrationTest {

    protected static final String ADMIN_EMAIL = "admin@demo.com";
    protected static final String CLAIMANT_EMAIL = "claimant@demo.com";
    protected static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Tokens are valid for 24h in the test realm, so fetch them once per Keycloak instance. */
    private static final Map<String, Map<String, String>> TOKEN_CACHE = new LinkedHashMap<>();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected KeycloakContainer keycloak;

    @Autowired
    protected KafkaContainer kafka;

    protected String adminToken;
    protected String claimantToken;
    protected UUID adminId;
    protected UUID claimantId;

    @BeforeEach
    void authenticateAndSeedUsers() {
        adminToken = userToken(ADMIN_EMAIL, "Admin123!");
        claimantToken = userToken(CLAIMANT_EMAIL, "Claimant123!");
        adminId = subjectOf(adminToken);
        claimantId = subjectOf(claimantToken);

        // In the real stack the BFF provisions these rows at signup. Keycloak generates the user IDs at
        // realm import, so the rows are keyed by the token's "sub" claim — exactly what the controllers use.
        seedUser(adminId, ADMIN_EMAIL, "Admin User", "ADMIN");
        seedUser(claimantId, CLAIMANT_EMAIL, "Claimant User", "CLAIMANT");
    }

    // ------------------------------------------------------------------------------------------------
    // Keycloak tokens
    // ------------------------------------------------------------------------------------------------

    /** Resource-owner password grant through the public UI client, as the login flow does. */
    protected String userToken(String username, String password) {
        return cachedToken("user:" + username, () -> requestToken(realmTokenUrl("demo-app"), Map.of(
                "grant_type", "password",
                "client_id", "demo-app-ui",
                "username", username,
                "password", password)));
    }

    /** Client-credentials token for the BFF's service account (used for user provisioning). */
    protected String bffServiceAccountToken() {
        return cachedToken("client:bff-service", () -> requestToken(realmTokenUrl("demo-app"), Map.of(
                "grant_type", "client_credentials",
                "client_id", "bff-service",
                "client_secret", "63+lunw2gODUVo9ehu7yBJfywfD5HG+/qcywh9i6j+E=")));
    }

    /** A perfectly valid Keycloak token — but from the {@code master} realm, i.e. the wrong issuer. */
    protected String foreignRealmToken() {
        return cachedToken("master:admin", () -> requestToken(realmTokenUrl("master"), Map.of(
                "grant_type", "password",
                "client_id", "admin-cli",
                "username", keycloak.getAdminUsername(),
                "password", keycloak.getAdminPassword())));
    }

    protected static UUID subjectOf(String jwt) {
        try {
            String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
            return UUID.fromString(JSON.readTree(payload).path("sub").asText());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot decode JWT payload", e);
        }
    }

    private String realmTokenUrl(String realm) {
        return keycloak.getAuthServerUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    private String cachedToken(String key, java.util.function.Supplier<String> fetch) {
        synchronized (TOKEN_CACHE) {
            return TOKEN_CACHE
                    .computeIfAbsent(keycloak.getAuthServerUrl(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> fetch.get());
        }
    }

    private static String requestToken(String url, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        ApiResponse response = send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(response.status())
                .as("Keycloak token request failed: %s", response.body())
                .isEqualTo(200);
        return response.json().path("access_token").asText();
    }

    // ------------------------------------------------------------------------------------------------
    // HTTP helpers (real HTTP against the running service)
    // ------------------------------------------------------------------------------------------------

    protected ApiResponse get(String path, String token) {
        return send(request(path, token).GET());
    }

    protected ApiResponse post(String path, String token, String json) {
        return send(request(path, token)
                .header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)));
    }

    protected ApiResponse patch(String path, String token, String json) {
        return send(request(path, token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static ApiResponse send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.body(),
                    response.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted", e);
        }
    }

    protected record ApiResponse(int status, String body, String location) {
        JsonNode json() {
            try {
                return body == null || body.isBlank() ? JSON.missingNode() : JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("Response is not JSON: " + body, e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Domain helpers
    // ------------------------------------------------------------------------------------------------

    protected static String claimRequest(String incidentDate, String location, String description, double amount) {
        return """
                {"incidentDate":"%s","incidentLocation":"%s","description":"%s","claimAmount":%s}
                """.formatted(incidentDate, location, description, amount);
    }

    protected static String validClaimRequest() {
        return claimRequest("2026-02-15", "123 Main St, Springfield, IL",
                "Water damage to basement due to pipe burst", 2500.50);
    }

    /** Creates a claim through the API as the given user and returns its id. */
    protected UUID createClaim(String token) {
        ApiResponse response = post("/api/claims", token, validClaimRequest());
        assertThat(response.status()).as("create claim: %s", response.body()).isEqualTo(201);
        return UUID.fromString(response.json().path("claimId").asText());
    }

    protected ApiResponse updateStatus(UUID claimId, String newStatus, String token) {
        return patch("/api/admin/claims/" + claimId + "/status", token,
                "{\"newStatus\":\"" + newStatus + "\"}");
    }

    protected String dbStatusOf(UUID claimId) {
        return jdbc.queryForObject("SELECT status FROM claims WHERE claim_id = ?", String.class, claimId);
    }

    protected void seedUser(UUID id, String email, String name, String role) {
        jdbc.update("INSERT INTO users (user_id, email, name, role, created_at) "
                + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING", id, email, name, role);
    }

    /** Polls until the condition holds or the timeout elapses (no Awaitility dependency in this module). */
    protected static void eventually(Duration timeout, Runnable assertion) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError last;
        do {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        } while (System.nanoTime() < deadline);
        throw last;
    }

    /** Arbitrary unique values so tests never collide with each other or with seeded data. */
    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@it.example.com";
    }
}
