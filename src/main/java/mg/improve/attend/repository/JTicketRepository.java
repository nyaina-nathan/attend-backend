package mg.improve.attend.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JUsers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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

    /**
     * A page of the given owner's tickets, optionally narrowed by status and to events that have
     * not started yet. The associations needed to build the response are fetched eagerly so the
     * whole page can be mapped without an N+1.
     */
    // coalesce (rather than ":status is null or ...") so Postgres can infer the enum type of
    // the optional parameter from the column it is compared to.
    @EntityGraph(attributePaths = {"owner", "checkedBy", "tier", "tier.event"})
    @Query("select t from JTicket t where t.owner.id = :ownerId "
            + "and t.ticketStatus = coalesce(:status, t.ticketStatus) "
            + "and (:upcoming = false or t.tier.event.startTime > :now)")
    Page<JTicket> findMyTickets(@Param("ownerId") UUID ownerId,
            @Param("status") TicketStatus status,
            @Param("upcoming") boolean upcoming,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * A page of an event's tickets for the attendee list, optionally narrowed by status, tier
     * and a case-insensitive owner name/username search. The owner and tier needed by the
     * response are fetched eagerly so the page maps without an N+1.
     */
    // coalesce (rather than ":param is null or ...") so Postgres can infer the enum/UUID types
    // of the optional parameters from the columns they are compared to.
    @EntityGraph(attributePaths = {"owner", "tier"})
    @Query("select t from JTicket t where t.tier.event.id = :eventId "
            + "and t.ticketStatus = coalesce(:status, t.ticketStatus) "
            + "and t.tier.id = coalesce(:tierId, t.tier.id) "
            + "and (:q is null or lower(t.owner.fullName) like :q or lower(t.owner.username) like :q)")
    Page<JTicket> findEventAttendees(@Param("eventId") UUID eventId,
            @Param("status") TicketStatus status,
            @Param("tierId") UUID tierId,
            @Param("q") String q,
            Pageable pageable);

    /**
     * Loads a ticket with the associations needed to build its detail response in one query.
     */
    @EntityGraph(attributePaths = {"owner", "checkedBy", "tier", "tier.event"})
    @Query("select t from JTicket t where t.id = :id")
    Optional<JTicket> findDetailById(@Param("id") UUID id);

    /**
     * Atomically checks a ticket in only while it is still in {@code allowedStatus}, setting the
     * scan marker as a pair so {@code chk_ticket_checkin_pair} holds. Returns the number of rows
     * updated (0 when the ticket was no longer in that status, e.g. two simultaneous scans).
     */
    @Modifying(clearAutomatically = true)
    @Query("update JTicket t set t.ticketStatus = :newStatus, t.checkedInAt = :checkedInAt, "
            + "t.checkedBy = :scanner where t.id = :ticketId and t.ticketStatus = :allowedStatus")
    int markCheckedIn(@Param("ticketId") UUID ticketId,
            @Param("allowedStatus") TicketStatus allowedStatus,
            @Param("newStatus") TicketStatus newStatus,
            @Param("checkedInAt") Instant checkedInAt,
            @Param("scanner") JUsers scanner);

    /**
     * Atomically moves a ticket's status while it is in one of {@code allowedStatuses}. Returns
     * the number of rows updated (0 when the ticket was not in an allowed status).
     */
    @Modifying(clearAutomatically = true)
    @Query("update JTicket t set t.ticketStatus = :newStatus "
            + "where t.id = :ticketId and t.ticketStatus in :allowedStatuses")
    int updateStatus(@Param("ticketId") UUID ticketId,
            @Param("allowedStatuses") Collection<TicketStatus> allowedStatuses,
            @Param("newStatus") TicketStatus newStatus);

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
