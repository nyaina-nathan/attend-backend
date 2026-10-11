package mg.improve.attend.domain.dto.response;

import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.Invitation;
import mg.improve.attend.domain.enums.InvitationStatus;

/**
 * An invitation to become an {@code ADMIN} of an event, returned by the invitation endpoints.
 */
public record InvitationResponse(
        UUID id,
        TicketEventRef event,
        UserRef sender,
        UserRef receiver,
        InvitationStatus invitationStatus,
        Instant createdAt) {

    public static InvitationResponse from(Invitation invitation) {
        return new InvitationResponse(
                invitation.getId(),
                TicketEventRef.from(invitation.getEvent()),
                UserRef.from(invitation.getSender()),
                UserRef.from(invitation.getReceiver()),
                invitation.getInvitationStatus(),
                invitation.getCreatedAt());
    }
}
