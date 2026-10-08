package mg.improve.attend.domain.dto.response;

/**
 * Attendee activity for the dashboard. {@code ticketsUpcoming} counts the caller's
 * {@code VALID} tickets for upcoming events.
 */
public record AttendingDashboard(
        long ticketsUpcoming,
        NextTicket nextTicket) {
}
