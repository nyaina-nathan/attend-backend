package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ticket tier summary nested in an {@link EventDetail}. {@code remaining} is {@code capacity}
 * minus the tickets in {@code VALID} or {@code USED}.
 */
public record TierSummary(
        UUID id,
        String tierName,
        BigDecimal price,
        int capacity,
        int remaining) {
}
