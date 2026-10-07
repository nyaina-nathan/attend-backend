package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.domain.enums.TicketStatus;

/**
 * Ticket object returned by the ticket endpoints. {@code id} is the value encoded in the QR
 * code; {@code pricePaid} is frozen at issue time and {@code validUntil} defaults to the
 * event's end time.
 */
public record TicketResponse(
        UUID id,
        TicketEventRef event,
        TicketTierRef tier,
        BigDecimal pricePaid,
        TicketStatus ticketStatus,
        Instant checkedInAt,
        Instant validUntil,
        Instant createdAt) {

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                TicketEventRef.from(ticket.getTier().getEvent()),
                TicketTierRef.from(ticket.getTier()),
                ticket.getPricePaid(),
                ticket.getTicketStatus(),
                ticket.getCheckedInAt(),
                ticket.getValidUntil(),
                ticket.getCreatedAt());
    }
}
