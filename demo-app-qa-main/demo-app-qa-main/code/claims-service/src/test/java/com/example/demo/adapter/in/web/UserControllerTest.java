package com.example.demo.adapter.in.web;

import com.example.claims.adapter.in.web.generated.model.CreateUserRequestDTO;
import com.example.claims.adapter.in.web.generated.model.UserRoleDTO;
import com.example.demo.fixtures.UserTestBuilder;
import com.example.demo.user.domain.Email;
import com.example.demo.user.domain.UserId;
import com.example.demo.user.adapter.out.persistence.UserJpaEntity;
import com.example.demo.user.adapter.out.persistence.UserJpaRepository;
import com.example.demo.user.domain.UserRepository;
import com.example.demo.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    private UserRepository userRepository;
    private UserJpaRepository userJpaRepository;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userJpaRepository = mock(UserJpaRepository.class);
        controller = new UserController(userRepository, userJpaRepository);
    }

    @Test
    void should_ReturnAllUsers_When_ListAllUsers() {
        var user1 = UserTestBuilder.aUser().withName("Alice").build();
        var user2 = UserTestBuilder.aUser().withName("Bob").asAdmin().build();
        when(userRepository.findAll()).thenReturn(List.of(user1, user2));

        var response = controller.listAllUsers();

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).getName()).isEqualTo("Alice");
        assertThat(response.getBody().get(1).getName()).isEqualTo("Bob");
    }

    @Test
    void should_CreateUserAndReturn201_When_ValidRequest() {
        UUID userId = UUID.randomUUID();
        var request = new CreateUserRequestDTO();
        request.setUserId(userId);
        request.setName("Jane Doe");
        request.setEmail("jane@example.com");
        request.setRole(UserRoleDTO.CLAIMANT);

        var savedUser = UserTestBuilder.aUser()
                .withUserId(userId)
                .withName("Jane Doe")
                .withEmail("jane@example.com")
                .withRole(UserRole.CLAIMANT)
                .build();
        when(userRepository.save(any())).thenReturn(savedUser);

        var response = controller.createUser(request);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUserId()).isEqualTo(userId);
        assertThat(response.getBody().getName()).isEqualTo("Jane Doe");
        assertThat(response.getBody().getEmail()).isEqualTo("jane@example.com");
        verify(userRepository).save(any());
    }

    @Test
    void should_ReturnUser_When_GetUserFound() {
        UUID userId = UUID.randomUUID();
        var user = UserTestBuilder.aUser().withUserId(userId).build();
        when(userRepository.findById(UserId.of(userId))).thenReturn(Optional.of(user));

        var response = controller.getUser(userId);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUserId()).isEqualTo(userId);
    }

    @Test
    void should_ThrowRuntimeException_When_GetUserNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(UserId.of(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getUser(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("User not found");
    }

    // --- Idempotent provisioning (BFF signup -> claims-service user provisioning) ---------------

    @Test
    void should_Return409WithExistingUser_When_SameEmailAndSameId() {
        UUID userId = UUID.randomUUID();
        var existing = UserTestBuilder.aUser().withUserId(userId).withEmail("jane@example.com").build();
        when(userRepository.findByEmail(Email.of("jane@example.com"))).thenReturn(Optional.of(existing));

        var response = controller.createUser(createRequest(userId, "jane@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getBody().getUserId()).isEqualTo(userId);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(userJpaRepository);
    }

    @Test
    void should_RekeyUserAndReturn201_When_SameEmailButNewKeycloakId() {
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        var existing = UserTestBuilder.aUser().withUserId(oldId).withEmail("jane@example.com").build();
        when(userRepository.findByEmail(Email.of("jane@example.com"))).thenReturn(Optional.of(existing));
        var rekeyed = new UserJpaEntity(newId, "jane@example.com", "Jane Doe", UserRole.CLAIMANT, Instant.now());
        when(userJpaRepository.findById(newId)).thenReturn(Optional.of(rekeyed));

        var response = controller.createUser(createRequest(newId, "jane@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(response.getBody().getUserId()).isEqualTo(newId);
        assertThat(response.getBody().getEmail()).isEqualTo("jane@example.com");
        var order = inOrder(userJpaRepository);
        order.verify(userJpaRepository).updateUserId(oldId, newId);
        order.verify(userJpaRepository).flush();
        order.verify(userJpaRepository).findById(newId);
        verify(userRepository, never()).save(any());
    }

    @Test
    void should_ThrowIllegalState_When_RekeyedUserCannotBeReloaded() {
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        var existing = UserTestBuilder.aUser().withUserId(oldId).withEmail("jane@example.com").build();
        when(userRepository.findByEmail(Email.of("jane@example.com"))).thenReturn(Optional.of(existing));
        when(userJpaRepository.findById(newId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.createUser(createRequest(newId, "jane@example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User ID update failed");
    }

    private static CreateUserRequestDTO createRequest(UUID userId, String email) {
        var request = new CreateUserRequestDTO();
        request.setUserId(userId);
        request.setName("Jane Doe");
        request.setEmail(email);
        request.setRole(UserRoleDTO.CLAIMANT);
        return request;
    }
}
