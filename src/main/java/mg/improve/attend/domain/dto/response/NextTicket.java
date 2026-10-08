package mg.improve.attend.domain.dto.response;

import java.util.UUID;
import mg.improve.attend.domain.enums.TicketStatus;

/**
 * The caller's next ticket, i.e. the {@code VALID} ticket whose event starts earliest among
 * upcoming events.
 */
public record NextTicket(
        UUID id,
        TicketEventRef event,
        TicketTierRef tier,
        TicketStatus ticketStatus) {
}
