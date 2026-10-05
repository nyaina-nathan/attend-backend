package mg.improve.attend.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.repository.entities.JTicketTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JTicketTierRepository extends JpaRepository<JTicketTier, UUID> {

    List<JTicketTier> findByEvent_Id(UUID eventId);

    @Modifying
    @Query("delete from JTicketTier t where t.event.id = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);

    /**
     * Lowest tier price per event, for the public event list's {@code minPrice}. Events with no
     * tiers simply have no row in the result.
     */
    @Query("select t.event.id as eventId, min(t.price) as minPrice from JTicketTier t "
            + "where t.event.id in :eventIds group by t.event.id")
    List<MinPrice> findMinPriceByEventIds(@Param("eventIds") Collection<UUID> eventIds);

    interface MinPrice {
        UUID getEventId();

        BigDecimal getMinPrice();
    }
}
