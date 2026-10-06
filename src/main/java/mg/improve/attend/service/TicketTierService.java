package mg.improve.attend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.TierCreateRequest;
import mg.improve.attend.domain.dto.request.TierUpdateRequest;
import mg.improve.attend.domain.dto.response.Tier;
import mg.improve.attend.domain.entity.TicketTier;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketTierMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.validator.TicketTierValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket tier use cases. Per-event role checks live here, never in the URL security config,
 * which only answers "is the caller authenticated?".
 */
@Service
@RequiredArgsConstructor
public class TicketTierService {

    /** Statuses that consume capacity, and therefore count toward {@code remaining}. */
    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JTicketTierRepository tiers;
    private final JTicketRepository tickets;
    private final TicketTierMapper tierMapper;
    private final TicketTierValidator validator;

    /**
     * Lists the tiers of an event. Public events are visible to everyone; a private event's
     * tiers only to its organizers. Anonymous callers get {@code 401} for a private event,
     * authenticated non-organizers get {@code 403}.
     */
    @Transactional(readOnly = true)
    public List<Tier> listTiers(UUID eventId, UUID userId) {
        JEvents event = loadEventOr404(eventId);

        if (!event.isPublic()) {
            if (userId == null) {
                throw new UnauthorizedException("UNAUTHORIZED", "Authentication required");
            }
            if (!organizers.existsByEvent_IdAndUser_Id(eventId, userId)) {
                throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
            }
        }

        List<JTicketTier> eventTiers = tiers.findByEvent_Id(eventId);
        if (eventTiers.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> issuedByTier = tickets.countIssuedByTier(eventId, CAPACITY_STATUSES).stream()
                .collect(Collectors.toMap(
                        JTicketRepository.TierIssued::getTierId,
                        JTicketRepository.TierIssued::getIssued));

        return eventTiers.stream()
                .map(tier -> toTier(tier, issuedByTier.getOrDefault(tier.getId(), 0L)))
                .toList();
    }

    /**
     * Creates a tier on an event. Organizers only. The tier name must be unique within the
     * event.
     */
    @Transactional
    public Tier createTier(UUID eventId, UUID userId, TierCreateRequest request) {
        JEvents event = loadEventOr404(eventId);
        requireOrganizer(eventId, userId);
        validator.validateCreate(request);

        if (tiers.existsByEvent_IdAndTierName(eventId, request.tierName())) {
            throw new ConflictException("TIER_NAME_TAKEN", "Tier name already used for this event");
        }

        JTicketTier tier = new JTicketTier();
        tier.setEvent(event);
        tier.setTierName(request.tierName());
        tier.setPrice(request.price());
        tier.setCapacity(request.capacity());
        JTicketTier saved = tiers.saveAndFlush(tier);

        return toTier(saved, 0L);
    }

    /**
     * Updates a tier. Organizers only. Only the non-null fields are applied, the name must
     * stay unique, and {@code capacity} cannot drop below the tickets already issued.
     */
    @Transactional
    public Tier updateTier(UUID tierId, UUID userId, TierUpdateRequest request) {
        JTicketTier tier = loadTierOr404(tierId);
        UUID eventId = tier.getEvent().getId();
        requireOrganizer(eventId, userId);
        validator.validateUpdate(request);

        if (request.tierName() != null
                && tiers.existsByEvent_IdAndTierNameAndIdNot(eventId, request.tierName(), tierId)) {
            throw new ConflictException("TIER_NAME_TAKEN", "Tier name already used for this event");
        }

        long issued = tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES);
        if (request.capacity() != null && request.capacity() < issued) {
            throw new ConflictException("CAPACITY_BELOW_ISSUED",
                    "Capacity cannot be below the number of tickets already issued");
        }

        if (request.tierName() != null) {
            tier.setTierName(request.tierName());
        }
        if (request.price() != null) {
            tier.setPrice(request.price());
        }
        if (request.capacity() != null) {
            tier.setCapacity(request.capacity());
        }
        JTicketTier saved = tiers.saveAndFlush(tier);

        return toTier(saved, issued);
    }

    /**
     * Deletes a tier. Organizers only, and only when it has no tickets.
     */
    @Transactional
    public void deleteTier(UUID tierId, UUID userId) {
        JTicketTier tier = loadTierOr404(tierId);
        requireOrganizer(tier.getEvent().getId(), userId);

        if (tickets.existsByTier_Id(tierId)) {
            throw new ConflictException("TIER_HAS_TICKETS", "Tickets exist for this tier");
        }

        tiers.delete(tier);
    }

    private void requireOrganizer(UUID eventId, UUID userId) {
        if (!organizers.existsByEvent_IdAndUser_Id(eventId, userId)) {
            throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
        }
    }

    private JEvents loadEventOr404(UUID eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found"));
    }

    private JTicketTier loadTierOr404(UUID tierId) {
        return tiers.findById(tierId)
                .orElseThrow(() -> new NotFoundException("TIER_NOT_FOUND", "Tier not found"));
    }

    private Tier toTier(JTicketTier entity, long issued) {
        TicketTier domain = tierMapper.toDomain(entity);
        int remaining = Math.max(0, domain.getCapacity() - (int) issued);
        return Tier.from(domain, remaining);
    }
}
