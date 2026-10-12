package mg.improve.attend.repository;

import java.util.List;
import java.util.UUID;
import mg.improve.attend.repository.entities.JTicketOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JTicketOrderRepository extends JpaRepository<JTicketOrder, UUID> {

    List<JTicketOrder> findByEvent_Id(UUID eventId);

    boolean existsByPaymentMethod_Id(UUID paymentMethodId);

    @Modifying
    @Query("delete from JTicketOrder o where o.event.id = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);

    @Modifying
    @Query("delete from JTicketOrder o where o.owner.id = :userId")
    void deleteByOwnerId(@Param("userId") UUID userId);
}
