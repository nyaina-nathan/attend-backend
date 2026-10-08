package mg.improve.attend.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One item in {@code organizing.attention}. Every item carries {@code type}, {@code eventId},
 * {@code eventTitle} and {@code startTime}; the remaining fields are rule-specific and only
 * present when relevant (nulls are omitted).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttentionItem(
        AttentionType type,
        UUID eventId,
        String eventTitle,
        Instant startTime,
        UUID tierId,
        String tierName,
        Integer remaining,
        Long ticketsIssued) {

    public static AttentionItem noTiers(UUID eventId, String eventTitle, Instant startTime) {
        return new AttentionItem(AttentionType.NO_TIERS, eventId, eventTitle, startTime, null, null, null, null);
    }

    public static AttentionItem lowStock(UUID eventId, String eventTitle, Instant startTime,
            UUID tierId, String tierName, int remaining) {
        return new AttentionItem(AttentionType.LOW_STOCK, eventId, eventTitle, startTime,
                tierId, tierName, remaining, null);
    }

    public static AttentionItem postponed(UUID eventId, String eventTitle, Instant startTime,
            long ticketsIssued) {
        return new AttentionItem(AttentionType.POSTPONED, eventId, eventTitle, startTime, null, null, null,
                ticketsIssued);
    }
}
