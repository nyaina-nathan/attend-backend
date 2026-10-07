package mg.improve.attend.rest.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.response.TicketResponse;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket endpoints. Like the other controllers, it only delegates to the service and shapes
 * the HTTP response.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping("/tiers/{tierId}/tickets")
    public ResponseEntity<TicketResponse> issueTicket(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID tierId) {
        TicketResponse issued = ticketService.issueTicket(tierId, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }
}
