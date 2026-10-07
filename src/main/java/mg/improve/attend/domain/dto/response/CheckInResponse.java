package mg.improve.attend.domain.dto.response;

import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.domain.enums.TicketStatus;

/**
 * Result of a successful check-in, matching the documented {@code CheckInResponse} schema.
 * {@code owner} and {@code tier} are inline objects (no id), so they are represented by nested
 * records rather than the shared reference DTOs.
 */
public record CheckInResponse(
        UUID id,
        TicketStatus ticketStatus,
        Instant checkedInAt,
        CheckedBy checkedBy,
        Owner owner,
        Tier tier) {

    public static CheckInResponse from(Ticket ticket, TicketStatus ticketStatus, Instant checkedInAt,
            CheckedBy checkedBy) {
        return new CheckInResponse(
                ticket.getId(),
                ticketStatus,
                checkedInAt,
                checkedBy,
                new Owner(ticket.getOwner().getFullName(), ticket.getOwner().getUsername()),
                new Tier(ticket.getTier().getTierName()));
    }

    public record Owner(String fullName, String username) {
    }

    public record Tier(String tierName) {
    }
}
