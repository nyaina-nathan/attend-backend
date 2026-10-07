package mg.improve.attend.domain.dto.response;

import java.util.UUID;
import mg.improve.attend.domain.entity.TicketTier;

/**
 * Ticket tier reference nested in a ticket response.
 */
public record TicketTierRef(
        UUID id,
        String tierName) {

    public static TicketTierRef from(TicketTier tier) {
        return new TicketTierRef(tier.getId(), tier.getTierName());
    }
}
