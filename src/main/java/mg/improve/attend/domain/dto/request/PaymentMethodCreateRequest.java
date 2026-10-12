package mg.improve.attend.domain.dto.request;

import mg.improve.attend.domain.enums.PaymentType;

/**
 * Body of {@code POST /api/events/{eventId}/payment-methods}. A plain data carrier: input
 * validation lives in {@code PaymentMethodValidator}. {@code isActive} defaults to {@code true}
 * when omitted.
 */
public record PaymentMethodCreateRequest(
        PaymentType paymentType,
        String providerName,
        String accountName,
        String accountIdentifier,
        String routingDetails,
        String instructions,
        Boolean isActive) {
}
