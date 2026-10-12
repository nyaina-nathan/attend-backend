package mg.improve.attend.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.AddOrganizerRequest;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.request.EventUpdateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.domain.dto.response.EventStats;
import mg.improve.attend.domain.dto.response.EventSummary;
import mg.improve.attend.domain.dto.response.Organizer;
import mg.improve.attend.domain.dto.response.PageEventSummary;
import mg.improve.attend.domain.dto.response.TierStats;
import mg.improve.attend.domain.dto.response.TierSummary;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.EventOrganizerMapper;
import mg.improve.attend.domain.mapper.EventsMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventPaymentMethodRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.EventValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event lifecycle use cases. Per-event role checks live here, never in the URL security
 * config, which only answers "is the caller authenticated?".
 */
@Service
@RequiredArgsConstructor
public class EventService {

    /** Statuses that consume capacity, and therefore count as issued toward the event stats. */
    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JEventPaymentMethodRepository paymentMethods;
    private final JTicketTierRepository tiers;
    private final JTicketRepository tickets;
    private final JUsersRepository users;
    private final EventsMapper eventsMapper;
    private final EventOrganizerMapper organizerMapper;
    private final EventValidator validator;

    /**
     * Creates an event and registers the caller as its {@code CREATOR} organizer in one
     * transaction. The caller's id comes from the authenticated principal.
     */
    @Transactional
    public EventDetail createEvent(UUID userId, EventCreateRequest request) {
        validator.validate(request);

        JUsers creator = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));

        JEvents event = new JEvents();
        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setStartTime(request.startTime());
        event.setEndTime(request.endTime());
        event.setPublic(request.isPublic() == null || request.isPublic());
        event.setLocation(request.location());
        event.setLatitude(request.latitude());
        event.setLongitude(request.longitude());
        if (request.priceUnit() != null) {
            event.setPriceUnit(request.priceUnit());
        }
        event.setStatus(EventStatus.OK);

        // saveAndFlush so the @CreationTimestamp/@UpdateTimestamp values are set before mapping.
        JEvents saved = events.saveAndFlush(event);

        JEventOrganizer organizer = new JEventOrganizer();
        organizer.setUser(creator);
        organizer.setEvent(saved);
        organizer.setEventRole(EventRole.CREATOR);
        organizers.save(organizer);

        Events domain = eventsMapper.toDomain(saved);
        return EventDetail.from(domain, EventRole.CREATOR, List.of());
    }

    /**
     * Event detail. Public events are visible to everyone; private events only to their
     * organizers. Anonymous callers get {@code 401} for a private event, authenticated
     * non-organizers get {@code 403}.
     */
    @Transactional(readOnly = true)
    public EventDetail getEvent(UUID eventId, UUID userId) {
        JEvents event = loadEventOr404(eventId);

        EventRole myRole = userId == null ? null
                : organizers.findByEvent_IdAndUser_Id(eventId, userId)
                        .map(JEventOrganizer::getEventRole)
                        .orElse(null);

        if (!event.isPublic()) {
            if (userId == null) {
                throw new UnauthorizedException("UNAUTHORIZED", "Authentication required");
            }
            if (myRole == null) {
                throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
            }
        }

        return EventDetail.from(eventsMapper.toDomain(event), myRole, tiersFor(eventId));
    }

    private List<TierSummary> tiersFor(UUID eventId) {
        List<JTicketTier> eventTiers = tiers.findByEvent_Id(eventId);
        if (eventTiers.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> issuedByTier = tickets
                .countIssuedByTier(eventId, CAPACITY_STATUSES)
                .stream()
                .collect(Collectors.toMap(
                        JTicketRepository.TierIssued::getTierId,
                        JTicketRepository.TierIssued::getIssued));

        return eventTiers.stream()
                .map(tier -> {
                    long issued = issuedByTier.getOrDefault(tier.getId(), 0L);
                    int remaining = Math.max(0, tier.getCapacity() - (int) issued);
                    return new TierSummary(tier.getId(), tier.getTierName(), tier.getPrice(),
                            tier.getCapacity(), remaining);
                })
                .toList();
    }

    /**
     * Sales and attendance statistics for an event. Organizers only. Each tier of the event is
     * reported (zero-activity ones included), ordered by name. Only {@code VALID}/{@code USED}
     * tickets count as issued and toward revenue; cancelled and refunded are excluded.
     */
    @Transactional(readOnly = true)
    public EventStats getEventStats(UUID eventId, UUID userId) {
        JEvents event = loadEventOr404(eventId);
        requireOrganizer(eventId, userId);

        List<JTicketTier> eventTiers = tiers.findByEvent_Id(eventId);
        Map<UUID, JTicketRepository.TierAggregate> aggregates = tickets
                .aggregateByTier(eventId, CAPACITY_STATUSES, TicketStatus.USED)
                .stream()
                .collect(Collectors.toMap(
                        JTicketRepository.TierAggregate::getTierId,
                        aggregate -> aggregate));

        List<TierStats> byTier = eventTiers.stream()
                .sorted(Comparator.comparing(JTicketTier::getTierName))
                .map(tier -> {
                    JTicketRepository.TierAggregate aggregate = aggregates.get(tier.getId());
                    long issued = aggregate == null ? 0L : aggregate.getIssued();
                    long checkedIn = aggregate == null ? 0L : aggregate.getCheckedIn();
                    BigDecimal revenue = aggregate == null ? BigDecimal.ZERO : aggregate.getRevenue();
                    return new TierStats(tier.getId(), tier.getTierName(), issued, checkedIn, revenue);
                })
                .toList();

        int totalCapacity = eventTiers.stream().mapToInt(JTicketTier::getCapacity).sum();
        long ticketsIssued = byTier.stream().mapToLong(TierStats::issued).sum();
        long ticketsCheckedIn = byTier.stream().mapToLong(TierStats::checkedIn).sum();
        BigDecimal revenue = byTier.stream()
                .map(TierStats::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new EventStats(eventId, event.getPriceUnit(), totalCapacity, ticketsIssued,
                ticketsCheckedIn, revenue, byTier);
    }

    /**
     * Updates an event. Creator only. Only the non-null fields of the request are applied.
     */
    @Transactional
    public EventDetail updateEvent(UUID eventId, UUID userId, EventUpdateRequest request) {
        JEvents event = requireCreator(eventId, userId);
        validator.validateUpdate(request, event.getStartTime(), event.getEndTime(),
                event.getLatitude(), event.getLongitude());

        if (request.title() != null) {
            event.setTitle(request.title());
        }
        if (request.description() != null) {
            event.setDescription(request.description());
        }
        if (request.startTime() != null) {
            event.setStartTime(request.startTime());
        }
        if (request.endTime() != null) {
            event.setEndTime(request.endTime());
        }
        if (request.isPublic() != null) {
            event.setPublic(request.isPublic());
        }
        if (request.location() != null) {
            event.setLocation(request.location());
        }
        if (request.latitude() != null) {
            event.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            event.setLongitude(request.longitude());
        }
        if (request.priceUnit() != null) {
            event.setPriceUnit(request.priceUnit());
        }
        if (request.status() != null) {
            event.setStatus(request.status());
        }

        JEvents saved = events.saveAndFlush(event);
        return EventDetail.from(eventsMapper.toDomain(saved), EventRole.CREATOR, tiersFor(eventId));
    }

    /**
     * Deletes an event. Creator only, and only when it has no tickets (cancel instead).
     */
    @Transactional
    public void deleteEvent(UUID eventId, UUID userId) {
        requireCreator(eventId, userId);

        if (tickets.existsByTier_Event_Id(eventId)) {
            throw new ConflictException("EVENT_HAS_TICKETS", "Tickets exist for this event; cancel it instead");
        }

        // No tickets, so tiers, payment methods and organizer rows can go before the event.
        organizers.deleteByEventId(eventId);
        paymentMethods.deleteByEventId(eventId);
        tiers.deleteByEventId(eventId);
        events.deleteById(eventId);
    }

    /**
     * Cancels an event. Creator only. Tickets are left untouched (check-in rejects tickets of a
     * cancelled event).
     */
    @Transactional
    public EventDetail cancelEvent(UUID eventId, UUID userId) {
        JEvents event = requireCreator(eventId, userId);
        event.setStatus(EventStatus.CANCELLED);

        JEvents saved = events.saveAndFlush(event);
        return EventDetail.from(eventsMapper.toDomain(saved), EventRole.CREATOR, tiersFor(eventId));
    }

    /**
     * Lists the organizers of an event. Any organizer of the event ({@code CREATOR} or
     * {@code ADMIN}) may read the list; an authenticated non-organizer gets {@code 403}.
     */
    @Transactional(readOnly = true)
    public List<Organizer> listOrganizers(UUID eventId, UUID userId) {
        loadEventOr404(eventId);

        List<JEventOrganizer> eventOrganizers = organizers.findByEvent_Id(eventId);
        boolean callerIsOrganizer = eventOrganizers.stream()
                .anyMatch(organizer -> organizer.getUser().getId().equals(userId));
        if (!callerIsOrganizer) {
            throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
        }

        return eventOrganizers.stream()
                .map(organizerMapper::toDomain)
                .map(Organizer::from)
                .toList();
    }

    /**
     * Adds an {@code ADMIN} organizer to an event. Creator only. The target user is resolved
     * by {@code username} or {@code email}.
     */
    @Transactional
    public Organizer addOrganizer(UUID eventId, UUID userId, AddOrganizerRequest request) {
        JEvents event = requireCreator(eventId, userId);
        validator.validate(request);

        JUsers target = resolveUser(request);
        if (organizers.existsByEvent_IdAndUser_Id(eventId, target.getId())) {
            throw new ConflictException("ALREADY_ORGANIZER", "User is already an organizer of this event");
        }

        JEventOrganizer organizer = new JEventOrganizer();
        organizer.setUser(target);
        organizer.setEvent(event);
        organizer.setEventRole(EventRole.ADMIN);
        JEventOrganizer saved = organizers.saveAndFlush(organizer);

        return Organizer.from(organizerMapper.toDomain(saved));
    }

    /**
     * Removes an {@code ADMIN} organizer from an event. Creator only, and the {@code CREATOR}
     * row itself can never be removed ({@code 409}).
     */
    @Transactional
    public void removeOrganizer(UUID eventId, UUID userId, UUID organizerId) {
        requireCreator(eventId, userId);

        JEventOrganizer organizer = organizers.findByIdAndEvent_Id(organizerId, eventId)
                .orElseThrow(() -> new NotFoundException("ORGANIZER_NOT_FOUND", "Organizer not found"));
        if (organizer.getEventRole() == EventRole.CREATOR) {
            throw new ConflictException("CANNOT_REMOVE_CREATOR", "The creator row cannot be removed");
        }

        organizers.delete(organizer);
    }

    private JUsers resolveUser(AddOrganizerRequest request) {
        if (request.username() != null && !request.username().isBlank()) {
            return users.findByUsername(request.username())
                    .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
        }
        return users.findByEmail(request.email())
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
    }

    private JEvents loadEventOr404(UUID eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found"));
    }

    private JEvents requireCreator(UUID eventId, UUID userId) {
        JEvents event = loadEventOr404(eventId);
        if (!organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR)) {
            throw new ForbiddenException("NOT_THE_CREATOR", "Caller is not the creator of this event");
        }
        return event;
    }

    private void requireOrganizer(UUID eventId, UUID userId) {
        if (!organizers.existsByEvent_IdAndUser_Id(eventId, userId)) {
            throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
        }
    }

    /**
     * Event discovery. Anonymous callers see {@code isPublic = true} events. An authenticated
     * caller may pass {@code isMine = true} to see only the events they organize, optionally
     * narrowed by {@code isPublic} (e.g. {@code isMine=true&isPublic=false} for private ones).
     */
    @Transactional(readOnly = true)
    public PageEventSummary listEvents(String q, Instant from, Instant to, EventStatus status,
            boolean isMine, Boolean isPublic, UUID userId, int page, int size, String sort) {
        Sort resolvedSort = validator.validateListQuery(page, size, sort);
        Pageable pageable = PageRequest.of(page, size, resolvedSort);

        Page<JEvents> eventsPage = events.findAll(filter(q, from, to, status, isMine, isPublic, userId), pageable);
        Map<UUID, BigDecimal> minPrices = minPricesByEvent(eventsPage.getContent());
        List<EventSummary> content = eventsPage.getContent().stream()
                .map(entity -> EventSummary.from(eventsMapper.toDomain(entity), minPrices.get(entity.getId())))
                .toList();

        return new PageEventSummary(
                eventsPage.getNumber(),
                eventsPage.getSize(),
                eventsPage.getTotalElements(),
                eventsPage.getTotalPages(),
                content);
    }

    private static Specification<JEvents> filter(String q, Instant from, Instant to, EventStatus status,
            boolean isMine, Boolean isPublic, UUID userId) {
        // Anonymous callers cannot have "mine" events, so they fall back to the public list.
        boolean mine = isMine && userId != null;
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), status));

            if (mine) {
                Subquery<UUID> organizerExists = query.subquery(UUID.class);
                Root<JEventOrganizer> organizer = organizerExists.from(JEventOrganizer.class);
                organizerExists.select(organizer.get("event").get("id"))
                        .where(cb.equal(organizer.get("event").get("id"), root.get("id")),
                                cb.equal(organizer.get("user").get("id"), userId));
                predicates.add(cb.exists(organizerExists));
                if (isPublic != null) {
                    predicates.add(cb.equal(root.get("isPublic"), isPublic));
                }
            } else {
                predicates.add(cb.isTrue(root.get("isPublic")));
            }

            if (q != null && !q.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + q.toLowerCase(Locale.ROOT) + "%"));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startTime"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startTime"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Map<UUID, BigDecimal> minPricesByEvent(List<JEvents> content) {
        if (content.isEmpty()) {
            return Map.of();
        }
        List<UUID> eventIds = content.stream().map(JEvents::getId).toList();
        return tiers.findMinPriceByEventIds(eventIds).stream()
                .collect(Collectors.toMap(
                        JTicketTierRepository.MinPrice::getEventId,
                        JTicketTierRepository.MinPrice::getMinPrice));
    }
}
