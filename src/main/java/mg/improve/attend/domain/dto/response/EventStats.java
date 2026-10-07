package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Sales and attendance statistics for a single event, returned by
 * {@code GET /events/{eventId}/stats}. Only tickets in {@code VALID} or {@code USED} count as
 * issued (and therefore toward {@code revenue}); cancelled and refunded tickets are excluded.
 */
public record EventStats(
        UUID eventId,
        int totalCapacity,
        long ticketsIssued,
        long ticketsCheckedIn,
        BigDecimal revenue,
        List<TierStats> byTier) {
}
