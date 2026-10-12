package mg.improve.attend.domain.dto.request;

import java.math.BigDecimal;
import java.time.Instant;
import mg.improve.attend.domain.enums.EventStatus;

/**
 * Body of {@code PATCH /api/events/{eventId}}. Partial update: every field is optional and
 * only the non-null ones are applied. Validation lives in {@code EventValidator}.
 * {@code latitude} and {@code longitude} are a coordinate pair: when one is provided the
 * other must resolve to a value too (from the request or the stored event).
 */
public record EventUpdateRequest(
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        Boolean isPublic,
        String location,
        BigDecimal latitude,
        BigDecimal longitude,
        String priceUnit,
        EventStatus status) {
}
