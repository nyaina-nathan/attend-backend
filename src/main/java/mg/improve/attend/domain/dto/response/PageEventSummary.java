package mg.improve.attend.domain.dto.response;

import java.util.List;

/**
 * A page of {@link EventSummary}. Flattened page metadata plus {@code content}, matching the
 * documented {@code PageEventSummary} schema.
 */
public record PageEventSummary(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<EventSummary> content) {
}
