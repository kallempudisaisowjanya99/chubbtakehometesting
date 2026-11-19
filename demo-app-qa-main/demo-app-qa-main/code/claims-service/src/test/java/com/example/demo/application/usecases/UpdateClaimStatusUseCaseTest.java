package com.example.demo.application.usecases;

import com.example.demo.domain.claim.Claim;
import com.example.demo.domain.claim.ClaimNotFoundException;
import com.example.demo.domain.claim.ClaimRepository;
import com.example.demo.domain.claim.ClaimStatus;
import com.example.demo.domain.claim.InvalidStatusTransitionException;
import com.example.demo.domain.claim.events.ClaimStatusChanged;
import com.example.demo.user.domain.Email;
import com.example.demo.user.domain.UnauthorizedException;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserId;
import com.example.demo.user.domain.UserRepository;
import com.example.demo.user.domain.UserRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UpdateClaimStatusUseCase}.
 *
 * <p>The use case was extended with an {@link ApplicationEventPublisher} dependency and a
 * {@code cdcEnabled} flag; the original test still used the 3-arg constructor and no longer
 * compiled. These tests cover the original orchestration rules plus the event-publishing
 * branches, the metrics counter, and the full status-transition matrix.
 */
class UpdateClaimStatusUseCaseTest {

    private static final String STATUS_CHANGED_METRIC = "claims.status.changed";

    private ClaimRepository claimRepository;
    private UserRepository userRepository;
    private MeterRegistry meterRegistry;
    private ApplicationEventPublisher eventPublisher;
    private UpdateClaimStatusUseCase useCase;

    private static final UUID CLAIM_ID = UUID.randomUUID();
    private static final UUID ADMIN_USER_ID = UUID.randomUUID();
    private static final UUID NON_ADMIN_USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        claimRepository = mock(ClaimRepository.class);
        userRepository = mock(UserRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        eventPublisher = mock(ApplicationEventPublisher.class);
        useCase = new UpdateClaimStatusUseCase(claimRepository, userRepository, meterRegistry, eventPublisher);
        // @Value is not processed outside Spring; default to direct-Kafka mode (the docker-compose default).
        setCdcEnabled(false);
    }

    // ---------------------------------------------------------------------------------------------
    // Original orchestration tests (kept, now compiling against the 4-arg constructor)
    // ---------------------------------------------------------------------------------------------

    // TC-031: Use case orchestration logic tested with mocked repositories
    @Test
    void should_UpdateClaimStatus_When_AdminUserAndValidTransition() {
        Claim claim = givenAdminAndClaim(ClaimStatus.SUBMITTED);

        Claim result = useCase.execute(command(ClaimStatus.UNDER_REVIEW));

        assertNotNull(result);
        assertEquals(ClaimStatus.UNDER_REVIEW, result.getStatus());
        verify(claimRepository).save(claim);
    }

    // TC-058: Non-admin user cannot update status (throws UnauthorizedException)
    @Test
    void should_ThrowUnauthorizedException_When_UserNotAdmin() {
        when(userRepository.findById(UserId.of(NON_ADMIN_USER_ID)))
            .thenReturn(Optional.of(createClaimantUser(NON_ADMIN_USER_ID)));

        var command = new UpdateClaimStatusCommand(CLAIM_ID, ClaimStatus.UNDER_REVIEW, NON_ADMIN_USER_ID);

        assertThrows(UnauthorizedException.class, () -> useCase.execute(command));
        verify(claimRepository, never()).findById(any());
        assertNoSideEffects();
    }

    // TC-059: Claim not found throws ClaimNotFoundException
    @Test
    void should_ThrowClaimNotFoundException_When_ClaimNotFound() {
        when(userRepository.findById(UserId.of(ADMIN_USER_ID)))
            .thenReturn(Optional.of(createAdminUser(ADMIN_USER_ID)));
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        assertThrows(ClaimNotFoundException.class, () -> useCase.execute(command(ClaimStatus.UNDER_REVIEW)));
        assertNoSideEffects();
    }

