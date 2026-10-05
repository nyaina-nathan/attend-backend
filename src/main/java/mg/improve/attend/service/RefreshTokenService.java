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
 * Refresh use case. Issues a new access token and decides, based on how close the presented
 * refresh token is to expiry, whether to rotate it.
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
            revokeChain(token);
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

    private boolean isNearExpiry(Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.compareTo(properties.rotationThreshold()) < 0;
    }

    private void rotate(JRefreshToken current, IssuedToken newToken, ClientInfo clientInfo) {
        Users user = usersMapper.toDomain(current.getUser());
        RefreshToken replacement = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtService.hashRefreshToken(newToken.token()))
                .expiresAt(newToken.expiresAt())
                .userAgent(clientInfo.userAgent())
                .ipAddress(clientInfo.ipAddress())
                .build();
        JRefreshToken savedReplacement = refreshTokens.save(refreshTokenMapper.toEntity(replacement));

        current.setRevokedAt(Instant.now());
        current.setReplacedBy(savedReplacement);
    }

    private void revokeChain(JRefreshToken revoked) {
        Instant now = Instant.now();
        Set<UUID> visited = new HashSet<>();
        JRefreshToken cursor = revoked.getReplacedBy();
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
