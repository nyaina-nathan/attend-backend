package mg.improve.attend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings bound from {@code spring.jwt.*} in application.yaml.
 *
 * <p>Durations accept the usual Spring formats ({@code 15m}, {@code 7d}, {@code P1D}).
 * A bare number is interpreted as milliseconds, e.g. {@code 900000}.
 */
@ConfigurationProperties(prefix = "spring.jwt")
public record JwtProperties(String secret, Expiration expiration) {

    /**
     * Access and refresh token time-to-live values.
     */
    public record Expiration(Duration access, Duration refresh) {
    }
}
