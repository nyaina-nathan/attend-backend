package mg.improve.attend.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.mapper.EventsMapper;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.EventValidator;
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
}
