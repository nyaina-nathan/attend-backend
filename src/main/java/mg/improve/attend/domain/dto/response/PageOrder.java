package mg.improve.attend.domain.dto.response;

import java.util.List;

/**
 * A page of {@link OrderResponse}. Flattened page metadata plus {@code content}, matching the
 * documented {@code PageOrder} schema.
 */
public record PageOrder(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<OrderResponse> content) {
}
