package mg.improve.attend.repository;

import java.util.UUID;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.repository.entities.JInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JInvitationRepository extends JpaRepository<JInvitation, UUID> {

    boolean existsByEvent_IdAndReceiver_IdAndInvitationStatus(
            UUID eventId, UUID receiverId, InvitationStatus invitationStatus);
}
