package mg.improve.attend.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;

/**
 * Full event object, returned by event creation and detail reads. {@code myRole} is the
 * caller's role on the event, or {@code null} for anonymous callers and non-organizers.
 */
public record EventDetail(
        UUID id,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        @JsonProperty("isPublic") boolean isPublic,
        String location,
        BigDecimal latitude,
        BigDecimal longitude,
        String priceUnit,
        EventStatus status,
        EventRole myRole,
        List<TierSummary> tiers,
        Instant createdAt,
        Instant updatedAt) {

    public static EventDetail from(Events event, EventRole myRole, List<TierSummary> tiers) {
        return new EventDetail(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getStartTime(),
                event.getEndTime(),
                event.isPublic(),
                event.getLocation(),
                event.getLatitude(),
                event.getLongitude(),
                event.getPriceUnit(),
                event.getStatus(),
                myRole,
                tiers == null ? List.of() : List.copyOf(tiers),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
