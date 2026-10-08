package mg.improve.attend.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.repository.entities.JEvents;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JEventsRepository extends JpaRepository<JEvents, UUID>, JpaSpecificationExecutor<JEvents> {

    /**
     * Dashboard organizer totals: how many events the caller organizes in total (any status)
     * and how many of those are upcoming ({@code endTime > now} and {@code OK} or
     * {@code POSTPONED}).
     */
    @Query("select count(e) as eventsTotal, "
            + "count(case when e.endTime > :now and e.status in :upcomingStatuses then 1 end) as eventsUpcoming "
            + "from JEvents e "
            + "where exists (select o.event.id from JEventOrganizer o "
            + "where o.event = e and o.user.id = :userId)")
    OrganizingCounters countOrganizing(@Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("upcomingStatuses") Collection<EventStatus> upcomingStatuses);

    /**
     * The caller's soonest upcoming organized event, with the caller's role, so the dashboard
     * can report {@code myRole} without a second lookup. Callers pass a size-1 page.
     */
    @Query("select e as event, o.eventRole as eventRole "
            + "from JEvents e, JEventOrganizer o "
            + "where o.event = e and o.user.id = :userId "
            + "and e.endTime > :now and e.status in :upcomingStatuses "
            + "order by e.startTime asc, e.id asc")
    List<NextOrganizingEventRow> findNextOrganizingEvent(@Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("upcomingStatuses") Collection<EventStatus> upcomingStatuses,
            Pageable pageable);

    /**
     * Dashboard {@code NO_TIERS} candidates: the caller's upcoming {@code OK} events that start
     * within the deadline and have no tiers at all.
     */
    @Query("select e.id as eventId, e.title as eventTitle, e.startTime as startTime "
            + "from JEvents e "
            + "where e.status = :status and e.endTime > :now and e.startTime <= :deadline "
            + "and exists (select o.event.id from JEventOrganizer o "
            + "where o.event = e and o.user.id = :userId) "
            + "and not exists (select tier.id from JTicketTier tier where tier.event = e) "
            + "order by e.startTime asc")
    List<AttentionEventRow> findNoTierAttention(@Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("deadline") Instant deadline,
            @Param("status") EventStatus status);

    /**
     * Dashboard {@code POSTPONED} candidates: the caller's postponed events that still have at
     * least one counted ticket, with how many they have. The inner join already drops events
     * without counted tickets.
     */
    @Query("select e.id as eventId, e.title as eventTitle, e.startTime as startTime, "
            + "count(t) as ticketsIssued "
            + "from JEvents e join JTicket t on t.tier.event = e "
            + "where e.status = :status and t.ticketStatus in :statuses "
            + "and exists (select o.event.id from JEventOrganizer o "
            + "where o.event = e and o.user.id = :userId) "
            + "group by e.id, e.title, e.startTime "
            + "order by e.startTime asc")
    List<PostponedAttentionRow> findPostponedAttention(@Param("userId") UUID userId,
            @Param("status") EventStatus status,
            @Param("statuses") Collection<TicketStatus> statuses);

    /** Projection of {@link #countOrganizing(UUID, Instant, Collection)}. */
    interface OrganizingCounters {
        long getEventsTotal();

        long getEventsUpcoming();
    }

    /** Projection of {@link #findNextOrganizingEvent(UUID, Instant, Collection, Pageable)}. */
    interface NextOrganizingEventRow {
        JEvents getEvent();

        EventRole getEventRole();
    }

    /** Projection of {@link #findNoTierAttention(UUID, Instant, Instant, EventStatus)}. */
    interface AttentionEventRow {
        UUID getEventId();

        String getEventTitle();

        Instant getStartTime();
    }

    /** Projection of {@link #findPostponedAttention(UUID, EventStatus, Collection)}. */
    interface PostponedAttentionRow {
        UUID getEventId();

        String getEventTitle();

        Instant getStartTime();

        long getTicketsIssued();
    }
}
