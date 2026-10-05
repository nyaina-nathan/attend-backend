package mg.improve.attend.repository;

import java.util.UUID;
import mg.improve.attend.repository.entities.JTicketTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JTicketTierRepository extends JpaRepository<JTicketTier, UUID> {

    @Modifying
    @Query("delete from JTicketTier t where t.event.id = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);
}
