package mg.improve.attend.rest.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.InvitationCreateRequest;
import mg.improve.attend.domain.dto.request.InvitationUpdateRequest;
import mg.improve.attend.domain.dto.response.InvitationResponse;
import mg.improve.attend.domain.dto.response.PageInvitation;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.InvitationService;
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
 * Invitation endpoints. Like the other controllers, it only delegates to the service and
 * shapes the HTTP response.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping("/events/{eventId}/invitations")
    public ResponseEntity<InvitationResponse> createInvitation(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID eventId,
            @RequestBody InvitationCreateRequest request) {
        InvitationResponse created = invitationService.createInvitation(eventId, principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/invitations")
    public ResponseEntity<PageInvitation> listInvitations(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(name = "direction", defaultValue = "received") String direction,
            @RequestParam(name = "status", required = false) InvitationStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "createdAt,desc") String sort) {
        return ResponseEntity.ok(invitationService.listInvitations(
                principal.getId(), direction, status, page, size, sort));
    }

    @GetMapping("/invitations/{invitationId}")
    public ResponseEntity<InvitationResponse> getInvitation(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID invitationId) {
        return ResponseEntity.ok(invitationService.getInvitation(invitationId, principal.getId()));
    }

    @DeleteMapping("/invitations/{invitationId}")
    public ResponseEntity<Void> deleteInvitation(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID invitationId) {
        invitationService.deleteInvitation(invitationId, principal.getId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/invitations/{invitationId}")
    public ResponseEntity<InvitationResponse> respondToInvitation(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID invitationId,
            @RequestBody InvitationUpdateRequest request) {
        return ResponseEntity.ok(
                invitationService.respondToInvitation(invitationId, principal.getId(), request));
    }
}
