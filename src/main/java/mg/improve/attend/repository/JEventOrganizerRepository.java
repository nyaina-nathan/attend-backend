package mg.improve.attend.repository;

import java.util.List;
import java.util.UUID;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.repository.entities.JEventOrganizer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JEventOrganizerRepository extends JpaRepository<JEventOrganizer, UUID> {

    @Query("select o.event.id from JEventOrganizer o where o.user.id = :userId and o.eventRole = :eventRole")
    List<UUID> findEventIdsByUserAndRole(@Param("userId") UUID userId, @Param("eventRole") EventRole eventRole);

    @Modifying
    @Query("delete from JEventOrganizer o where o.event.id = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);

    @Modifying
    @Query("delete from JEventOrganizer o where o.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
