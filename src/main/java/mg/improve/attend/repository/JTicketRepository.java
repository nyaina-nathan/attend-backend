package mg.improve.attend.repository;

import java.util.UUID;
import mg.improve.attend.repository.entities.JTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JTicketRepository extends JpaRepository<JTicket, UUID> {

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
