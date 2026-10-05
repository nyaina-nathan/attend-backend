package mg.improve.attend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cookie settings bound from {@code app.cookie.*}.
 *
 * <p>{@code sameSite=None} is required for a cross-site frontend, but browsers only store
 * such a cookie when it is also {@code Secure}, so {@code secure} must be {@code true}
 * whenever the app is served over HTTPS.
 *
 * @param secure   whether the browser may only send the cookie over HTTPS
 * @param sameSite SameSite attribute: {@code None}, {@code Lax} or {@code Strict}
 */
@ConfigurationProperties(prefix = "app.cookie")
public record CookieProperties(boolean secure, String sameSite) {
}
