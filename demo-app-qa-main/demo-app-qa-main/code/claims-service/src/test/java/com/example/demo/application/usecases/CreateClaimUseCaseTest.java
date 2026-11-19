package com.example.demo.application.usecases;

import com.example.demo.application.exceptions.UserNotFoundException;
import com.example.demo.domain.claim.Claim;
import com.example.demo.domain.claim.ClaimRepository;
import com.example.demo.domain.claim.ClaimStatus;
import com.example.demo.domain.claim.events.ClaimSubmitted;
import com.example.demo.fixtures.ClaimTestBuilder;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserId;
import com.example.demo.user.domain.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateClaimUseCaseTest {

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter mockCounter;

    // Added with the CDC/direct-publish feature; without it @InjectMocks passes null -> NPE.
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CreateClaimUseCase useCase;

    @BeforeEach
    void setUp() {
        // Stub MeterRegistry to return a mock Counter (lenient to avoid UnnecessaryStubbingException)
        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mockCounter);
        // @Value is not processed outside Spring; default to direct-Kafka mode (the docker-compose default).
        ReflectionTestUtils.setField(useCase, "cdcEnabled", false);
    }

    @Test
    void should_CreateClaim_When_ValidCommandProvided() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(UserId.of(userId))).thenReturn(Optional.of(mock(User.class)));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateClaimCommand command = ClaimTestBuilder.aClaim()
                .withUserId(userId)
                .withIncidentLocation("123 Main St, Springfield")
                .withDescription("Vehicle accident on highway during heavy rain")
                .toCommand();

        Claim result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(result.getClaimAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.getIncidentLocation()).isEqualTo("123 Main St, Springfield");
        assertThat(result.getDescription()).isEqualTo("Vehicle accident on highway during heavy rain");

        verify(userRepository).findById(UserId.of(userId));
        verify(claimRepository).save(any(Claim.class));
    }

    @Test
    void should_ThrowUserNotFoundException_When_UserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(UserId.of(userId))).thenReturn(Optional.empty());

        CreateClaimCommand command = ClaimTestBuilder.aClaim()
                .withUserId(userId)
                .withClaimAmount(new BigDecimal("1000.00"))
                .toCommand();

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(userId.toString());

        verify(userRepository).findById(UserId.of(userId));
        verify(claimRepository, never()).save(any());
    }

    @Test
    void should_PublishClaimSubmittedEvent_When_ClaimCreated() {
        UUID userId = givenUserExistsAndSaveEchoes();

        Claim result = useCase.execute(ClaimTestBuilder.aClaim()
                .withUserId(userId)
                .withIncidentLocation("456 Oak Avenue, Chicago")
                .withClaimAmount(new BigDecimal("2000.00"))
                .toCommand());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        ClaimSubmitted event = (ClaimSubmitted) captor.getValue();
        assertThat(event.claimId()).isEqualTo(result.getClaimId());
        assertThat(event.userId()).isEqualTo(userId);

        // Events are cleared after direct publish so they are not published twice.
        assertThat(result.getDomainEvents()).isEmpty();
    }

    @Test
    void should_PublishAfterSave_When_ClaimCreated() {
        UUID userId = givenUserExistsAndSaveEchoes();

        useCase.execute(ClaimTestBuilder.aClaim().withUserId(userId).toCommand());

        InOrder inOrder = inOrder(claimRepository, eventPublisher);
        inOrder.verify(claimRepository).save(any(Claim.class));
        inOrder.verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void should_NotPublishButRetainEvent_When_CdcEnabled() {
        ReflectionTestUtils.setField(useCase, "cdcEnabled", true);
        UUID userId = givenUserExistsAndSaveEchoes();

        Claim result = useCase.execute(ClaimTestBuilder.aClaim().withUserId(userId).toCommand());

        // Debezium publishes from the DB WAL; the use case must not also publish.
        verifyNoInteractions(eventPublisher);
        assertThat(result.getDomainEvents())
                .singleElement()
                .isInstanceOf(ClaimSubmitted.class);
    }

    @Test
    void should_IncrementSubmittedCounter_WithTags_When_ClaimCreated() {
        UUID userId = givenUserExistsAndSaveEchoes();

        useCase.execute(ClaimTestBuilder.aClaim().withUserId(userId).toCommand());

        verify(meterRegistry).counter("claims.submitted", "status", "SUBMITTED", "userId", userId.toString());
        verify(mockCounter).increment();
    }

    @Test
    void should_NotPublishOrCount_When_UserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(UserId.of(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(ClaimTestBuilder.aClaim().withUserId(userId).toCommand()))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(eventPublisher, mockCounter);
    }

    @Test
    void should_SetStatusToSubmitted_When_ClaimCreated() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(UserId.of(userId))).thenReturn(Optional.of(mock(User.class)));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateClaimCommand command = ClaimTestBuilder.aClaim()
                .withUserId(userId)
                .withIncidentLocation("789 Elm Street, Boston")
                .withDescription("Fire damage to kitchen from electrical fault")
                .withClaimAmount(new BigDecimal("15000.00"))
                .toCommand();

        Claim result = useCase.execute(command);

        assertThat(result.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    private UUID givenUserExistsAndSaveEchoes() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(UserId.of(userId))).thenReturn(Optional.of(mock(User.class)));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return userId;
    }
}
