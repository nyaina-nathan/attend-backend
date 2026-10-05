package mg.improve.attend.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.domain.dto.response.EventSummary;
import mg.improve.attend.domain.dto.response.PageEventSummary;
import mg.improve.attend.domain.dto.response.TierSummary;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.EventsMapper;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JEventOrganizerRepository;
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

    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JTicketTierRepository tiers;
    private final JTicketRepository tickets;
    private final JUsersRepository users;
    private final EventsMapper eventsMapper;
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
        JEvents event = events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found"));

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
                .countIssuedByTier(eventId, List.of(TicketStatus.VALID, TicketStatus.USED))
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