    @Test
    void should_ThrowInvalidStatusTransitionException_When_TransitionInvalid() {
        givenAdminAndClaim(ClaimStatus.CLOSED);

        assertThrows(InvalidStatusTransitionException.class, () -> useCase.execute(command(ClaimStatus.APPROVED)));
        assertNoSideEffects();
    }

    @Test
    void should_ThrowUnauthorizedException_When_UserNotFound() {
        when(userRepository.findById(UserId.of(ADMIN_USER_ID))).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class, () -> useCase.execute(command(ClaimStatus.UNDER_REVIEW)));
        verify(claimRepository, never()).findById(any());
        assertNoSideEffects();
    }

    // ---------------------------------------------------------------------------------------------
    // Event publishing — the behaviour introduced with the ApplicationEventPublisher dependency
    // ---------------------------------------------------------------------------------------------

    @Nested
    class WhenCdcDisabled {

        @Test
        void should_PublishStatusChangedEvent_When_StatusUpdated() {
            givenAdminAndClaim(ClaimStatus.SUBMITTED);

            useCase.execute(command(ClaimStatus.UNDER_REVIEW));

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(1)).publishEvent(captor.capture());

            ClaimStatusChanged event = assertInstanceOf(ClaimStatusChanged.class, captor.getValue());
            assertEquals(CLAIM_ID, event.claimId());
            assertEquals(ClaimStatus.SUBMITTED, event.oldStatus());
            assertEquals(ClaimStatus.UNDER_REVIEW, event.newStatus());
            assertNotNull(event.eventId());
            assertNotNull(event.occurredAt());
        }

        @Test
        void should_ClearDomainEvents_When_EventsPublished() {
            Claim claim = givenAdminAndClaim(ClaimStatus.SUBMITTED);

            useCase.execute(command(ClaimStatus.UNDER_REVIEW));

            assertTrue(claim.getDomainEvents().isEmpty(),
                "events must be cleared after direct publish to avoid double publishing");
        }

        @Test
        void should_PublishAfterSave_When_StatusUpdated() {
            givenAdminAndClaim(ClaimStatus.SUBMITTED);

            useCase.execute(command(ClaimStatus.UNDER_REVIEW));

            var inOrder = inOrder(claimRepository, eventPublisher);
            inOrder.verify(claimRepository).save(any(Claim.class));
            inOrder.verify(eventPublisher).publishEvent(any(Object.class));
        }
    }

    @Nested
    class WhenCdcEnabled {

        @BeforeEach
        void enableCdc() {
            setCdcEnabled(true);
        }

        @Test
        void should_NotPublishEvents_When_CdcEnabled() {
            givenAdminAndClaim(ClaimStatus.SUBMITTED);

            Claim result = useCase.execute(command(ClaimStatus.UNDER_REVIEW));

            assertEquals(ClaimStatus.UNDER_REVIEW, result.getStatus());
            verify(claimRepository).save(any(Claim.class));
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void should_RetainDomainEvents_When_CdcEnabled() {
            Claim claim = givenAdminAndClaim(ClaimStatus.SUBMITTED);

            useCase.execute(command(ClaimStatus.UNDER_REVIEW));

            // Debezium publishes from the WAL; the use case leaves the in-memory events untouched.
            assertEquals(1, claim.getDomainEvents().size());
        }

        @Test
        void should_StillIncrementMetric_When_CdcEnabled() {
            givenAdminAndClaim(ClaimStatus.SUBMITTED);

            useCase.execute(command(ClaimStatus.UNDER_REVIEW));

            assertEquals(1.0, statusChangedCounter(ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW).count());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Metrics
    // ---------------------------------------------------------------------------------------------

    @Test
    void should_IncrementStatusChangedCounter_WithTags_When_StatusUpdated() {
        givenAdminAndClaim(ClaimStatus.UNDER_REVIEW);

        useCase.execute(command(ClaimStatus.APPROVED));

        Counter counter = statusChangedCounter(ClaimStatus.UNDER_REVIEW, ClaimStatus.APPROVED);
        assertNotNull(counter, "counter with oldStatus/newStatus/adminUserId tags should be registered");
        assertEquals(1.0, counter.count());
    }

    @Test
    void should_NotRegisterCounter_When_UpdateFails() {
        givenAdminAndClaim(ClaimStatus.CLOSED);

        assertThrows(InvalidStatusTransitionException.class, () -> useCase.execute(command(ClaimStatus.SUBMITTED)));

        assertNull(meterRegistry.find(STATUS_CHANGED_METRIC).counter());
    }

    // ---------------------------------------------------------------------------------------------
    // Full transition matrix (business rules from ClaimStatus#canTransitionTo)
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
        "SUBMITTED,    UNDER_REVIEW",
        "SUBMITTED,    REJECTED",
        "UNDER_REVIEW, APPROVED",
        "UNDER_REVIEW, REJECTED",
        "UNDER_REVIEW, SUBMITTED",
        "APPROVED,     CLOSED",
        "REJECTED,     CLOSED"
    })
    void should_UpdateStatus_When_TransitionAllowed(ClaimStatus from, ClaimStatus to) {
        givenAdminAndClaim(from);

        Claim result = useCase.execute(command(to));

        assertEquals(to, result.getStatus());
        verify(claimRepository).save(any(Claim.class));
        verify(eventPublisher).publishEvent(any(ClaimStatusChanged.class));
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("disallowedTransitions")
    void should_RejectTransition_When_TransitionNotAllowed(ClaimStatus from, ClaimStatus to) {
        givenAdminAndClaim(from);

        assertThrows(InvalidStatusTransitionException.class, () -> useCase.execute(command(to)));
        assertNoSideEffects();
    }

    /** Every (from, to) pair, including same-status, that is NOT in the allowed list above. */
    static Stream<Arguments> disallowedTransitions() {
        return Arrays.stream(ClaimStatus.values())
            .flatMap(from -> Arrays.stream(ClaimStatus.values())
                .filter(to -> !from.canTransitionTo(to))
                .map(to -> Arguments.of(from, to)));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void setCdcEnabled(boolean enabled) {
        ReflectionTestUtils.setField(useCase, "cdcEnabled", enabled);
    }

    private Claim givenAdminAndClaim(ClaimStatus currentStatus) {
        Claim claim = createClaim(currentStatus);
        when(userRepository.findById(UserId.of(ADMIN_USER_ID)))
            .thenReturn(Optional.of(createAdminUser(ADMIN_USER_ID)));
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
        return claim;
    }

    private UpdateClaimStatusCommand command(ClaimStatus newStatus) {
        return new UpdateClaimStatusCommand(CLAIM_ID, newStatus, ADMIN_USER_ID);
    }

    private void assertNoSideEffects() {
        verify(claimRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
        assertNull(meterRegistry.find(STATUS_CHANGED_METRIC).counter(), "no metric on failed update");
    }

    private Counter statusChangedCounter(ClaimStatus oldStatus, ClaimStatus newStatus) {
        return meterRegistry.find(STATUS_CHANGED_METRIC)
            .tag("oldStatus", oldStatus.name())
            .tag("newStatus", newStatus.name())
            .tag("adminUserId", ADMIN_USER_ID.toString())
            .counter();
    }

    private User createAdminUser(UUID userId) {
        return new User(UserId.of(userId), "Admin User", Email.of("admin@test.com"), UserRole.ADMIN, Instant.now());
    }

    private User createClaimantUser(UUID userId) {
        return new User(UserId.of(userId), "Claimant User", Email.of("claimant@test.com"), UserRole.CLAIMANT, Instant.now());
    }

    private Claim createClaim(ClaimStatus status) {
        return Claim.reconstitute(
            CLAIM_ID,
            UUID.randomUUID(),
            LocalDate.now().minusDays(1),
            "123 Main Street, City",
            "Test claim description for unit testing",
            new BigDecimal("5000.00"),
            status,
            Instant.now(),
            Instant.now()
        );
    }
}
