package mg.improve.attend.rest.controller;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.PaymentMethodCreateRequest;
import mg.improve.attend.domain.dto.request.PaymentMethodUpdateRequest;
import mg.improve.attend.domain.dto.response.PaymentMethodResponse;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.EventPaymentMethodService;
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
 * Payment method endpoints. Like the other controllers, it only delegates to the service and
 * shapes the HTTP response. The event-scoped read/create and the method-scoped reads/mutations
 * live in the same controller so the resource is handled in one place.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EventPaymentMethodController {

    private final EventPaymentMethodService paymentMethodService;

    @GetMapping("/events/{eventId}/payment-methods")
    public ResponseEntity<List<PaymentMethodResponse>> listPaymentMethods(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID eventId) {
        UUID userId = principal == null ? null : principal.getId();
        return ResponseEntity.ok(paymentMethodService.listPaymentMethods(eventId, userId));
    }

    @PostMapping("/events/{eventId}/payment-methods")
    public ResponseEntity<PaymentMethodResponse> createPaymentMethod(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID eventId,
            @RequestBody PaymentMethodCreateRequest request) {
        PaymentMethodResponse created = paymentMethodService.createPaymentMethod(
                eventId, principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/payment-methods/{paymentMethodId}")
    public ResponseEntity<PaymentMethodResponse> getPaymentMethod(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID paymentMethodId) {
        UUID userId = principal == null ? null : principal.getId();
        return ResponseEntity.ok(paymentMethodService.getPaymentMethod(paymentMethodId, userId));
    }

    @PatchMapping("/payment-methods/{paymentMethodId}")
    public ResponseEntity<PaymentMethodResponse> updatePaymentMethod(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID paymentMethodId,
            @RequestBody PaymentMethodUpdateRequest request) {
        return ResponseEntity.ok(
                paymentMethodService.updatePaymentMethod(paymentMethodId, principal.getId(), request));
    }

    @DeleteMapping("/payment-methods/{paymentMethodId}")
    public ResponseEntity<Void> deletePaymentMethod(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID paymentMethodId) {
        paymentMethodService.deletePaymentMethod(paymentMethodId, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
