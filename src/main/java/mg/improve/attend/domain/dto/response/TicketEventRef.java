package mg.improve.attend.domain.dto.response;

import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Events;

/**
 * Event reference nested in a {@link TicketResponse}. {@code location} is nullable.
 */
public record TicketEventRef(
        UUID id,
        String title,
        Instant startTime,
        String location) {

    public static TicketEventRef from(Events event) {
        return new TicketEventRef(
                event.getId(),
                event.getTitle(),
                event.getStartTime(),
                event.getLocation());
    }
}
