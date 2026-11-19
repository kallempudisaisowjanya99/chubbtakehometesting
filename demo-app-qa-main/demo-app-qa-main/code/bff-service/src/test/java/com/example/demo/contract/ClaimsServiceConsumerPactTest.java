package com.example.demo.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.example.bff.adapter.in.web.generated.model.ClaimStatusDTO;
import com.example.bff.adapter.in.web.generated.model.UpdateClaimStatusRequestDTO;
import com.example.bff.adapter.out.claims.ApiClient;
import com.example.demo.adapter.in.web.AdminClaimResponseMapperImpl;
import com.example.demo.adapter.in.web.AdminController;
import com.example.demo.adapter.out.claimsservice.ClaimsServiceAdapter;
import com.example.demo.application.auth.ServiceAccountTokenProvider;
import com.example.demo.config.JacksonConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer side of the BFF → claims-service contract.
 *
 * <p>Each test drives the BFF's real outbound code ({@link ClaimsServiceAdapter} and {@link AdminController},
 * with the OpenAPI-generated client) against a Pact mock server. Only what the BFF actually sends and reads is
 * in the contract: the status codes it branches on and the response fields it maps. Error bodies are left out
 * except where the BFF parses them (the 409 on user creation).
 *
 * <p>The pact is written to {@code bff-service/pacts/} and verified by {@code ClaimsServiceProviderPactTest}
 * in claims-service.
 *
 * <p><b>Bearer tokens</b> are placeholders that name the caller: {@value #CLAIMANT_TOKEN},
 * {@value #ADMIN_TOKEN} and {@value #SERVICE_TOKEN}. The provider test swaps each one for a real Keycloak
 * token for that caller. <b>IDs</b> the provider generates (claim ids, the claimant's Keycloak id) come from
 * provider states, so the pact does not depend on fixed database rows.
 */
@Tag("contract")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = ClaimsServiceConsumerPactTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class ClaimsServiceConsumerPactTest {

    static final String PROVIDER = "claims-service";
    static final String CONSUMER = "bff-service";

    static final String CLAIMANT_TOKEN = "claimant-token";
    static final String ADMIN_TOKEN = "admin-token";
    static final String SERVICE_TOKEN = "service-account-token";

    // Provider states. claims-service implements one handler per name.
    static final String CLAIMANT_HAS_A_CLAIM = "the claimant has a submitted claim";
    static final String OTHER_USERS_CLAIM = "a claim owned by another user exists";
    static final String NO_SUCH_CLAIM = "no claim exists with the requested id";
    static final String USER_EXISTS = "a user exists";
    static final String NO_SUCH_USER = "no user exists with the given id and email";
    static final String USERS_EXIST = "the admin and the claimant are registered";

    // Example values. Provider-state expressions replace the ids at verification time.
    static final String EXAMPLE_CLAIM_ID = "660e8400-e29b-41d4-a716-446655440001";
    static final String EXAMPLE_USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    static final String UNKNOWN_CLAIM_ID = "00000000-0000-4000-8000-00000000dead";
    static final UUID NEW_USER_ID = UUID.fromString("7a1c4f00-0000-4000-8000-000000c0ffee");
    static final String NEW_USER_EMAIL = "pact.new.user@example.com";

    private static final String ISO_DATE_TIME =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$";
    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    private static final String CLAIM_STATUS = "SUBMITTED|UNDER_REVIEW|APPROVED|REJECTED|CLOSED";

    private static ServiceAccountTokenProvider serviceToken() {
        return new ServiceAccountTokenProvider() {
            @Override
            public String getServiceAccountToken() {
                return SERVICE_TOKEN;
            }

            @Override
            public String forceRefreshToken() {
                return SERVICE_TOKEN;
            }
        };
    }

    private static ClaimsServiceAdapter adapter(MockServer mockServer) {
        var apiClient = new ApiClient();
        apiClient.setBasePath(mockServer.getUrl());
        return new ClaimsServiceAdapter(apiClient, serviceToken(), new JacksonConfig().objectMapper());
    }

    private static AdminController adminController(MockServer mockServer) {
        return new AdminController(mockServer.getUrl(), serviceToken(), new AdminClaimResponseMapperImpl());
    }

    /** AdminController forwards the logged-in admin's JWT for some calls; it reads it from the security context. */
    private static void loggedInAsAdmin() {
        var jwt = Jwt.withTokenValue(ADMIN_TOKEN)
                .header("alg", "none")
                .subject(EXAMPLE_USER_ID)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------------
    // Response bodies, written as the BFF reads them
    // ---------------------------------------------------------------------------------------------

    /** ClaimResponse with the claim id taken from the provider state. */
    private static PactDslJsonBody claimBody(String status) {
        return claimFields(new PactDslJsonBody()
                .valueFromProviderState("claimId", "${claimId}", EXAMPLE_CLAIM_ID)
                .stringValue("status", status));
    }

    private static PactDslJsonBody claimFields(PactDslJsonBody body) {
        return body
                .uuid("userId", UUID.fromString(EXAMPLE_USER_ID))
                .date("incidentDate", "yyyy-MM-dd")
                .stringType("incidentLocation", "123 Main St, Springfield, IL")
                .stringType("description", "Water damage to basement due to pipe burst")
                .numberType("claimAmount", 2500.5)
                .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z")
                .stringMatcher("updatedAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z");
    }

    private static PactDslJsonBody userBody(String role) {
        return new PactDslJsonBody()
                .uuid("userId", UUID.fromString(EXAMPLE_USER_ID))
                .stringType("email", "jane.doe@example.com")
                .stringType("name", "Jane Doe")
                .stringValue("role", role)
                .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z");
    }

    // =============================================================================================
    // Claimant flows: the BFF forwards the claimant's own token
    // =============================================================================================

    @Pact(consumer = CONSUMER)
    RequestResponsePact submitClaim(PactDslWithProvider builder) {
        return builder
                .given(USERS_EXIST)
                .uponReceiving("a claimant submits a claim")
                .method("POST")
                .path("/api/claims")
                .headers("Authorization", "Bearer " + CLAIMANT_TOKEN)
                .body(new PactDslJsonBody()
                        .stringValue("incidentDate", "2026-02-15")
                        .stringValue("incidentLocation", "123 Main St, Springfield, IL")
                        .stringValue("description", "Water damage to basement due to pipe burst")
                        .numberValue("claimAmount", 2500.5))
                .willRespondWith()
                .status(201)
                .body(claimFields(new PactDslJsonBody()
                        .uuid("claimId", UUID.fromString(EXAMPLE_CLAIM_ID))
                        .stringValue("status", "SUBMITTED")))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "submitClaim")
    void submitClaim(MockServer mockServer) {
        var claim = adapter(mockServer).submitClaim(CLAIMANT_TOKEN, LocalDate.of(2026, 2, 15),
                "123 Main St, Springfield, IL", "Water damage to basement due to pipe burst",
                new BigDecimal("2500.5"));

        assertThat(claim.claimId()).isEqualTo(UUID.fromString(EXAMPLE_CLAIM_ID));
        assertThat(claim.status()).isEqualTo("SUBMITTED");
        assertThat(claim.claimAmount()).isEqualByComparingTo("2500.5");
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact listOwnClaims(PactDslWithProvider builder) {
        DslPart summaries = PactDslJsonArray.arrayMinLike(1)
                .uuid("claimId", UUID.fromString(EXAMPLE_CLAIM_ID))
                .date("incidentDate", "yyyy-MM-dd")
                .stringType("description", "Water damage to basement due to pipe burst")
                .numberType("claimAmount", 2500.5)
                .stringMatcher("status", CLAIM_STATUS, "SUBMITTED")
                .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z")
                .closeObject();
        return builder
                .given(CLAIMANT_HAS_A_CLAIM)
                .uponReceiving("a claimant lists their claims")
                .method("GET")
                .path("/api/claims")
                .headers("Authorization", "Bearer " + CLAIMANT_TOKEN)
                .willRespondWith()
                .status(200)
                .body(summaries)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "listOwnClaims")
    void listOwnClaims(MockServer mockServer) {
        var claims = adapter(mockServer).listClaims(CLAIMANT_TOKEN, UUID.fromString(EXAMPLE_USER_ID));

        assertThat(claims).isNotEmpty();
        assertThat(claims.getFirst().status()).isEqualTo("SUBMITTED");
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact getOwnClaim(PactDslWithProvider builder) {
        return builder
                .given(CLAIMANT_HAS_A_CLAIM)
                .uponReceiving("a claimant views one of their claims")
                .method("GET")
                .pathFromProviderState("/api/claims/${claimId}", "/api/claims/" + EXAMPLE_CLAIM_ID)
                .headers("Authorization", "Bearer " + CLAIMANT_TOKEN)
                .willRespondWith()
                .status(200)
                .body(claimBody("SUBMITTED"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getOwnClaim")
    void getOwnClaim(MockServer mockServer) {
        var claim = adapter(mockServer).getClaim(CLAIMANT_TOKEN, UUID.fromString(EXAMPLE_CLAIM_ID));

        assertThat(claim.claimId()).isEqualTo(UUID.fromString(EXAMPLE_CLAIM_ID));
        assertThat(claim.incidentLocation()).isNotBlank();
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact getSomeoneElsesClaim(PactDslWithProvider builder) {
        return builder
                .given(OTHER_USERS_CLAIM)
                .uponReceiving("a claimant asks for a claim they do not own")
                .method("GET")
                .pathFromProviderState("/api/claims/${claimId}", "/api/claims/" + EXAMPLE_CLAIM_ID)
                .headers("Authorization", "Bearer " + CLAIMANT_TOKEN)
                .willRespondWith()
                .status(403)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getSomeoneElsesClaim")
    void getSomeoneElsesClaim(MockServer mockServer) {
        assertThatThrownBy(() -> adapter(mockServer).getClaim(CLAIMANT_TOKEN, UUID.fromString(EXAMPLE_CLAIM_ID)))
                .hasMessageStartingWith("Forbidden");
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact getUnknownClaim(PactDslWithProvider builder) {
        return builder
                .given(NO_SUCH_CLAIM)
                .uponReceiving("a claimant asks for a claim that does not exist")
                .method("GET")
                .path("/api/claims/" + UNKNOWN_CLAIM_ID)
                .headers("Authorization", "Bearer " + CLAIMANT_TOKEN)
                .willRespondWith()
                .status(404)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getUnknownClaim")
    void getUnknownClaim(MockServer mockServer) {
        assertThatThrownBy(() -> adapter(mockServer).getClaim(CLAIMANT_TOKEN, UUID.fromString(UNKNOWN_CLAIM_ID)))
                .hasMessageStartingWith("Claim not found");
    }

    // =============================================================================================
    // User provisioning: the BFF calls with its own service-account token
    // =============================================================================================

    @Pact(consumer = CONSUMER)
    RequestResponsePact createNewUser(PactDslWithProvider builder) {
        return builder
                .given(NO_SUCH_USER, Map.of("userId", NEW_USER_ID.toString(), "email", NEW_USER_EMAIL))
                .uponReceiving("the BFF provisions a new user")
                .method("POST")
                .path("/api/users")
                .headers("Authorization", "Bearer " + SERVICE_TOKEN)
                .body(new PactDslJsonBody()
                        .stringValue("userId", NEW_USER_ID.toString())
                        .stringValue("email", NEW_USER_EMAIL)
                        .stringValue("name", "Pact New User")
                        .stringValue("role", "CLAIMANT"))
                .willRespondWith()
                .status(201)
                .body(new PactDslJsonBody()
                        .stringValue("userId", NEW_USER_ID.toString())
                        .stringValue("email", NEW_USER_EMAIL)
                        .stringValue("name", "Pact New User")
                        .stringValue("role", "CLAIMANT")
                        .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "createNewUser")
    void createNewUser(MockServer mockServer) {
        var user = adapter(mockServer).createUser(NEW_USER_ID, NEW_USER_EMAIL, "Pact New User", "CLAIMANT");

        assertThat(user.userId()).isEqualTo(NEW_USER_ID);
        assertThat(user.role()).isEqualTo("CLAIMANT");
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact createExistingUser(PactDslWithProvider builder) {
        return builder
                .given(USER_EXISTS, Map.of("userId", NEW_USER_ID.toString(), "email", NEW_USER_EMAIL))
                .uponReceiving("the BFF provisions a user that already exists")
                .method("POST")
                .path("/api/users")
                .headers("Authorization", "Bearer " + SERVICE_TOKEN)
                .body(new PactDslJsonBody()
                        .stringValue("userId", NEW_USER_ID.toString())
                        .stringValue("email", NEW_USER_EMAIL)
                        .stringValue("name", "Pact New User")
                        .stringValue("role", "CLAIMANT"))
                .willRespondWith()
                .status(409)
                // The BFF parses this body to log the user in with the existing record.
                .body(new PactDslJsonBody()
                        .stringValue("userId", NEW_USER_ID.toString())
                        .stringValue("email", NEW_USER_EMAIL)
                        .stringType("name", "Pact New User")
                        .stringValue("role", "CLAIMANT")
                        .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "createExistingUser")
    void createExistingUser(MockServer mockServer) {
        var user = adapter(mockServer).createUser(NEW_USER_ID, NEW_USER_EMAIL, "Pact New User", "CLAIMANT");

        assertThat(user.userId()).isEqualTo(NEW_USER_ID);
        assertThat(user.email()).isEqualTo(NEW_USER_EMAIL);
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact getUser(PactDslWithProvider builder) {
        return builder
                .given(USER_EXISTS, Map.of("userId", NEW_USER_ID.toString(), "email", NEW_USER_EMAIL))
                .uponReceiving("the BFF looks up a user by id")
                .method("GET")
                .path("/api/users/" + NEW_USER_ID)
                .headers("Authorization", "Bearer " + SERVICE_TOKEN)
                .willRespondWith()
                .status(200)
                .body(new PactDslJsonBody()
                        .stringValue("userId", NEW_USER_ID.toString())
                        .stringValue("email", NEW_USER_EMAIL)
                        .stringType("name", "Pact New User")
                        .stringMatcher("role", "CLAIMANT|ADMIN", "CLAIMANT")
                        .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getUser")
    void getUser(MockServer mockServer) {
        var user = adapter(mockServer).getUser(NEW_USER_ID);

        assertThat(user.email()).isEqualTo(NEW_USER_EMAIL);
    }

    // =============================================================================================
    // Admin flows
    // =============================================================================================

    @Pact(consumer = CONSUMER)
    RequestResponsePact dashboardStats(PactDslWithProvider builder) {
        return builder
                .given(CLAIMANT_HAS_A_CLAIM)
                .uponReceiving("the BFF fetches dashboard stats, bypassing the cache")
                .method("GET")
                .path("/api/admin/dashboard/stats")
                .query("bypassCache=true")
                .headers("Authorization", "Bearer " + SERVICE_TOKEN)
                .willRespondWith()
                .status(200)
                .body(new PactDslJsonBody()
                        .integerType("totalUsers", 2)
                        .integerType("totalClaims", 1)
                        .booleanType("cacheHit", false)
                        .integerType("queryTimeMs", 12)
                        // Only statuses that have claims are present, so match keys by example.
                        .object("claimsByStatus")
                            .eachKeyLike("SUBMITTED", PactDslJsonRootValue.integerType(1))
                            .closeObject())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "dashboardStats")
    void dashboardStats(MockServer mockServer) {
        var response = adminController(mockServer).getDashboardStats(Optional.of(true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getClaimsByStatus()).containsKey("SUBMITTED");
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact flushCache(PactDslWithProvider builder) {
        return builder
                .uponReceiving("the BFF flushes the dashboard cache")
                .method("POST")
                .path("/api/admin/dashboard/flush-cache")
                .headers("Authorization", "Bearer " + SERVICE_TOKEN)
                .willRespondWith()
                .status(204)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "flushCache")
    void flushCache(MockServer mockServer) {
        var response = adminController(mockServer).flushDashboardCache();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact listUsers(PactDslWithProvider builder) {
        DslPart users = PactDslJsonArray.arrayMinLike(1)
                .uuid("userId", UUID.fromString(EXAMPLE_USER_ID))
                .stringType("email", "jane.doe@example.com")
                .stringType("name", "Jane Doe")
                .stringMatcher("role", "CLAIMANT|ADMIN", "CLAIMANT")
                .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z")
                .closeObject();
        return builder
                .given(USERS_EXIST)
                .uponReceiving("an admin lists all users")
                .method("GET")
                .path("/api/users")
                .headers("Authorization", "Bearer " + ADMIN_TOKEN)
                .willRespondWith()
                .status(200)
                .body(users)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "listUsers")
    void listUsers(MockServer mockServer) {
        loggedInAsAdmin();

        var response = adminController(mockServer).listAllUsers();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact listClaimsFiltered(PactDslWithProvider builder) {
        DslPart claims = PactDslJsonArray.arrayMinLike(1)
                .uuid("claimId", UUID.fromString(EXAMPLE_CLAIM_ID))
                .valueFromProviderState("userId", "${userId}", EXAMPLE_USER_ID)
                .stringType("userName", "Jane Doe")
                .stringType("userEmail", "jane.doe@example.com")
                .date("incidentDate", "yyyy-MM-dd")
                .stringType("incidentLocation", "123 Main St, Springfield, IL")
                .stringType("description", "Water damage to basement due to pipe burst")
                .numberType("claimAmount", 2500.5)
                .stringValue("status", "SUBMITTED")
                .stringMatcher("createdAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z")
                .stringMatcher("updatedAt", ISO_DATE_TIME, "2026-02-17T10:30:00Z")
                .closeObject();
        return builder
                .given(CLAIMANT_HAS_A_CLAIM)
                .uponReceiving("an admin lists one user's submitted claims")
                .method("GET")
                .path("/api/admin/claims")
                .query("status=SUBMITTED")
                .queryParameterFromProviderState("userId", "${userId}", EXAMPLE_USER_ID)
                .headers("Authorization", "Bearer " + SERVICE_TOKEN)
                .willRespondWith()
                .status(200)
                .body(claims)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "listClaimsFiltered")
    void listClaimsFiltered(MockServer mockServer) {
        var response = adminController(mockServer).listAllClaims(
                Optional.of(ClaimStatusDTO.SUBMITTED), Optional.of(UUID.fromString(EXAMPLE_USER_ID)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty()
                .allSatisfy(claim -> assertThat(claim.getStatus()).isEqualTo(ClaimStatusDTO.SUBMITTED));
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact moveClaimToReview(PactDslWithProvider builder) {
        return builder
                .given(CLAIMANT_HAS_A_CLAIM)
                .uponReceiving("an admin moves a submitted claim to UNDER_REVIEW")
                .method("PATCH")
                .pathFromProviderState("/api/admin/claims/${claimId}/status",
                        "/api/admin/claims/" + EXAMPLE_CLAIM_ID + "/status")
                .headers("Authorization", "Bearer " + ADMIN_TOKEN)
                .body(new PactDslJsonBody().stringValue("newStatus", "UNDER_REVIEW"))
                .willRespondWith()
                .status(200)
                .body(claimBody("UNDER_REVIEW"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "moveClaimToReview")
    void moveClaimToReview(MockServer mockServer) {
        loggedInAsAdmin();

        var response = adminController(mockServer).updateClaimStatus(UUID.fromString(EXAMPLE_CLAIM_ID),
                new UpdateClaimStatusRequestDTO().newStatus(ClaimStatusDTO.UNDER_REVIEW));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo(ClaimStatusDTO.UNDER_REVIEW);
    }

    /** The BFF turns a 400 into a 400 for the UI; any other error becomes a 500. */
    @Pact(consumer = CONSUMER)
    RequestResponsePact invalidTransition(PactDslWithProvider builder) {
        return builder
                .given(CLAIMANT_HAS_A_CLAIM)
                .uponReceiving("an admin tries a transition that is not allowed (SUBMITTED to CLOSED)")
                .method("PATCH")
                .pathFromProviderState("/api/admin/claims/${claimId}/status",
                        "/api/admin/claims/" + EXAMPLE_CLAIM_ID + "/status")
                .headers("Authorization", "Bearer " + ADMIN_TOKEN)
                .body(new PactDslJsonBody().stringValue("newStatus", "CLOSED"))
                .willRespondWith()
                .status(400)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "invalidTransition")
    void invalidTransition(MockServer mockServer) {
        loggedInAsAdmin();

        var response = adminController(mockServer).updateClaimStatus(UUID.fromString(EXAMPLE_CLAIM_ID),
                new UpdateClaimStatusRequestDTO().newStatus(ClaimStatusDTO.CLOSED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact updateUnknownClaim(PactDslWithProvider builder) {
        return builder
                .given(NO_SUCH_CLAIM)
                .uponReceiving("an admin updates a claim that does not exist")
                .method("PATCH")
                .path("/api/admin/claims/" + UNKNOWN_CLAIM_ID + "/status")
                .headers("Authorization", "Bearer " + ADMIN_TOKEN)
                .body(new PactDslJsonBody().stringValue("newStatus", "UNDER_REVIEW"))
                .willRespondWith()
                .status(404)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "updateUnknownClaim")
    void updateUnknownClaim(MockServer mockServer) {
        loggedInAsAdmin();

        var response = adminController(mockServer).updateClaimStatus(UUID.fromString(UNKNOWN_CLAIM_ID),
                new UpdateClaimStatusRequestDTO().newStatus(ClaimStatusDTO.UNDER_REVIEW));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
