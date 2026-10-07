package mg.improve.attend.domain.dto.request;

/**
 * Body of {@code POST /api/tickets/{ticketId}/refund}. {@code reason} is optional and is not
 * stored by the current schema.
 */
public record RefundRequest(String reason) {
}
