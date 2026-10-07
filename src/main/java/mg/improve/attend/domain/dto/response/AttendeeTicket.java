package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.domain.enums.TicketStatus;

/**
 * A ticket as seen in an event's attendee list (organizers only). Always carries the owner and
 * the tier; unlike {@link TicketResponse} it omits the event, which is implied by the endpoint.
 */
public record AttendeeTicket(
        UUID id,
        TicketOwner owner,
        TicketTierRef tier,
        BigDecimal pricePaid,
        TicketStatus ticketStatus,
        Instant checkedInAt,
        Instant createdAt) {

    public static AttendeeTicket from(Ticket ticket) {
        return new AttendeeTicket(
                ticket.getId(),
                TicketOwner.from(ticket.getOwner()),
                TicketTierRef.from(ticket.getTier()),
                ticket.getPricePaid(),
                ticket.getTicketStatus(),
                ticket.getCheckedInAt(),
                ticket.getCreatedAt());
    }
}
