package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.enums.EventStatus;

/**
 * Event card returned by the public list. {@code minPrice} is the lowest price across the
 * event's tiers, or {@code null} when the event has no tiers yet.
 */
public record EventSummary(
        UUID id,
        String title,
        Instant startTime,
        Instant endTime,
        String location,
        String priceUnit,
        EventStatus status,
        BigDecimal minPrice) {

    public static EventSummary from(Events event, BigDecimal minPrice) {
        return new EventSummary(
                event.getId(),
                event.getTitle(),
                event.getStartTime(),
                event.getEndTime(),
                event.getLocation(),
                event.getPriceUnit(),
                event.getStatus(),
                minPrice);
    }
}
