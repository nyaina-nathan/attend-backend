package mg.improve.attend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh-token settings bound from {@code app.refresh.*}.
 *
 * <p>When a refresh happens and the presented token is within {@code rotationThreshold} of its
 * expiry, the refresh token is rotated (revoked and replaced) so the session slides forward;
 * otherwise it is reused as is.
 *
 * @param rotationThreshold how close to expiry a refresh token must be before it is rotated
 */
@ConfigurationProperties(prefix = "app.refresh")
public record RefreshProperties(Duration rotationThreshold) {

    public RefreshProperties {
        if (rotationThreshold == null) {
            rotationThreshold = Duration.ofHours(24);
        }
    }
}
