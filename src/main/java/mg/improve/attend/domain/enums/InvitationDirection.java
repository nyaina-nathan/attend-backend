package mg.improve.attend.domain.enums;

/**
 * Which side of an invitation a caller wants to list. Query-only, never persisted: {@code
 * RECEIVED} for invitations addressed to the caller, {@code SENT} for those they sent, and
 * {@code ALL} for both.
 */
public enum InvitationDirection {
    RECEIVED,
    SENT,
    ALL
}
