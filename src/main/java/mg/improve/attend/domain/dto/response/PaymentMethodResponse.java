package mg.improve.attend.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import mg.improve.attend.domain.entity.EventPaymentMethod;
import mg.improve.attend.domain.enums.PaymentType;

/**
 * Payment method object returned by the payment method endpoints. {@code isActive} is annotated
 * so it serializes as {@code isActive} (a record accessor named {@code isActive()} would
 * otherwise map to {@code active}).
 */
public record PaymentMethodResponse(
        UUID id,
        UUID eventId,
        PaymentType paymentType,
        String providerName,
        String accountName,
        String accountIdentifier,
        String routingDetails,
        String instructions,
        @JsonProperty("isActive") boolean isActive,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentMethodResponse from(EventPaymentMethod method) {
        return new PaymentMethodResponse(
                method.getId(),
                method.getEvent().getId(),
                method.getPaymentType(),
                method.getProviderName(),
                method.getAccountName(),
                method.getAccountIdentifier(),
                method.getRoutingDetails(),
                method.getInstructions(),
                method.isActive(),
                method.getCreatedAt(),
                method.getUpdatedAt());
    }
}
