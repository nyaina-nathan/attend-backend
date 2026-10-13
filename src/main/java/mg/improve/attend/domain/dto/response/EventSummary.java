package mg.improve.attend.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.enums.EventStatus;

/**
 * Event card returned by the public list. {@code minPrice} is the lowest price across the
 * event's tiers, or {@code null} when the event has no tiers yet. {@code isOrderPending} is
 * {@code true}/{@code false} when the caller organizes the event (whether it has a
 * {@code PENDING} order), or {@code null} for anonymous callers and non-organizers.
 */
public record EventSummary(
        UUID id,
        String title,
        Instant startTime,
        Instant endTime,
        String location,
        BigDecimal latitude,
        BigDecimal longitude,
        String priceUnit,
        EventStatus status,
        BigDecimal minPrice,
        @JsonProperty("isOrderPending") Boolean isOrderPending) {

    public static EventSummary from(Events event, BigDecimal minPrice, Boolean isOrderPending) {
        return new EventSummary(
                event.getId(),
                event.getTitle(),
                event.getStartTime(),
                event.getEndTime(),
                event.getLocation(),
                event.getLatitude(),
                event.getLongitude(),
                event.getPriceUnit(),
                event.getStatus(),
                minPrice,
                isOrderPending);
    }
}
