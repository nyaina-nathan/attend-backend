package mg.improve.attend.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.config.RefreshProperties;
import mg.improve.attend.domain.entity.RefreshToken;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.mapper.RefreshTokenMapper;
import mg.improve.attend.domain.mapper.UsersMapper;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JRefreshTokenRepository;
import mg.improve.attend.repository.entities.JRefreshToken;
import mg.improve.attend.security.ClientInfo;
import mg.improve.attend.security.ClientInfoResolver;
import mg.improve.attend.security.IssuedToken;
import mg.improve.attend.security.JwtService;
import mg.improve.attend.security.RefreshTokenCookieResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the refresh-token lifecycle: issuing, rotating and revoking sessions, plus the
 * {@code POST /auth/refresh} use case.
 *
 * <p>Reuse detection: because an unused refresh token stays valid, the only way to present a
 * revoked one is to replay a token that was already rotated. That revokes the rest of the
 * chain (the replacement and its descendants) and fails with {@code REFRESH_TOKEN_REUSED}.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final JRefreshTokenRepository refreshTokens;
    private final RefreshTokenMapper refreshTokenMapper;
    private final UsersMapper usersMapper;
    private final JwtService jwtService;
    private final RefreshTokenCookieResolver cookieResolver;
    private final ClientInfoResolver clientInfoResolver;
    private final RefreshProperties properties;

    @Transactional
    public SessionTokens refresh(HttpServletRequest httpRequest) {
        String rawToken = cookieResolver.resolve(httpRequest).orElseThrow(RefreshTokenService::invalid);
        if (!jwtService.isValid(rawToken) || !jwtService.isRefreshToken(rawToken)) {
            throw invalid();
        }

        String tokenHash = jwtService.hashRefreshToken(rawToken);
        // Pessimistic lock so two concurrent refreshes cannot both rotate the same token.
        JRefreshToken token = refreshTokens.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(RefreshTokenService::invalid);

        if (token.getRevokedAt() != null) {
            revokeChainFrom(token);
            throw new UnauthorizedException("REFRESH_TOKEN_REUSED", "Refresh token has already been used");
        }

        Users user = usersMapper.toDomain(token.getUser());
        IssuedToken accessToken = jwtService.issueAccessToken(user);

        if (isNearExpiry(token.getExpiresAt())) {
            IssuedToken refreshToken = jwtService.issueRefreshToken(user);
            rotate(token, refreshToken, clientInfoResolver.resolve(httpRequest));
            return new SessionTokens(accessToken, refreshToken);
        }

        return new SessionTokens(accessToken, null);
    }

    /**
     * Issues a brand-new access + refresh pair and persists the refresh token. Shared by
     * register, login and password change so token creation has a single owner.
     */
    @Transactional
    public SessionTokens issueSession(Users user, ClientInfo clientInfo) {
        IssuedToken accessToken = jwtService.issueAccessToken(user);
        IssuedToken refreshToken = jwtService.issueRefreshToken(user);
        refreshTokens.save(newRefreshEntity(user, refreshToken, clientInfo));
        return new SessionTokens(accessToken, refreshToken);
    }

    /**
     * Revokes every still-active refresh token of the user, invalidating their existing
     * sessions.
     */
    @Transactional
    public void revokeAllActive(UUID userId) {
        refreshTokens.revokeActiveByUserId(userId, Instant.now());
    }

    /**
     * Logs the caller out by revoking the refresh token they present, together with any token
     * it was replaced by. Idempotent: a missing, unknown, foreign or already-revoked token is
     * simply ignored, because the cookies are cleared regardless.
     */
    @Transactional
    public void logout(UUID userId, HttpServletRequest httpRequest) {
        cookieResolver.resolve(httpRequest)
                .map(jwtService::hashRefreshToken)
                .flatMap(refreshTokens::findByTokenHashForUpdate)
                .filter(token -> token.getUser().getId().equals(userId))
                .ifPresent(this::revokeChainFrom);
    }

    private boolean isNearExpiry(Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.compareTo(properties.rotationThreshold()) < 0;
    }

    private void rotate(JRefreshToken current, IssuedToken newToken, ClientInfo clientInfo) {
        Users user = usersMapper.toDomain(current.getUser());
        JRefreshToken savedReplacement = refreshTokens.save(newRefreshEntity(user, newToken, clientInfo));

        current.setRevokedAt(Instant.now());
        current.setReplacedBy(savedReplacement);
    }

    private JRefreshToken newRefreshEntity(Users user, IssuedToken refreshToken, ClientInfo clientInfo) {
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtService.hashRefreshToken(refreshToken.token()))
                .expiresAt(refreshToken.expiresAt())
                .userAgent(clientInfo.userAgent())
                .ipAddress(clientInfo.ipAddress())
                .build();
        return refreshTokenMapper.toEntity(token);
    }

    private void revokeChainFrom(JRefreshToken start) {
        Instant now = Instant.now();
        Set<UUID> visited = new HashSet<>();
        JRefreshToken cursor = start;
        while (cursor != null && visited.add(cursor.getId())) {
            if (cursor.getRevokedAt() == null) {
                cursor.setRevokedAt(now);
            }
            cursor = cursor.getReplacedBy();
        }
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException("REFRESH_TOKEN_INVALID", "Refresh token is invalid");
    }
}
