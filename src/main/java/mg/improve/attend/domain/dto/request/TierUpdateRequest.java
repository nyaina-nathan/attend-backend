package mg.improve.attend.domain.dto.request;

import java.math.BigDecimal;

/**
 * Body of {@code PATCH /api/tiers/{tierId}}. Only the non-null fields are applied; validation
 * lives in {@code TicketTierValidator}.
 */
public record TierUpdateRequest(
        String tierName,
        BigDecimal price,
        Integer capacity) {
}
