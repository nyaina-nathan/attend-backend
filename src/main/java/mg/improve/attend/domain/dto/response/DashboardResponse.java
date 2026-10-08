package mg.improve.attend.domain.dto.response;

/**
 * Aggregated data for the dashboard home page, returned by {@code GET /users/me/dashboard}.
 * Covers both the caller's organizer activity and their attendee activity. The month calendar
 * is not included; use {@code GET /events?isMine=true&from=&to=} for that.
 */
public record DashboardResponse(
        OrganizingDashboard organizing,
        AttendingDashboard attending) {
}
