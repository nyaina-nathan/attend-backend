package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.domain.enums.TicketStatus;

/**
 * A ticket returned by {@code GET /tickets/{ticketId}}. Flattens the {@link TicketResponse}
 * fields and adds {@code owner} and {@code checkedBy}, which are only populated when the caller
 * is an organizer of the ticket's event.
 */
public record TicketDetail(
        UUID id,
        TicketEventRef event,
        TicketTierRef tier,
        BigDecimal pricePaid,
        TicketStatus ticketStatus,
        Instant checkedInAt,
        Instant validUntil,
        Instant createdAt,
        TicketOwner owner,
        CheckedBy checkedBy) {

    public static TicketDetail from(Ticket ticket, boolean includeOrganizerFields) {
        TicketOwner owner = includeOrganizerFields ? TicketOwner.from(ticket.getOwner()) : null;
        CheckedBy checkedBy = includeOrganizerFields ? CheckedBy.from(ticket.getCheckedBy()) : null;
        return new TicketDetail(
                ticket.getId(),
                TicketEventRef.from(ticket.getTier().getEvent()),
                TicketTierRef.from(ticket.getTier()),
                ticket.getPricePaid(),
                ticket.getTicketStatus(),
                ticket.getCheckedInAt(),
                ticket.getValidUntil(),
                ticket.getCreatedAt(),
                owner,
                checkedBy);
    }
}
