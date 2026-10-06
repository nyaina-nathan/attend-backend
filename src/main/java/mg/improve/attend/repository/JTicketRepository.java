package mg.improve.attend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.repository.entities.JTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JTicketRepository extends JpaRepository<JTicket, UUID> {

    /**
     * Number of tickets issued per tier for an event, counting only the statuses that consume
     * capacity ({@code VALID}, {@code USED}).
     */
    @Query("select t.tier.id as tierId, count(t) as issued from JTicket t "
            + "where t.tier.event.id = :eventId and t.ticketStatus in :statuses group by t.tier.id")
    List<TierIssued> countIssuedByTier(@Param("eventId") UUID eventId,
            @Param("statuses") Collection<TicketStatus> statuses);

    boolean existsByTier_Event_Id(UUID eventId);

    boolean existsByTier_Id(UUID tierId);

    long countByTier_IdAndTicketStatusIn(UUID tierId, Collection<TicketStatus> statuses);

    interface TierIssued {
        UUID getTierId();

        long getIssued();
    }

    @Modifying
    @Query("delete from JTicket t where t.owner.id = :userId")
    void deleteByOwnerId(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from JTicket t where t.tier.event.id = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);

    /**
     * Clears the scan marker on tickets the user checked in. Both columns are cleared together
     * because {@code chk_ticket_checkin_pair} requires them to be null or set as a pair; the
     * ticket status is left untouched.
     */
    @Modifying
    @Query("update JTicket t set t.checkedBy = null, t.checkedInAt = null where t.checkedBy.id = :userId")
    void clearCheckInByUserId(@Param("userId") UUID userId);
}
