package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.config.RefreshProperties;
import mg.improve.attend.domain.entity.RefreshToken;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.mapper.RefreshTokenMapper;
import mg.improve.attend.domain.mapper.UsersMapper;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JRefreshTokenRepository;
import mg.improve.attend.repository.entities.JRefreshToken;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.security.ClientInfo;
import mg.improve.attend.security.ClientInfoResolver;
import mg.improve.attend.security.IssuedToken;
import mg.improve.attend.security.JwtService;
import mg.improve.attend.security.RefreshTokenCookieResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTests {

    private static final String RAW_TOKEN = "raw-refresh-token";
    private static final String TOKEN_HASH = "token-hash";

    @Mock
    private JRefreshTokenRepository refreshTokens;

    @Mock
    private RefreshTokenMapper refreshTokenMapper;

    @Mock
    private UsersMapper usersMapper;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenCookieResolver cookieResolver;

    @Mock
    private ClientInfoResolver clientInfoResolver;

    @Mock
    private HttpServletRequest httpRequest;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        // Real properties so the rotation-threshold branch is exercised deterministically.
        service = new RefreshTokenService(
                refreshTokens,
                refreshTokenMapper,
                usersMapper,
                jwtService,
                cookieResolver,
                clientInfoResolver,
                new RefreshProperties(Duration.ofHours(24)));
    }

    // ------------------------------------------------------------------
    // refresh
    // ------------------------------------------------------------------

    @Test
    void refresh_rejectsMissingCookie() {
        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.empty());

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.refresh(httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("REFRESH_TOKEN_INVALID"));

        verifyNoInteractions(jwtService, refreshTokens);
    }

    @Test
    void refresh_rejectsInvalidJwt() {
        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.isValid(RAW_TOKEN)).thenReturn(false);

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.refresh(httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("REFRESH_TOKEN_INVALID"));

        verify(jwtService, never()).hashRefreshToken(any());
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void refresh_rejectsAccessTokenPresentedAsRefreshToken() {
        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.isValid(RAW_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(RAW_TOKEN)).thenReturn(false);

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.refresh(httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("REFRESH_TOKEN_INVALID"));

        verify(jwtService, never()).hashRefreshToken(any());
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void refresh_rejectsUnknownTokenHash() {
        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.isValid(RAW_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(RAW_TOKEN)).thenReturn(true);
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.refresh(httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void refresh_detectsReuseAndRevokesTheReplacementChain() {
        JRefreshToken replacement = token(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)));
        JRefreshToken revoked = token(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)));
        revoked.setRevokedAt(Instant.now().minus(Duration.ofMinutes(5)));
        revoked.setReplacedBy(replacement);

        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.isValid(RAW_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(RAW_TOKEN)).thenReturn(true);
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(revoked));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.refresh(httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("REFRESH_TOKEN_REUSED"));

        assertThat(replacement.getRevokedAt()).isNotNull();
        verifyNoInteractions(refreshTokenMapper);
    }

    @Test
    void refresh_revokesChainWithoutLoopingOnACycle() {
        JRefreshToken cyclic = token(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)));
        cyclic.setRevokedAt(Instant.now().minus(Duration.ofMinutes(5)));
        cyclic.setReplacedBy(cyclic);

        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.isValid(RAW_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(RAW_TOKEN)).thenReturn(true);
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(cyclic));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.refresh(httpRequest))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void refresh_reusesTokenWhenItIsFarFromExpiry() {
        UUID userId = UUID.randomUUID();
        JUsers userEntity = userEntity(userId);
        Users user = user(userId);
        JRefreshToken token = token(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(48)));
        token.setUser(userEntity);
        IssuedToken accessToken = new IssuedToken("access", Instant.now().plus(Duration.ofMinutes(15)));

        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.isValid(RAW_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(RAW_TOKEN)).thenReturn(true);
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
        when(usersMapper.toDomain(userEntity)).thenReturn(user);
        when(jwtService.issueAccessToken(user)).thenReturn(accessToken);

        SessionTokens result = service.refresh(httpRequest);

        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.refreshToken()).isNull();
        assertThat(token.getRevokedAt()).isNull();
        verify(jwtService, never()).issueRefreshToken(any());
        verify(refreshTokens, never()).save(any());
        verifyNoInteractions(refreshTokenMapper, clientInfoResolver);
    }

    @Test
    void refresh_rotatesTokenWhenItIsNearExpiry() {
        UUID userId = UUID.randomUUID();
        JUsers userEntity = userEntity(userId);
        Users user = user(userId);
        JRefreshToken token = token(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1)));
        token.setUser(userEntity);

        ClientInfo clientInfo = new ClientInfo("junit-agent", "127.0.0.1");
        IssuedToken accessToken = new IssuedToken("access", Instant.now().plus(Duration.ofMinutes(15)));
        IssuedToken rotated = new IssuedToken("rotated-refresh", Instant.now().plus(Duration.ofDays(30)));
        JRefreshToken replacement = new JRefreshToken();
        replacement.setId(UUID.randomUUID());

        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.isValid(RAW_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(RAW_TOKEN)).thenReturn(true);
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
        when(usersMapper.toDomain(userEntity)).thenReturn(user);
        when(jwtService.issueAccessToken(user)).thenReturn(accessToken);
        when(jwtService.issueRefreshToken(user)).thenReturn(rotated);
        when(jwtService.hashRefreshToken("rotated-refresh")).thenReturn("rotated-hash");
        when(clientInfoResolver.resolve(httpRequest)).thenReturn(clientInfo);
        when(refreshTokenMapper.toEntity(any(RefreshToken.class))).thenReturn(replacement);
        when(refreshTokens.save(replacement)).thenReturn(replacement);

        SessionTokens result = service.refresh(httpRequest);

        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.refreshToken()).isEqualTo(rotated);
        assertThat(token.getRevokedAt()).isNotNull();
        assertThat(token.getReplacedBy()).isEqualTo(replacement);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).toEntity(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().getTokenHash()).isEqualTo("rotated-hash");
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(rotated.expiresAt());
        assertThat(captor.getValue().getUserAgent()).isEqualTo("junit-agent");
        assertThat(captor.getValue().getIpAddress()).isEqualTo("127.0.0.1");
        verify(refreshTokens).save(replacement);
    }

    // ------------------------------------------------------------------
    // issueSession
    // ------------------------------------------------------------------

    @Test
    void issueSession_persistsRefreshTokenWithClientMetadata() {
        UUID userId = UUID.randomUUID();
        Users user = user(userId);
        ClientInfo clientInfo = new ClientInfo("junit-agent", "127.0.0.1");
        IssuedToken accessToken = new IssuedToken("access", Instant.now().plus(Duration.ofMinutes(15)));
        IssuedToken refreshToken = new IssuedToken("refresh", Instant.now().plus(Duration.ofDays(30)));
        JRefreshToken entity = new JRefreshToken();

        when(jwtService.issueAccessToken(user)).thenReturn(accessToken);
        when(jwtService.issueRefreshToken(user)).thenReturn(refreshToken);
        when(jwtService.hashRefreshToken("refresh")).thenReturn("refresh-hash");
        when(refreshTokenMapper.toEntity(any(RefreshToken.class))).thenReturn(entity);
        when(refreshTokens.save(entity)).thenReturn(entity);

        SessionTokens result = service.issueSession(user, clientInfo);

        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.refreshToken()).isEqualTo(refreshToken);
        verify(refreshTokens).save(entity);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).toEntity(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().getTokenHash()).isEqualTo("refresh-hash");
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(refreshToken.expiresAt());
        assertThat(captor.getValue().getUserAgent()).isEqualTo("junit-agent");
        assertThat(captor.getValue().getIpAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void issueSession_toleratesMissingClientMetadata() {
        Users user = user(UUID.randomUUID());
        IssuedToken accessToken = new IssuedToken("access", Instant.now().plus(Duration.ofMinutes(15)));
        IssuedToken refreshToken = new IssuedToken("refresh", Instant.now().plus(Duration.ofDays(30)));
        JRefreshToken entity = new JRefreshToken();

        when(jwtService.issueAccessToken(user)).thenReturn(accessToken);
        when(jwtService.issueRefreshToken(user)).thenReturn(refreshToken);
        when(jwtService.hashRefreshToken("refresh")).thenReturn("refresh-hash");
        when(refreshTokenMapper.toEntity(any(RefreshToken.class))).thenReturn(entity);
        when(refreshTokens.save(entity)).thenReturn(entity);

        service.issueSession(user, new ClientInfo(null, null));

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).toEntity(captor.capture());
        assertThat(captor.getValue().getUserAgent()).isNull();
        assertThat(captor.getValue().getIpAddress()).isNull();
    }

    // ------------------------------------------------------------------
    // revokeAllActive
    // ------------------------------------------------------------------

    @Test
    void revokeAllActive_delegatesWithCurrentInstant() {
        UUID userId = UUID.randomUUID();

        service.revokeAllActive(userId);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokens).revokeActiveByUserId(eq(userId), captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    // ------------------------------------------------------------------
    // logout
    // ------------------------------------------------------------------

    @Test
    void logout_ignoresMissingCookie() {
        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.empty());

        service.logout(UUID.randomUUID(), httpRequest);

        verifyNoInteractions(jwtService, refreshTokens);
    }

    @Test
    void logout_ignoresUnknownToken() {
        UUID userId = UUID.randomUUID();
        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        service.logout(userId, httpRequest);

        verify(refreshTokens, never()).save(any());
    }

    @Test
    void logout_doesNotRevokeForeignToken() {
        UUID userId = UUID.randomUUID();
        JRefreshToken token = token(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)));
        token.setUser(userEntity(UUID.randomUUID()));

        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

        service.logout(userId, httpRequest);

        assertThat(token.getRevokedAt()).isNull();
    }

    @Test
    void logout_revokesOwnTokenAndItsReplacement() {
        UUID userId = UUID.randomUUID();
        JRefreshToken replacement = token(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)));
        JRefreshToken token = token(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)));
        token.setUser(userEntity(userId));
        token.setReplacedBy(replacement);

        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

        service.logout(userId, httpRequest);

        assertThat(token.getRevokedAt()).isNotNull();
        assertThat(replacement.getRevokedAt()).isNotNull();
    }

    @Test
    void logout_isIdempotentForAlreadyRevokedToken() {
        UUID userId = UUID.randomUUID();
        Instant revokedAt = Instant.now().minus(Duration.ofMinutes(5));
        JRefreshToken token = token(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)));
        token.setUser(userEntity(userId));
        token.setRevokedAt(revokedAt);

        when(cookieResolver.resolve(httpRequest)).thenReturn(Optional.of(RAW_TOKEN));
        when(jwtService.hashRefreshToken(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(refreshTokens.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

        service.logout(userId, httpRequest);

        assertThat(token.getRevokedAt()).isEqualTo(revokedAt);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JRefreshToken token(UUID id, Instant expiresAt) {
        JRefreshToken token = new JRefreshToken();
        token.setId(id);
        token.setTokenHash(TOKEN_HASH);
        token.setExpiresAt(expiresAt);
        return token;
    }

    private static JUsers userEntity(UUID id) {
        JUsers entity = new JUsers();
        entity.setId(id);
        return entity;
    }

    private static Users user(UUID id) {
        return Users.builder()
                .id(id)
                .email("user@example.com")
                .fullName("Test User")
                .username("testuser")
                .build();
    }
}
