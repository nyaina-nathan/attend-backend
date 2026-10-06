package mg.improve.attend.rest.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.AddOrganizerRequest;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.request.EventUpdateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.domain.dto.response.Organizer;
import mg.improve.attend.domain.dto.response.PageEventSummary;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.EventService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Event lifecycle endpoints. Like the other controllers, it only delegates to the service
 * and shapes the HTTP response.
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventDetail> createEvent(@AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody EventCreateRequest request) {
        EventDetail created = eventService.createEvent(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventDetail> getEvent(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId) {
        UUID userId = principal == null ? null : principal.getId();
        return ResponseEntity.ok(eventService.getEvent(eventId, userId));
    }

    @PatchMapping("/{eventId}")
    public ResponseEntity<EventDetail> updateEvent(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId, @RequestBody EventUpdateRequest request) {
        return ResponseEntity.ok(eventService.updateEvent(eventId, principal.getId(), request));
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> deleteEvent(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId) {
        eventService.deleteEvent(eventId, principal.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{eventId}/cancel")
    public ResponseEntity<EventDetail> cancelEvent(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.cancelEvent(eventId, principal.getId()));
    }

    @GetMapping("/{eventId}/organizers")
    public ResponseEntity<List<Organizer>> listOrganizers(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.listOrganizers(eventId, principal.getId()));
    }

    @PostMapping("/{eventId}/organizers")
    public ResponseEntity<Organizer> addOrganizer(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId, @RequestBody AddOrganizerRequest request) {
        Organizer created = eventService.addOrganizer(eventId, principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{eventId}/organizers/{organizerId}")
    public ResponseEntity<Void> removeOrganizer(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId, @PathVariable UUID organizerId) {
        eventService.removeOrganizer(eventId, principal.getId(), organizerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<PageEventSummary> listEvents(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "status", defaultValue = "OK") EventStatus status,
            @RequestParam(name = "isMine", defaultValue = "false") boolean isMine,
            @RequestParam(name = "isPublic", required = false) Boolean isPublic,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "startTime,asc") String sort) {
        UUID userId = principal == null ? null : principal.getId();
        return ResponseEntity.ok(
                eventService.listEvents(q, from, to, status, isMine, isPublic, userId, page, size, sort));
    }
}
