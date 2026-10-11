package mg.improve.attend.domain.dto.request;

import mg.improve.attend.domain.enums.InvitationStatus;

/**
 * Body of {@code PATCH /api/invitations/{invitationId}}. The receiver accepts or refuses a
 * {@code PENDING} invitation; the validator rejects {@code null} and {@code PENDING} so only
 * {@code ACCEPTED} or {@code DECLINED} are accepted.
 */
public record InvitationUpdateRequest(InvitationStatus invitationStatus) {
}
