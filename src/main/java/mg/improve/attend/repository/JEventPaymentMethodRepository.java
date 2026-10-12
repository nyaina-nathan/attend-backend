package mg.improve.attend.repository;

import java.util.List;
import java.util.UUID;
import mg.improve.attend.repository.entities.JEventPaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JEventPaymentMethodRepository extends JpaRepository<JEventPaymentMethod, UUID> {

    List<JEventPaymentMethod> findByEvent_Id(UUID eventId);

    @Modifying
    @Query("delete from JEventPaymentMethod m where m.event.id = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);
}
