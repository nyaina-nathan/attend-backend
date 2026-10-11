package mg.improve.attend.repository;

import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.repository.entities.JInvitation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JInvitationRepository extends JpaRepository<JInvitation, UUID> {

    boolean existsByEvent_IdAndReceiver_IdAndInvitationStatus(
            UUID eventId, UUID receiverId, InvitationStatus invitationStatus);

    /**
     * Loads an invitation with the associations needed to build its response in one query.
     */
    @EntityGraph(attributePaths = {"event", "sender", "receiver"})
    @Query("select i from JInvitation i where i.id = :id")
    Optional<JInvitation> findDetailById(@Param("id") UUID id);

    /**
     * A page of invitations visible to a user. {@code direction} is one of {@code RECEIVED} (the
     * caller is the receiver), {@code SENT} (the caller is the sender) or {@code ALL} (either),
     * optionally narrowed by status. The associations needed to build the response are fetched
     * eagerly so the whole page can be mapped without an N+1.
     */
    // coalesce (rather than ":status is null or ...") so Postgres can infer the enum type of the
    // optional parameter from the column it is compared to.
    @EntityGraph(attributePaths = {"event", "sender", "receiver"})
    @Query("select i from JInvitation i where ("
            + "(:direction = 'RECEIVED' and i.receiver.id = :userId) "
            + "or (:direction = 'SENT' and i.sender.id = :userId) "
            + "or (:direction = 'ALL' and (i.sender.id = :userId or i.receiver.id = :userId))) "
            + "and i.invitationStatus = coalesce(:status, i.invitationStatus)")
    Page<JInvitation> findForUser(@Param("userId") UUID userId,
            @Param("direction") String direction,
            @Param("status") InvitationStatus status,
            Pageable pageable);
}
