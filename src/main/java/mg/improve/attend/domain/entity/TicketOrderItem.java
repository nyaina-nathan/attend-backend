package mg.improve.attend.domain.entity;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketOrderItem {

    private UUID id;
    private TicketTier tier;
    private int quantity;
    private BigDecimal unitPrice;
}
