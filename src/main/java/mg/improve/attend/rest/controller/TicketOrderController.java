package mg.improve.attend.rest.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.OrderCreateRequest;
import mg.improve.attend.domain.dto.request.OrderRefuseRequest;
import mg.improve.attend.domain.dto.response.OrderResponse;
import mg.improve.attend.domain.dto.response.PageOrder;
import mg.improve.attend.domain.enums.OrderStatus;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.TicketOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket order endpoints. Like the other controllers, it only delegates to the service and
 * shapes the HTTP response.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TicketOrderController {

    private final TicketOrderService orderService;

    @PostMapping("/events/{eventId}/orders")
    public ResponseEntity<OrderResponse> createOrder(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID eventId,
            @RequestBody OrderCreateRequest request) {
        OrderResponse created = orderService.createOrder(eventId, principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/events/{eventId}/orders")
    public ResponseEntity<PageOrder> listEventOrders(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID eventId,
            @RequestParam(name = "status", required = false) OrderStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(
                orderService.listEventOrders(eventId, principal.getId(), status, page, size));
    }

    @GetMapping("/orders/me")
    public ResponseEntity<PageOrder> listMyOrders(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(name = "status", required = false) OrderStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.listMyOrders(principal.getId(), status, page, size));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId, principal.getId()));
    }

    @PostMapping("/orders/{orderId}/approve")
    public ResponseEntity<OrderResponse> approveOrder(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.approveOrder(orderId, principal.getId()));
    }

    @PostMapping("/orders/{orderId}/refuse")
    public ResponseEntity<OrderResponse> refuseOrder(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID orderId,
            @RequestBody OrderRefuseRequest request) {
        return ResponseEntity.ok(orderService.refuseOrder(orderId, principal.getId(), request));
    }
}
