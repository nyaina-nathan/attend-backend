package mg.improve.attend.validator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.OrderCreateRequest;
import mg.improve.attend.domain.dto.request.OrderItemCreateRequest;
import mg.improve.attend.domain.dto.request.OrderRefuseRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the ticket order request bodies and pagination. All failures are collected so the
 * client receives every problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class OrderValidator {

    private static final int REFERENCE_MAX = 255;
    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 100;

    public void validateCreate(OrderCreateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        if (request.paymentMethodId() == null) {
            details.add(new ErrorDetail("paymentMethodId", "must not be null"));
        }
        if (request.transactionReference() != null && request.transactionReference().length() > REFERENCE_MAX) {
            details.add(new ErrorDetail("transactionReference",
                    "must be at most " + REFERENCE_MAX + " characters"));
        }

        List<OrderItemCreateRequest> items = request.items();
        if (items == null || items.isEmpty()) {
            details.add(new ErrorDetail("items", "must not be empty"));
        } else {
            Set<UUID> seenTiers = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                validateItem(items.get(i), i, seenTiers, details);
            }
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    public void validateRefuse(OrderRefuseRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        if (request.reason() == null || request.reason().isBlank()) {
            details.add(new ErrorDetail("reason", "must not be blank"));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    /** Validates the pagination of an order list query. */
    public void validateListQuery(int page, int size) {
        List<ErrorDetail> details = new ArrayList<>();

        if (page < 0) {
            details.add(new ErrorDetail("page", "must be greater than or equal to 0"));
        }
        if (size < SIZE_MIN || size > SIZE_MAX) {
            details.add(new ErrorDetail("size", "must be between " + SIZE_MIN + " and " + SIZE_MAX));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    private void validateItem(OrderItemCreateRequest item, int index, Set<UUID> seenTiers,
            List<ErrorDetail> details) {
        String field = "items[" + index + "]";
        if (item == null) {
            details.add(new ErrorDetail(field, "must not be null"));
            return;
        }
        if (item.tierId() == null) {
            details.add(new ErrorDetail(field + ".tierId", "must not be null"));
        } else if (!seenTiers.add(item.tierId())) {
            details.add(new ErrorDetail(field + ".tierId", "must not be repeated"));
        }
        if (item.quantity() == null) {
            details.add(new ErrorDetail(field + ".quantity", "must not be null"));
        } else if (item.quantity() < 1) {
            details.add(new ErrorDetail(field + ".quantity", "must be greater than or equal to 1"));
        }
    }
}
