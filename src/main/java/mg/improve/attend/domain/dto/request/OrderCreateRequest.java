package mg.improve.attend.domain.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/events/{eventId}/orders}. A plain data carrier: input validation
 * lives in {@code OrderValidator}.
 */
public record OrderCreateRequest(
        UUID paymentMethodId,
        String transactionReference,
        List<OrderItemCreateRequest> items) {
}
