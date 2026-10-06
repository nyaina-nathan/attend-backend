package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.util.UUID;
import mg.improve.attend.domain.entity.TicketTier;

/**
 * Ticket tier object returned by the tier endpoints. {@code remaining} is {@code capacity}
 * minus the tickets in {@code VALID} or {@code USED}.
 */
public record Tier(
        UUID id,
        UUID eventId,
        String tierName,
        BigDecimal price,
        int capacity,
        int remaining) {

    public static Tier from(TicketTier tier, int remaining) {
        return new Tier(
                tier.getId(),
                tier.getEvent().getId(),
                tier.getTierName(),
                tier.getPrice(),
                tier.getCapacity(),
                remaining);
    }
}
