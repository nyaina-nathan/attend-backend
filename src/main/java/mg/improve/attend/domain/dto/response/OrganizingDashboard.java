package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Organizer activity for the dashboard. {@code ticketsSold} and {@code revenue} are scoped to
 * upcoming events only and count {@code VALID}/{@code USED} tickets.
 */
public record OrganizingDashboard(
        long eventsTotal,
        long eventsUpcoming,
        long ticketsSold,
        BigDecimal revenue,
        NextOrganizingEvent nextEvent,
        List<AttentionItem> attention) {
}
