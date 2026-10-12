package mg.improve.attend.domain.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mg.improve.attend.domain.enums.PaymentType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventPaymentMethod {

    private UUID id;
    private Events event;
    private PaymentType paymentType;
    private String providerName;
    private String accountName;
    private String accountIdentifier;
    private String routingDetails;
    private String instructions;

    @Builder.Default
    private boolean isActive = true;

    private Instant createdAt;
    private Instant updatedAt;
}
