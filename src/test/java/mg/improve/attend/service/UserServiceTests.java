package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.ChangePasswordRequest;
import mg.improve.attend.domain.dto.request.UserUpdateRequest;
import mg.improve.attend.domain.dto.response.PageUserResponse;
import mg.improve.attend.domain.dto.response.UserMeResponse;
import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.mapper.UsersMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ErrorDetail;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.security.ClientInfo;
import mg.improve.attend.security.ClientInfoResolver;
import mg.improve.attend.security.IssuedToken;
import mg.improve.attend.validator.UserValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTests {

    @Mock
    private JUsersRepository users;

    @Mock
    private JEventsRepository events;

    @Mock
    private JEventOrganizerRepository organizers;

    @Mock
    private JTicketTierRepository tiers;

    @Mock
    private JTicketRepository tickets;

    @Mock
    private UsersMapper usersMapper;

    @Mock
    private UserValidator validator;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ClientInfoResolver clientInfoResolver;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private UserService userService;

    // ------------------------------------------------------------------
    // deleteAccount
    // ------------------------------------------------------------------

    @Test
    void deleteAccount_removesUserDataWhenNoEventsWereCreated() {
        UUID userId = UUID.randomUUID();
        when(organizers.findEventIdsByUserAndRole(userId, EventRole.CREATOR)).thenReturn(List.of());
        when(users.findById(userId)).thenReturn(Optional.empty());

        userService.deleteAccount(userId);

        verify(tickets).deleteByOwnerId(userId);
        verify(tickets, never()).deleteByEventId(any());
        verify(organizers).deleteByUserId(userId);
        verify(tickets).clearCheckInByUserId(userId);
        verify(users, never()).delete(any(JUsers.class));
        verifyNoInteractions(events, tiers, refreshTokenService);
    }

    @Test
    void deleteAccount_removesCreatorEventsAndTheUser() {
        UUID userId = UUID.randomUUID();
        UUID firstEvent = UUID.randomUUID();
        UUID secondEvent = UUID.randomUUID();
        JUsers entity = userEntity(userId);
        when(organizers.findEventIdsByUserAndRole(userId, EventRole.CREATOR))
                .thenReturn(List.of(firstEvent, secondEvent));
        when(users.findById(userId)).thenReturn(Optional.of(entity));

        userService.deleteAccount(userId);

        verify(tickets).deleteByEventId(firstEvent);
        verify(tickets).deleteByEventId(secondEvent);
        verify(tiers).deleteByEventId(firstEvent);
        verify(tiers).deleteByEventId(secondEvent);
        verify(organizers).deleteByEventId(firstEvent);
        verify(organizers).deleteByEventId(secondEvent);
        verify(events).deleteById(firstEvent);
        verify(events).deleteById(secondEvent);
        verify(users).delete(entity);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void deleteAccount_deletesInForeignKeySafeOrder() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        JUsers entity = userEntity(userId);
        when(organizers.findEventIdsByUserAndRole(userId, EventRole.CREATOR)).thenReturn(List.of(eventId));
        when(users.findById(userId)).thenReturn(Optional.of(entity));

        userService.deleteAccount(userId);

        InOrder inOrder = inOrder(tickets, tiers, organizers, events, users);
        inOrder.verify(tickets).deleteByOwnerId(userId);
        inOrder.verify(tickets).deleteByEventId(eventId);
        inOrder.verify(tiers).deleteByEventId(eventId);
        inOrder.verify(organizers).deleteByEventId(eventId);
        inOrder.verify(events).deleteById(eventId);
        inOrder.verify(organizers).deleteByUserId(userId);
        inOrder.verify(tickets).clearCheckInByUserId(userId);
        inOrder.verify(users).delete(entity);
    }

    // ------------------------------------------------------------------
    // getCurrentUser
    // ------------------------------------------------------------------

    @Test
    void getCurrentUser_returnsMappedProfile() {
        UUID userId = UUID.randomUUID();
        JUsers entity = userEntity(userId);
        Users domain = user(userId);
        when(users.findById(userId)).thenReturn(Optional.of(entity));
        when(usersMapper.toDomain(entity)).thenReturn(domain);

        UserMeResponse response = userService.getCurrentUser(userId);

        assertThat(response).isEqualTo(UserMeResponse.from(domain));
    }

    @Test
    void getCurrentUser_rejectsUnknownUser() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> userService.getCurrentUser(userId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    // ------------------------------------------------------------------
    // searchUsers
    // ------------------------------------------------------------------

    @Test
    void searchUsers_lowercasesPatternsAndMapsThePage() {
        UUID userId = UUID.randomUUID();
        JUsers entity = userEntity(userId);
        Users domain = user(userId);
        Sort sort = Sort.by("username");
        when(validator.validateSearchQuery("Jane", null, 0, 20, "username,asc")).thenReturn(sort);
        when(users.search("%jane%", null, PageRequest.of(0, 20, sort)))
                .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1));
        when(usersMapper.toDomain(entity)).thenReturn(domain);

        PageUserResponse response = userService.searchUsers("Jane", null, 0, 20, "username,asc");

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.content()).containsExactly(UserResponse.from(domain));
    }

    @Test
    void searchUsers_passesBothPatternsWhenBothProvided() {
        Sort sort = Sort.by("username");
        when(validator.validateSearchQuery("jane", "acme", 0, 20, "username,asc")).thenReturn(sort);
        when(users.search("%jane%", "%acme%", PageRequest.of(0, 20, sort)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageUserResponse response = userService.searchUsers("jane", "acme", 0, 20, "username,asc");

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void searchUsers_propagatesValidationFailure() {
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateSearchQuery(null, null, 0, 20, "username,asc");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> userService.searchUsers(null, null, 0, 20, "username,asc"))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(users);
    }

    // ------------------------------------------------------------------
    // updateCurrentUser
    // ------------------------------------------------------------------

    @Test
    void updateCurrentUser_appliesOnlyProvidedFields() {
        UUID userId = UUID.randomUUID();
        UserUpdateRequest request = new UserUpdateRequest("New Name", "newuser", LocalDate.of(1991, 2, 2));
        JUsers entity = userEntity(userId);
        Users domain = user(userId);

        when(users.findByUsername("newuser")).thenReturn(Optional.empty());
        when(users.findById(userId)).thenReturn(Optional.of(entity));
        when(users.saveAndFlush(entity)).thenReturn(entity);
        when(usersMapper.toDomain(entity)).thenReturn(domain);

        UserMeResponse response = userService.updateCurrentUser(userId, request);

        verify(validator).validate(request);
        assertThat(entity.getFullName()).isEqualTo("New Name");
        assertThat(entity.getUsername()).isEqualTo("newuser");
        assertThat(entity.getDateBirth()).isEqualTo(LocalDate.of(1991, 2, 2));
        assertThat(entity.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(response).isEqualTo(UserMeResponse.from(domain));
    }

    @Test
    void updateCurrentUser_skipsUniquenessCheckWhenUsernameAbsent() {
        UUID userId = UUID.randomUUID();
        UserUpdateRequest request = new UserUpdateRequest("New Name", null, null);
        JUsers entity = userEntity(userId);
        Users domain = user(userId);

        when(users.findById(userId)).thenReturn(Optional.of(entity));
        when(users.saveAndFlush(entity)).thenReturn(entity);
        when(usersMapper.toDomain(entity)).thenReturn(domain);

        userService.updateCurrentUser(userId, request);

        verify(users, never()).findByUsername(any());
        assertThat(entity.getFullName()).isEqualTo("New Name");
        assertThat(entity.getUsername()).isEqualTo("originaluser");
    }

    @Test
    void updateCurrentUser_rejectsUsernameOwnedByAnotherUser() {
        UUID userId = UUID.randomUUID();
        UserUpdateRequest request = new UserUpdateRequest(null, "newuser", null);
        when(users.findByUsername("newuser")).thenReturn(Optional.of(userEntity(UUID.randomUUID())));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> userService.updateCurrentUser(userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("USERNAME_TAKEN"));

        verify(users, never()).findById(any());
    }

    @Test
    void updateCurrentUser_allowsKeepingOwnUsername() {
        UUID userId = UUID.randomUUID();
        UserUpdateRequest request = new UserUpdateRequest(null, "newuser", null);
        JUsers entity = userEntity(userId);
        Users domain = user(userId);

        when(users.findByUsername("newuser")).thenReturn(Optional.of(entity));
        when(users.findById(userId)).thenReturn(Optional.of(entity));
        when(users.saveAndFlush(entity)).thenReturn(entity);
        when(usersMapper.toDomain(entity)).thenReturn(domain);

        userService.updateCurrentUser(userId, request);

        verify(users).saveAndFlush(entity);
    }

    @Test
    void updateCurrentUser_rejectsUnknownUser() {
        UUID userId = UUID.randomUUID();
        UserUpdateRequest request = new UserUpdateRequest(null, "newuser", null);
        when(users.findByUsername("newuser")).thenReturn(Optional.empty());
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> userService.updateCurrentUser(userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND"));
    }

    @Test
    void updateCurrentUser_propagatesValidationFailure() {
        UUID userId = UUID.randomUUID();
        UserUpdateRequest request = new UserUpdateRequest(" ", " ", null);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validate(any(UserUpdateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> userService.updateCurrentUser(userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(users);
    }

    // ------------------------------------------------------------------
    // changePassword
    // ------------------------------------------------------------------

    @Test
    void changePassword_revokesSessionsThenIssuesANewOne() {
        UUID userId = UUID.randomUUID();
        ChangePasswordRequest request = new ChangePasswordRequest("old-secret", "new-secret-123");
        JUsers entity = userEntity(userId);
        Users domain = user(userId);
        ClientInfo clientInfo = new ClientInfo("junit-agent", "127.0.0.1");
        IssuedToken accessToken = new IssuedToken("access", Instant.now().plusSeconds(900));
        IssuedToken refreshToken = new IssuedToken("refresh", Instant.now().plusSeconds(3600));

        when(users.findById(userId)).thenReturn(Optional.of(entity));
        when(passwordEncoder.matches("old-secret", "hashed-password")).thenReturn(true);
        when(passwordEncoder.encode("new-secret-123")).thenReturn("new-hash");
        when(users.saveAndFlush(entity)).thenReturn(entity);
        when(usersMapper.toDomain(entity)).thenReturn(domain);
        when(clientInfoResolver.resolve(httpRequest)).thenReturn(clientInfo);
        when(refreshTokenService.issueSession(domain, clientInfo))
                .thenReturn(new SessionTokens(accessToken, refreshToken));

        SessionTokens result = userService.changePassword(userId, request, httpRequest);

        verify(validator).validate(request);
        assertThat(entity.getPasswordHash()).isEqualTo("new-hash");
        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.refreshToken()).isEqualTo(refreshToken);

        InOrder inOrder = inOrder(users, refreshTokenService);
        inOrder.verify(users).saveAndFlush(entity);
        inOrder.verify(refreshTokenService).revokeAllActive(userId);
        inOrder.verify(refreshTokenService).issueSession(domain, clientInfo);
    }

    @Test
    void changePassword_rejectsWrongCurrentPassword() {
        UUID userId = UUID.randomUUID();
        ChangePasswordRequest request = new ChangePasswordRequest("wrong-secret", "new-secret-123");
        JUsers entity = userEntity(userId);

        when(users.findById(userId)).thenReturn(Optional.of(entity));
        when(passwordEncoder.matches("wrong-secret", "hashed-password")).thenReturn(false);

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> userService.changePassword(userId, request, httpRequest))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getDetails()).extracting(ErrorDetail::field)
                            .containsExactly("currentPassword");
                });

        verify(users, never()).saveAndFlush(any());
        verify(refreshTokenService, never()).revokeAllActive(any());
        verify(refreshTokenService, never()).issueSession(any(), any());
    }

    @Test
    void changePassword_rejectsUnknownUser() {
        UUID userId = UUID.randomUUID();
        ChangePasswordRequest request = new ChangePasswordRequest("old-secret", "new-secret-123");
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> userService.changePassword(userId, request, httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND"));

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void changePassword_propagatesValidationFailure() {
        UUID userId = UUID.randomUUID();
        ChangePasswordRequest request = new ChangePasswordRequest(null, null);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validate(any(ChangePasswordRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> userService.changePassword(userId, request, httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(users, refreshTokenService);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JUsers userEntity(UUID id) {
        JUsers entity = new JUsers();
        entity.setId(id);
        entity.setEmail("user@example.com");
        entity.setPasswordHash("hashed-password");
        entity.setFullName("Original Name");
        entity.setUsername("originaluser");
        entity.setDateBirth(LocalDate.of(1990, 5, 20));
        entity.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2024-01-02T00:00:00Z"));
        return entity;
    }

    private static Users user(UUID id) {
        return Users.builder()
                .id(id)
                .email("user@example.com")
                .passwordHash("hashed-password")
                .fullName("Original Name")
                .username("originaluser")
                .dateBirth(LocalDate.of(1990, 5, 20))
                .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2024-01-02T00:00:00Z"))
                .build();
    }
}
