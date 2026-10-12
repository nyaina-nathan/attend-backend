package mg.improve.attend.validator;

import java.util.ArrayList;
import java.util.List;
import mg.improve.attend.domain.dto.request.PaymentMethodCreateRequest;
import mg.improve.attend.domain.dto.request.PaymentMethodUpdateRequest;
import mg.improve.attend.domain.enums.PaymentType;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the payment method request bodies. All failures are collected so the client receives
 * every problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class PaymentMethodValidator {

    private static final int PROVIDER_NAME_MAX = 100;
    private static final int ACCOUNT_NAME_MAX = 150;
    private static final int ACCOUNT_IDENTIFIER_MAX = 100;
    private static final int ROUTING_DETAILS_MAX = 100;

    public void validateCreate(PaymentMethodCreateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        validatePaymentType(request.paymentType(), details);
        validateProviderName(request.providerName(), details);
        validateAccountName(request.accountName(), details);
        validateAccountIdentifier(request.accountIdentifier(), details);
        validateRoutingDetails(request.routingDetails(), details);

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    public void validateUpdate(PaymentMethodUpdateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        if (request.paymentType() != null) {
            validatePaymentType(request.paymentType(), details);
        }
        if (request.providerName() != null) {
            validateProviderName(request.providerName(), details);
        }
        if (request.accountName() != null) {
            validateAccountName(request.accountName(), details);
        }
        if (request.accountIdentifier() != null) {
            validateAccountIdentifier(request.accountIdentifier(), details);
        }
        if (request.routingDetails() != null) {
            validateRoutingDetails(request.routingDetails(), details);
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    private void validatePaymentType(PaymentType paymentType, List<ErrorDetail> details) {
        if (paymentType == null) {
            details.add(new ErrorDetail("paymentType", "must not be null"));
        }
    }

    private void validateProviderName(String providerName, List<ErrorDetail> details) {
        if (providerName == null || providerName.isBlank()) {
            details.add(new ErrorDetail("providerName", "must not be blank"));
        } else if (providerName.length() > PROVIDER_NAME_MAX) {
            details.add(new ErrorDetail("providerName",
                    "must be at most " + PROVIDER_NAME_MAX + " characters"));
        }
    }

    private void validateAccountName(String accountName, List<ErrorDetail> details) {
        if (accountName == null || accountName.isBlank()) {
            details.add(new ErrorDetail("accountName", "must not be blank"));
        } else if (accountName.length() > ACCOUNT_NAME_MAX) {
            details.add(new ErrorDetail("accountName",
                    "must be at most " + ACCOUNT_NAME_MAX + " characters"));
        }
    }

    private void validateAccountIdentifier(String accountIdentifier, List<ErrorDetail> details) {
        if (accountIdentifier == null || accountIdentifier.isBlank()) {
            details.add(new ErrorDetail("accountIdentifier", "must not be blank"));
        } else if (accountIdentifier.length() > ACCOUNT_IDENTIFIER_MAX) {
            details.add(new ErrorDetail("accountIdentifier",
                    "must be at most " + ACCOUNT_IDENTIFIER_MAX + " characters"));
        }
    }

    private void validateRoutingDetails(String routingDetails, List<ErrorDetail> details) {
        if (routingDetails != null && routingDetails.length() > ROUTING_DETAILS_MAX) {
            details.add(new ErrorDetail("routingDetails",
                    "must be at most " + ROUTING_DETAILS_MAX + " characters"));
        }
    }
}
