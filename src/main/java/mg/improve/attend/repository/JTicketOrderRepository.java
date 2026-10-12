package mg.improve.attend.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.enums.OrderStatus;
import mg.improve.attend.repository.entities.JTicketOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JTicketOrderRepository extends JpaRepository<JTicketOrder, UUID> {

    List<JTicketOrder> findByEvent_Id(UUID eventId);

    boolean existsByPaymentMethod_Id(UUID paymentMethodId);

    boolean existsByPaymentMethod_IdAndTransactionReference(UUID paymentMethodId, String transactionReference);

    /** Loads one order with everything needed for its response in a single query. */
    @EntityGraph(attributePaths = {"owner", "paymentMethod", "reviewedBy", "items", "items.tier"})
    @Query("select o from JTicketOrder o where o.id = :id")
    Optional<JTicketOrder> findDetailById(@Param("id") UUID id);

    /**
     * Loads an order with a row lock ({@code SELECT ... FOR UPDATE}) so two simultaneous reviews
     * of the same order cannot both pass the {@code PENDING} check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from JTicketOrder o where o.id = :id")
    Optional<JTicketOrder> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"owner", "paymentMethod", "reviewedBy"})
    @Query("select o from JTicketOrder o where o.owner.id = :ownerId "
            + "and o.orderStatus = coalesce(:status, o.orderStatus)")
    Page<JTicketOrder> findMyOrders(@Param("ownerId") UUID ownerId,
            @Param("status") OrderStatus status,
            Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "paymentMethod", "reviewedBy"})
    @Query("select o from JTicketOrder o where o.event.id = :eventId "
            + "and o.orderStatus = coalesce(:status, o.orderStatus)")
    Page<JTicketOrder> findEventOrders(@Param("eventId") UUID eventId,
            @Param("status") OrderStatus status,
            Pageable pageable);

    @Modifying
    @Query("delete from JTicketOrder o where o.event.id = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);

    @Modifying
    @Query("delete from JTicketOrder o where o.owner.id = :userId")
    void deleteByOwnerId(@Param("userId") UUID userId);
}
