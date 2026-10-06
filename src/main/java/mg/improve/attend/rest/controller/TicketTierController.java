package mg.improve.attend.rest.controller;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.TierCreateRequest;
import mg.improve.attend.domain.dto.request.TierUpdateRequest;
import mg.improve.attend.domain.dto.response.Tier;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.TicketTierService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket tier endpoints. Like the other controllers, it only delegates to the service and
 * shapes the HTTP response. The event-scoped reads/creates and the tier-scoped mutations live
 * in the same controller so the tier resource is handled in one place.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TicketTierController {

    private final TicketTierService ticketTierService;

    @GetMapping("/events/{eventId}/tiers")
    public ResponseEntity<List<Tier>> listTiers(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId) {
        UUID userId = principal == null ? null : principal.getId();
        return ResponseEntity.ok(ticketTierService.listTiers(eventId, userId));
    }

    @PostMapping("/events/{eventId}/tiers")
    public ResponseEntity<Tier> createTier(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId, @RequestBody TierCreateRequest request) {
        Tier created = ticketTierService.createTier(eventId, principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/tiers/{tierId}")
    public ResponseEntity<Tier> updateTier(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID tierId, @RequestBody TierUpdateRequest request) {
        return ResponseEntity.ok(ticketTierService.updateTier(tierId, principal.getId(), request));
    }

    @DeleteMapping("/tiers/{tierId}")
    public ResponseEntity<Void> deleteTier(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID tierId) {
        ticketTierService.deleteTier(tierId, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
