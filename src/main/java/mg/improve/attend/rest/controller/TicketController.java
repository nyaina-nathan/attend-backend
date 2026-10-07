package mg.improve.attend.rest.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.response.PageTicket;
import mg.improve.attend.domain.dto.response.TicketDetail;
import mg.improve.attend.domain.dto.response.TicketResponse;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/tickets/me")
    public ResponseEntity<PageTicket> listMyTickets(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(name = "status", required = false) TicketStatus status,
            @RequestParam(name = "upcoming", defaultValue = "false") boolean upcoming,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(
                ticketService.listMyTickets(principal.getId(), status, upcoming, page, size));
    }

    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<TicketDetail> getTicket(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID ticketId) {
        return ResponseEntity.ok(ticketService.getTicket(ticketId, principal.getId()));
    }
}
