package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-tier sales and attendance statistics nested in {@link EventStats}. {@code issued} counts
 * the tickets in {@code VALID} or {@code USED}; {@code checkedIn} the {@code USED} subset; and
 * {@code revenue} the sum of {@code price_paid} across those tickets.
 */
public record TierStats(
        UUID tierId,
        String tierName,
        long issued,
        long checkedIn,
        BigDecimal revenue) {
}
