package mg.improve.attend.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.response.AttendeeTicket;
import mg.improve.attend.domain.dto.response.CheckInResponse;
import mg.improve.attend.domain.dto.response.CheckedBy;
import mg.improve.attend.domain.dto.response.PageAttendeeTicket;
import mg.improve.attend.domain.dto.response.PageTicket;
import mg.improve.attend.domain.dto.response.TicketDetail;
import mg.improve.attend.domain.dto.response.TicketResponse;
import mg.improve.attend.domain.dto.response.TierTicketPresence;
import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ErrorDetail;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.TicketValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket use cases. Per-event role checks live here, never in the URL security config, which
 * only answers "is the caller authenticated?".
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    /** Statuses that consume capacity, and therefore block an issue when the tier is full. */
    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JTicketTierRepository tiers;
    private final JTicketRepository tickets;
    private final JUsersRepository users;
    private final TicketMapper ticketMapper;
    private final TicketValidator validator;

    /**
     * Issues a {@code VALID} ticket to the caller. Runs in one transaction and locks the tier
     * row ({@code SELECT ... FOR UPDATE}) so concurrent callers cannot oversell it. The event
     * must be open (not cancelled and not already ended); otherwise {@code 409}.
     */
    @Transactional
    public TicketResponse issueTicket(UUID tierId, UUID userId) {
        JTicketTier tier = tiers.findByIdForUpdate(tierId)
                .orElseThrow(() -> new NotFoundException("TIER_NOT_FOUND", "Tier not found"));
        JEvents event = tier.getEvent();

        if (event.getStatus() != EventStatus.OK || event.getEndTime().isBefore(Instant.now())) {
            throw new ConflictException("EVENT_NOT_OPEN", "Event is cancelled or has already ended");
        }

        long issued = tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES);
        if (issued >= tier.getCapacity()) {
            throw new ConflictException("SOLD_OUT", "No tickets remaining");
        }

        JUsers owner = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));

        JTicket ticket = new JTicket();
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(tier.getPrice());
        ticket.setTicketStatus(TicketStatus.VALID);
        ticket.setValidUntil(event.getEndTime());

        // saveAndFlush so the @CreationTimestamp value is set before mapping the response.
        JTicket saved = tickets.saveAndFlush(ticket);

        return TicketResponse.from(ticketMapper.toDomain(saved));
    }

    /**
     * Lists the caller's own tickets, earliest event first, optionally narrowed to a status and
     * to events that have not started yet. Paginated.
     */
    @Transactional(readOnly = true)
    public PageTicket listMyTickets(UUID userId, TicketStatus status, boolean upcoming, int page, int size) {
        validator.validateListQuery(page, size);

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.asc("tier.event.startTime"), Sort.Order.asc("createdAt")));
        Page<JTicket> result = tickets.findMyTickets(userId, status, upcoming, Instant.now(), pageable);

        List<TicketResponse> content = result.getContent().stream()
                .map(ticketMapper::toDomain)
                .map(TicketResponse::from)
                .toList();

        return new PageTicket(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                content);
    }

    /**
     * Returns a ticket to its owner or to any organizer of its event (resolved from ticket →
     * tier). Organizers additionally receive {@code owner} and {@code checkedBy}; a caller who
     * is neither gets {@code 403}.
     */
    @Transactional(readOnly = true)
    public TicketDetail getTicket(UUID ticketId, UUID userId) {
        JTicket ticket = loadTicketOr404(ticketId);

        UUID eventId = ticket.getTier().getEvent().getId();
        boolean organizer = organizers.existsByEvent_IdAndUser_Id(eventId, userId);
        boolean owner = ticket.getOwner().getId().equals(userId);
        if (!owner && !organizer) {
            throw new ForbiddenException("TICKET_FORBIDDEN",
                    "Caller is neither the ticket owner nor an organizer of this event");
        }

        return TicketDetail.from(ticketMapper.toDomain(ticket), organizer);
    }

    /**
     * Lists an event's tickets for its organizers, optionally narrowed by status, tier and a
     * case-insensitive owner name/username search. Paginated, earliest issuance first.
     */
    @Transactional(readOnly = true)
    public PageAttendeeTicket listEventTickets(UUID eventId, UUID userId, TicketStatus status,
            UUID tierId, String q, int page, int size) {
        loadEventOr404(eventId);
        requireOrganizer(eventId, userId);
        validator.validateListQuery(page, size);

        String pattern = (q == null || q.isBlank()) ? null : "%" + q.toLowerCase(Locale.ROOT) + "%";
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt")));
        Page<JTicket> result = tickets.findEventAttendees(eventId, status, tierId, pattern, pageable);

        List<AttendeeTicket> content = result.getContent().stream()
                .map(ticketMapper::toDomain)
                .map(AttendeeTicket::from)
                .toList();

        return new PageAttendeeTicket(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                content);
    }

    /**
     * Checks a ticket in. Organizers of the ticket's event only; the event is always resolved
     * from the ticket (ticket → tier → event), never from the request, so an organizer of event
     * A cannot scan a ticket of event B. The status transition is atomic, so two simultaneous
     * scans cannot both succeed.
     */
    @Transactional
    public CheckInResponse checkInTicket(UUID ticketId, UUID userId) {
        JTicket ticket = loadTicketOr404(ticketId);
        JEvents event = ticket.getTier().getEvent();
        requireOrganizer(event.getId(), userId);

        if (event.getStatus() != EventStatus.OK) {
            throw new ConflictException("EVENT_NOT_OPEN", "Event is cancelled or has already ended");
        }
        if (ticket.getTicketStatus() != TicketStatus.VALID) {
            throw notValidOrUsed(ticket);
        }

        Instant now = Instant.now();
        if (ticket.getValidUntil() != null && ticket.getValidUntil().isBefore(now)) {
            throw new ConflictException("TICKET_EXPIRED", "Ticket is past its validUntil time");
        }

        JUsers scanner = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));

        int updated = tickets.markCheckedIn(ticketId, TicketStatus.VALID, TicketStatus.USED, now, scanner);
        if (updated == 0) {
            throw notValidOrUsed(loadTicketOr404(ticketId));
        }

        Ticket domain = ticketMapper.toDomain(ticket);
        CheckedBy checkedBy = new CheckedBy(scanner.getId(), scanner.getUsername());
        return CheckInResponse.from(domain, TicketStatus.USED, now, checkedBy);
    }

    /**
     * Cancels a {@code VALID} ticket owned by the caller, freeing its capacity. Only the owner
     * may cancel; an already used or non-valid ticket is a {@code 409}.
     */
    @Transactional
    public TicketResponse cancelTicket(UUID ticketId, UUID userId) {
        JTicket ticket = loadTicketOr404(ticketId);
        if (!ticket.getOwner().getId().equals(userId)) {
            throw new ForbiddenException("NOT_THE_OWNER", "Caller is not the owner of this ticket");
        }
        if (ticket.getTicketStatus() != TicketStatus.VALID) {
            throw notValidOrUsed(ticket);
        }

        int updated = tickets.updateStatus(ticketId, List.of(TicketStatus.VALID), TicketStatus.CANCELLED);
        if (updated == 0) {
            throw notValidOrUsed(loadTicketOr404(ticketId));
        }

        Ticket domain = ticketMapper.toDomain(ticket);
        domain.setTicketStatus(TicketStatus.CANCELLED);
        return TicketResponse.from(domain);
    }

    /**
     * Refunds a {@code VALID} ticket. Organizers of the ticket's event only; the event is
     * resolved from the ticket. Only an unused ticket can be refunded.
     */
    @Transactional
    public TicketResponse refundTicket(UUID ticketId, UUID userId) {
        JTicket ticket = loadTicketOr404(ticketId);
        requireOrganizer(ticket.getTier().getEvent().getId(), userId);

        if (ticket.getTicketStatus() != TicketStatus.VALID) {
            throw new ConflictException("TICKET_NOT_REFUNDABLE",
                    "Ticket cannot be refunded in its current state");
        }

        int updated = tickets.updateStatus(ticketId, List.of(TicketStatus.VALID), TicketStatus.REFUNDED);
        if (updated == 0) {
            throw new ConflictException("TICKET_NOT_REFUNDABLE",
                    "Ticket cannot be refunded in its current state");
        }

        Ticket domain = ticketMapper.toDomain(ticket);
        domain.setTicketStatus(TicketStatus.REFUNDED);
        return TicketResponse.from(domain);
    }

    /**
     * Reports whether the caller already holds a ticket in the tier. Counts the
     * capacity-consuming statuses ({@code VALID}, {@code USED}), so cancelled and refunded
     * tickets do not count. The tier must exist; otherwise {@code 404}.
     */
    @Transactional(readOnly = true)
    public TierTicketPresence checkTierTicketPresence(UUID tierId, UUID userId) {
        if (!tiers.existsById(tierId)) {
            throw new NotFoundException("TIER_NOT_FOUND", "Tier not found");
        }

        boolean present = tickets.existsByTier_IdAndOwner_IdAndTicketStatusIn(
                tierId, userId, CAPACITY_STATUSES);
        return TierTicketPresence.of(present);
    }

    private JTicket loadTicketOr404(UUID ticketId) {
        return tickets.findDetailById(ticketId)
                .orElseThrow(() -> new NotFoundException("TICKET_NOT_FOUND", "Ticket not found"));
    }

    private JEvents loadEventOr404(UUID eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found"));
    }

    private void requireOrganizer(UUID eventId, UUID userId) {
        if (!organizers.existsByEvent_IdAndUser_Id(eventId, userId)) {
            throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
        }
    }

    /** Maps a non-valid ticket to the documented {@code 409}: used vs cancelled/refunded. */
    private ConflictException notValidOrUsed(JTicket ticket) {
        if (ticket.getTicketStatus() == TicketStatus.USED) {
            List<ErrorDetail> details = ticket.getCheckedInAt() == null
                    ? List.of()
                    : List.of(new ErrorDetail("checkedInAt", ticket.getCheckedInAt().toString()));
            return new ConflictException("TICKET_ALREADY_USED", "Ticket has already been used", details);
        }
        return new ConflictException("TICKET_NOT_VALID", "Ticket is cancelled or refunded");
    }
}
