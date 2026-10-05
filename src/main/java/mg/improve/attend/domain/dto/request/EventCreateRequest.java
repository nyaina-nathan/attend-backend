package mg.improve.attend.domain.dto.request;

import java.time.Instant;

/**
 * Body of {@code POST /api/events}. A plain data carrier: input validation lives in
 * {@code EventValidator}. {@code isPublic} is a nullable {@link Boolean} so an omitted value
 * defaults to {@code true} as documented.
 */
public record EventCreateRequest(
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        Boolean isPublic,
        String location) {
}
