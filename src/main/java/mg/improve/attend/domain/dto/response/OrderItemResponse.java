package mg.improve.attend.domain.dto.response;

import java.math.BigDecimal;
import java.util.UUID;
import mg.improve.attend.domain.entity.TicketOrderItem;

/**
 * One line of an {@link OrderResponse}. {@code unitPrice} is the tier price frozen at order
 * creation; {@code lineTotal = unitPrice × quantity}.
 */
public record OrderItemResponse(
        UUID id,
        UUID tierId,
        String tierName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal) {

    public static OrderItemResponse from(TicketOrderItem item) {
        BigDecimal lineTotal = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new OrderItemResponse(
                item.getId(),
                item.getTier().getId(),
                item.getTier().getTierName(),
                item.getQuantity(),
                item.getUnitPrice(),
                lineTotal);
    }
}
