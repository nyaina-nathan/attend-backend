package mg.improve.attend.domain.dto.request;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Body of {@code POST /api/events}. A plain data carrier: input validation lives in
 * {@code EventValidator}. {@code isPublic} is a nullable {@link Boolean} so an omitted value
 * defaults to {@code true} as documented. {@code priceUnit} is a nullable currency/unit code;
 * when omitted the event defaults to {@code USD}. {@code latitude} and {@code longitude} are a
 * nullable coordinate pair: both must be provided together.
 */
public record EventCreateRequest(
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        Boolean isPublic,
        String location,
        BigDecimal latitude,
        BigDecimal longitude,
        String priceUnit) {
}
