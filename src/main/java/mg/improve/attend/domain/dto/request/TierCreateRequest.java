package mg.improve.attend.domain.dto.request;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/events/{eventId}/tiers}. A plain data carrier: input validation
 * lives in {@code TicketTierValidator}.
 */
public record TierCreateRequest(
        String tierName,
        BigDecimal price,
        Integer capacity) {
}
