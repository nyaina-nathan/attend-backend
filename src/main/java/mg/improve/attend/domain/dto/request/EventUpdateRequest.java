package mg.improve.attend.domain.dto.request;

import java.time.Instant;
import mg.improve.attend.domain.enums.EventStatus;

/**
 * Body of {@code PATCH /api/events/{eventId}}. Partial update: every field is optional and
 * only the non-null ones are applied. Validation lives in {@code EventValidator}.
 */
public record EventUpdateRequest(
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        Boolean isPublic,
        String location,
        EventStatus status) {
}
