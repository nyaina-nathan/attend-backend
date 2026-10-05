package mg.improve.attend.rest.controller;

import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.EventService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
