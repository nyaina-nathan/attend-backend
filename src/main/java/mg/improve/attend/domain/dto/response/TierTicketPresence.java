package mg.improve.attend.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Presence check result for the caller's tickets in a tier. {@code isPresent} is {@code true}
 * when the caller holds a ticket that still consumes a seat ({@code VALID} or {@code USED}).
 * The explicit {@link JsonProperty} guards the boolean getter naming so the wire key stays
 * {@code isPresent}.
 */
public record TierTicketPresence(@JsonProperty("isPresent") boolean isPresent) {

    public static TierTicketPresence of(boolean isPresent) {
        return new TierTicketPresence(isPresent);
    }
}
