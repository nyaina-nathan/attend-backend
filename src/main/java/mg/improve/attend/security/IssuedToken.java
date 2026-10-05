package mg.improve.attend.security;

import java.time.Instant;

/**
 * A freshly issued token together with its expiry instant, so callers can persist the
 * refresh token hash and its {@code expires_at} without re-parsing the token.
 */
public record IssuedToken(String token, Instant expiresAt) {
}
