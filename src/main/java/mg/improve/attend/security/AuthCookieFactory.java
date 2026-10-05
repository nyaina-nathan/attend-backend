package mg.improve.attend.security;

import java.time.Duration;
import java.time.Instant;
import mg.improve.attend.config.CookieProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Turns freshly issued tokens into the {@code HttpOnly} auth cookies described by the API.
 *
 * <p>The refresh cookie is scoped to {@code /api/auth} so it is only sent to the endpoints
 * that need it, while the access cookie is available to the whole application.
 */
@Component
public class AuthCookieFactory {

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String ACCESS_TOKEN_PATH = "/";
    private static final String REFRESH_TOKEN_PATH = "/api/auth";

    private final CookieProperties properties;

    public AuthCookieFactory(CookieProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the {@code Set-Cookie} headers for a successful authentication. A {@code null}
     * refresh token means the refresh token was reused rather than rotated, so only the access
     * cookie is (re)set.
     */
    public HttpHeaders headers(IssuedToken accessToken, IssuedToken refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, build(ACCESS_TOKEN_COOKIE, accessToken, ACCESS_TOKEN_PATH).toString());
        if (refreshToken != null) {
            headers.add(HttpHeaders.SET_COOKIE,
                    build(REFRESH_TOKEN_COOKIE, refreshToken, REFRESH_TOKEN_PATH).toString());
        }
        return headers;
    }

    private ResponseCookie build(String name, IssuedToken token, String path) {
        Duration maxAge = Duration.between(Instant.now(), token.expiresAt());
        if (maxAge.isNegative()) {
            maxAge = Duration.ZERO;
        }
        return ResponseCookie.from(name, token.token())
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
