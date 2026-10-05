package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.LoginRequest;
import mg.improve.attend.domain.dto.request.RegisterRequest;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.mapper.UsersMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.security.ClientInfo;
import mg.improve.attend.security.ClientInfoResolver;
import mg.improve.attend.security.IssuedToken;
import mg.improve.attend.validator.AuthValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    @Mock
    private JUsersRepository userRepo;

    @Mock
    private UsersMapper usersMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthValidator validator;

    @Mock
    private ClientInfoResolver clientInfoResolver;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private AuthService authService;

    // ------------------------------------------------------------------
    // register
    // ------------------------------------------------------------------

    @Test
    void register_persistsHashedUserAndStartsSession() {
        RegisterRequest request = new RegisterRequest(
                "user@example.com", "secret123", "Test User", "testuser", LocalDate.of(1990, 5, 20));
        ClientInfo clientInfo = new ClientInfo("junit-agent", "127.0.0.1");
        UUID userId = UUID.randomUUID();
        Users savedUser = user(userId);

        JUsers entity = new JUsers();
        JUsers savedEntity = new JUsers();
        IssuedToken accessToken = new IssuedToken("access", Instant.now().plusSeconds(900));
        IssuedToken refreshToken = new IssuedToken("refresh", Instant.now().plusSeconds(3600));

        when(userRepo.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.empty());
        when(clientInfoResolver.resolve(httpRequest)).thenReturn(clientInfo);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-secret");
        when(usersMapper.toEntity(any(Users.class))).thenReturn(entity);
        when(userRepo.saveAndFlush(entity)).thenReturn(savedEntity);
        when(usersMapper.toDomain(savedEntity)).thenReturn(savedUser);
        when(refreshTokenService.issueSession(savedUser, clientInfo))
                .thenReturn(new SessionTokens(accessToken, refreshToken));

        AuthResult result = authService.register(request, httpRequest);

        assertThat(result.user().id()).isEqualTo(userId);
        assertThat(result.user().email()).isEqualTo("user@example.com");
        assertThat(result.user().username()).isEqualTo("testuser");
        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.refreshToken()).isEqualTo(refreshToken);

        ArgumentCaptor<Users> captor = ArgumentCaptor.forClass(Users.class);
        verify(usersMapper).toEntity(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("encoded-secret");
        assertThat(captor.getValue().getFullName()).isEqualTo("Test User");
        assertThat(captor.getValue().getUsername()).isEqualTo("testuser");
        assertThat(captor.getValue().getDateBirth()).isEqualTo(LocalDate.of(1990, 5, 20));

        verify(passwordEncoder).encode("secret123");
        verify(userRepo).saveAndFlush(entity);
    }

    @Test
    void register_rejectsTakenEmail() {
        RegisterRequest request = new RegisterRequest(
                "user@example.com", "secret123", "Test User", "testuser", null);
        when(userRepo.findByEmail("user@example.com")).thenReturn(Optional.of(new JUsers()));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> authService.register(request, httpRequest))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("EMAIL_TAKEN");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(userRepo, never()).findByUsername(any());
        verifyNoInteractions(refreshTokenService, clientInfoResolver);
    }

    @Test
    void register_rejectsTakenUsername() {
        RegisterRequest request = new RegisterRequest(
                "user@example.com", "secret123", "Test User", "testuser", null);
        when(userRepo.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.of(new JUsers()));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> authService.register(request, httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("USERNAME_TAKEN"));

        verify(userRepo, never()).saveAndFlush(any());
        verifyNoInteractions(refreshTokenService, clientInfoResolver);
    }

    @Test
    void register_propagatesValidationFailureWithoutTouchingPersistence() {
        RegisterRequest request = new RegisterRequest("bad", "x", null, null, null);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validate(any(RegisterRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> authService.register(request, httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(userRepo, usersMapper, passwordEncoder, clientInfoResolver, refreshTokenService);
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    @Test
    void login_returnsSessionForValidCredentials() {
        LoginRequest request = new LoginRequest("user@example.com", "secret123");
        ClientInfo clientInfo = new ClientInfo("junit-agent", "127.0.0.1");
        UUID userId = UUID.randomUUID();
        Users user = user(userId);

        JUsers entity = new JUsers();
        entity.setId(userId);
        entity.setPasswordHash("hashed-password");

        IssuedToken accessToken = new IssuedToken("access", Instant.now().plusSeconds(900));
        IssuedToken refreshToken = new IssuedToken("refresh", Instant.now().plusSeconds(3600));

        when(userRepo.findByEmail("user@example.com")).thenReturn(Optional.of(entity));
        when(usersMapper.toDomain(entity)).thenReturn(user);
        when(passwordEncoder.matches("secret123", "hashed-password")).thenReturn(true);
        when(clientInfoResolver.resolve(httpRequest)).thenReturn(clientInfo);
        when(refreshTokenService.issueSession(user, clientInfo))
                .thenReturn(new SessionTokens(accessToken, refreshToken));

        AuthResult result = authService.login(request, httpRequest);

        assertThat(result.user().id()).isEqualTo(userId);
        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.refreshToken()).isEqualTo(refreshToken);
    }

    @Test
    void login_rejectsUnknownEmail() {
        LoginRequest request = new LoginRequest("nobody@example.com", "secret123");
        when(userRepo.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> authService.login(request, httpRequest))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });

        verifyNoInteractions(refreshTokenService, clientInfoResolver, usersMapper);
    }

    @Test
    void login_rejectsWrongPassword() {
        LoginRequest request = new LoginRequest("user@example.com", "wrong-password");
        Users user = user(UUID.randomUUID());
        JUsers entity = new JUsers();
        entity.setPasswordHash("hashed-password");

        when(userRepo.findByEmail("user@example.com")).thenReturn(Optional.of(entity));
        when(usersMapper.toDomain(entity)).thenReturn(user);
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> authService.login(request, httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS"));

        verifyNoInteractions(refreshTokenService, clientInfoResolver);
    }

    @Test
    void login_propagatesValidationFailureWithoutTouchingPersistence() {
        LoginRequest request = new LoginRequest(null, null);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validate(any(LoginRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> authService.login(request, httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(userRepo, usersMapper, passwordEncoder, clientInfoResolver, refreshTokenService);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Users user(UUID id) {
        return Users.builder()
                .id(id)
                .email("user@example.com")
                .passwordHash("hashed-password")
                .fullName("Test User")
                .username("testuser")
                .dateBirth(LocalDate.of(1990, 5, 20))
                .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
                .build();
    }
}
