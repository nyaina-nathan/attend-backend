package mg.improve.attend.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code refresh_token} cookie. Keeps the servlet API out of the controller and
 * service so the refresh use case only deals with the raw token value.
 */
@Component
public class RefreshTokenCookieResolver {

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    public Optional<String> resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (REFRESH_TOKEN_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }
}
