package mg.improve.attend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKey;
import mg.improve.attend.config.JwtProperties;
import mg.improve.attend.domain.entity.Users;
import org.springframework.stereotype.Service;

/**
 * Creates, identifies and verifies the signed JWT access and refresh tokens.
 *
 * <p>Both tokens are HS256-signed and carry the user id in {@code sub}, the token kind in
 * the {@code type} claim and a unique {@code jti}. The {@code type} claim is what stops a
 * refresh token from being accepted as an access token.
 *
 * <p>Refresh-token persistence (hashing, rotation, reuse detection) lives in the service
 * layer; use {@link #hashRefreshToken(String)} to produce the value stored in
 * {@code refresh_token.token_hash}.
 */
@Service
public class JwtService {

    private static final String CLAIM_TYPE = "type";

    private final SecretKey signingKey;
    private final Duration accessExpiration;
    private final Duration refreshExpiration;

    public JwtService(JwtProperties properties) {
        // Keys.hmacShaKeyFor rejects secrets shorter than 256 bits, as required by HS256.
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessExpiration = properties.expiration().access();
        this.refreshExpiration = properties.expiration().refresh();
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    public IssuedToken issueAccessToken(Users user) {
        return buildToken(user.getId(), TokenType.ACCESS, accessExpiration);
    }

    public IssuedToken issueRefreshToken(Users user) {
        return buildToken(user.getId(), TokenType.REFRESH, refreshExpiration);
    }

    private IssuedToken buildToken(UUID userId, TokenType type, Duration ttl) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);
        String token = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TYPE, type.value())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    // ------------------------------------------------------------------
    // Identify
    // ------------------------------------------------------------------

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public TokenType extractType(String token) {
        return TokenType.from(parseClaims(token).get(CLAIM_TYPE, String.class));
    }

    public Instant extractExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    public String extractJti(String token) {
        return parseClaims(token).getId();
    }

    // ------------------------------------------------------------------
    // Verify
    // ------------------------------------------------------------------

    /**
     * @return true when the signature is valid and the token is not expired.
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isAccessToken(String token) {
        return hasType(token, TokenType.ACCESS);
    }

    public boolean isRefreshToken(String token) {
        return hasType(token, TokenType.REFRESH);
    }

    private boolean hasType(String token, TokenType expected) {
        try {
            return extractType(token) == expected;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Refresh-token storage helper
    // ------------------------------------------------------------------

    /**
     * SHA-256 of the raw refresh token, hex-encoded to the 64 chars expected by
     * {@code refresh_token.token_hash}. Never store the raw token.
     */
    public String hashRefreshToken(String rawToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
