package mg.improve.attend.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.response.PageTicket;
import mg.improve.attend.domain.dto.response.TicketResponse;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.NotFoundException;
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
}
