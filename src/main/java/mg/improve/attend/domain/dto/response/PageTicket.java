package mg.improve.attend.domain.dto.response;

import java.util.List;

/**
 * A page of {@link TicketResponse}. Flattened page metadata plus {@code content}, matching the
 * documented {@code PageTicket} schema.
 */
public record PageTicket(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<TicketResponse> content) {
}
