package mg.improve.attend.domain.dto.request;

/**
 * Body of {@code POST /api/events/{eventId}/invitations}. Exactly one of {@code username} or
 * {@code email} identifies the user to invite; validation lives in {@code InvitationValidator}.
 */
public record InvitationCreateRequest(String username, String email) {
}
