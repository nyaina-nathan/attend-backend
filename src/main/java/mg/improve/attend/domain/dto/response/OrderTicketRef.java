package mg.improve.attend.domain.dto.response;

import java.util.UUID;
import mg.improve.attend.domain.enums.TicketStatus;

/**
 * Minimal ticket reference nested in an {@link OrderResponse}: an issued {@code VALID} ticket
 * or, for a refused order, a placeholder {@code REFUNDED} ticket.
 */
public record OrderTicketRef(
        UUID id,
        TicketStatus ticketStatus) {

    public static OrderTicketRef of(UUID id, TicketStatus ticketStatus) {
        return new OrderTicketRef(id, ticketStatus);
    }
}
