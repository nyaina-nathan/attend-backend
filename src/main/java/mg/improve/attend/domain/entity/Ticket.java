package mg.improve.attend.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mg.improve.attend.domain.enums.TicketStatus;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    private UUID id;
    private Users owner;
    private Users checkedBy;
    private TicketTier tier;
    private BigDecimal pricePaid;
    private TicketStatus ticketStatus;
    private UUID orderId;
    private Instant checkedInAt;
    private Instant createdAt;
    private Instant validUntil;
}
