package mg.improve.attend.domain.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mg.improve.attend.domain.enums.OrderStatus;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketOrder {

    private UUID id;
    private Users owner;
    private Events event;
    private EventPaymentMethod paymentMethod;
    private Users reviewedBy;
    private String transactionReference;
    private String priceUnit;
    private OrderStatus orderStatus;
    private String refusalReason;
    private Instant reviewedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Builder.Default
    private List<TicketOrderItem> items = new ArrayList<>();
}
