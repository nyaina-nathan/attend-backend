package mg.improve.attend.domain.dto.request;

/**
 * Body of {@code POST /api/events/{eventId}/organizers}. Exactly one of {@code username} or
 * {@code email} identifies the user to promote; validation lives in {@code EventValidator}.
 */
public record AddOrganizerRequest(String username, String email) {
}
