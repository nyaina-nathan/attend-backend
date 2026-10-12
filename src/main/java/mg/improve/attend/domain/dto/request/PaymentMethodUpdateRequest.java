package mg.improve.attend.domain.dto.request;

import mg.improve.attend.domain.enums.PaymentType;

/**
 * Body of {@code PATCH /api/payment-methods/{paymentMethodId}}. Partial update: every field is
 * optional and only the non-null ones are applied. Validation lives in
 * {@code PaymentMethodValidator}. The owning event cannot be changed.
 */
public record PaymentMethodUpdateRequest(
        PaymentType paymentType,
        String providerName,
        String accountName,
        String accountIdentifier,
        String routingDetails,
        String instructions,
        Boolean isActive) {
}
