package mg.improve.attend.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.repository.entities.JTicketTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JTicketTierRepository extends JpaRepository<JTicketTier, UUID> {

    List<JTicketTier> findByEvent_Id(UUID eventId);

    /**
     * Loads a tier with a row lock ({@code SELECT ... FOR UPDATE}) so ticket issuance cannot
     * oversell its capacity under concurrent calls.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from JTicketTier t where t.id = :id")
    Optional<JTicketTier> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByEvent_IdAndTierName(UUID eventId, String tierName);

    boolean existsByEvent_IdAndTierNameAndIdNot(UUID eventId, String tierName, UUID id);

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

    /**
     * Dashboard low-stock candidates: every tier of the caller's upcoming {@code OK} events,
     * with the number of counted tickets ({@code VALID}/{@code USED}) already issued. The
     * {@code remaining = capacity - issued} filter is applied by the caller, matching
     * {@code GET /events/{eventId}}.
     */
    @Query("select tier.id as tierId, tier.tierName as tierName, e.id as eventId, "
            + "e.title as eventTitle, e.startTime as startTime, tier.capacity as capacity, "
            + "count(t) as issued "
            + "from JTicketTier tier join tier.event e "
            + "left join JTicket t on t.tier = tier and t.ticketStatus in :statuses "
            + "where e.status = :status and e.endTime > :now "
            + "and exists (select o.event.id from JEventOrganizer o "
            + "where o.event = e and o.user.id = :userId) "
            + "group by tier.id, tier.tierName, e.id, e.title, e.startTime, tier.capacity")
    List<LowStockTier> findLowStockTiers(@Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("status") EventStatus status,
            @Param("statuses") Collection<TicketStatus> statuses);

    /** Projection of {@link #findLowStockTiers(UUID, Instant, EventStatus, Collection)}. */
    interface LowStockTier {
        UUID getTierId();

        String getTierName();

        UUID getEventId();

        String getEventTitle();

        Instant getStartTime();

        int getCapacity();

        long getIssued();
    }

    interface MinPrice {
        UUID getEventId();

        BigDecimal getMinPrice();
    }
}
