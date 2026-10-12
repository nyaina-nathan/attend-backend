package mg.improve.attend.domain.dto.request;

/**
 * Body of {@code POST /api/orders/{orderId}/refuse}. The reason is required.
 */
public record OrderRefuseRequest(
        String reason) {
}
