package mg.improve.attend.domain.dto.response;

import java.util.List;

/**
 * A page of {@link AttendeeTicket}. Flattened page metadata plus {@code content}, matching the
 * documented {@code PageAttendeeTicket} schema.
 */
public record PageAttendeeTicket(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<AttendeeTicket> content) {
}
