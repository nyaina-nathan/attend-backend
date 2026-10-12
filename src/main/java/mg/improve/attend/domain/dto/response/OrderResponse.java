package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.domain.entity.TicketOrder;
import mg.improve.attend.domain.enums.OrderStatus;

/**
 * Ticket order object returned by the order endpoints. {@code total} is the sum of the item
 * line totals; {@code tickets} holds the issued tickets (or the placeholder {@code REFUNDED}
 * ones for a refused order).
 */
public record OrderResponse(
        UUID id,
        UUID eventId,
        UserRef owner,
        UUID paymentMethodId,
        String transactionReference,
        String priceUnit,
        OrderStatus orderStatus,
        String refusalReason,
        UserRef reviewedBy,
        Instant reviewedAt,
        List<OrderItemResponse> items,
        BigDecimal total,
        List<OrderTicketRef> tickets,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(TicketOrder order, List<OrderTicketRef> tickets) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(OrderItemResponse::from)
                .toList();
        BigDecimal total = items.stream()
                .map(OrderItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(
                order.getId(),
                order.getEvent().getId(),
                UserRef.from(order.getOwner()),
                order.getPaymentMethod().getId(),
                order.getTransactionReference(),
                order.getPriceUnit(),
                order.getOrderStatus(),
                order.getRefusalReason(),
                UserRef.from(order.getReviewedBy()),
                order.getReviewedAt(),
                items,
                total,
                tickets == null ? List.of() : List.copyOf(tickets),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
