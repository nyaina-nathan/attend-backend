package mg.improve.attend.domain.dto.response;

import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;

/**
 * The caller's soonest upcoming organized event, with the same counters reported by
 * {@code GET /events/{eventId}/stats}. {@code ticketsIssued} counts {@code VALID} and
 * {@code USED} tickets; {@code ticketsCheckedIn} the {@code USED} subset.
 */
public record NextOrganizingEvent(
        UUID id,
        String title,
        Instant startTime,
        Instant endTime,
        String location,
        EventStatus status,
        EventRole myRole,
        int totalCapacity,
        long ticketsIssued,
        long ticketsCheckedIn) {
}
