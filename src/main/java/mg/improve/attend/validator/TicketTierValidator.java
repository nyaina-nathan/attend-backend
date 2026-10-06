package mg.improve.attend.validator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import mg.improve.attend.domain.dto.request.TierCreateRequest;
import mg.improve.attend.domain.dto.request.TierUpdateRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the ticket tier request bodies. All failures are collected so the client receives
 * every problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class TicketTierValidator {

    private static final int TIER_NAME_MAX = 100;

    public void validateCreate(TierCreateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        validateTierName(request.tierName(), details);
        validatePrice(request.price(), details);
        validateCapacity(request.capacity(), details);

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    public void validateUpdate(TierUpdateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        if (request.tierName() != null) {
            validateTierName(request.tierName(), details);
        }
        if (request.price() != null) {
            validatePrice(request.price(), details);
        }
        if (request.capacity() != null) {
            validateCapacity(request.capacity(), details);
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    private void validateTierName(String tierName, List<ErrorDetail> details) {
        if (tierName == null || tierName.isBlank()) {
            details.add(new ErrorDetail("tierName", "must not be blank"));
        } else if (tierName.length() > TIER_NAME_MAX) {
            details.add(new ErrorDetail("tierName", "must be at most " + TIER_NAME_MAX + " characters"));
        }
    }

    private void validatePrice(BigDecimal price, List<ErrorDetail> details) {
        if (price == null) {
            details.add(new ErrorDetail("price", "must not be null"));
        } else if (price.signum() < 0) {
            details.add(new ErrorDetail("price", "must be greater than or equal to 0"));
        }
    }

    private void validateCapacity(Integer capacity, List<ErrorDetail> details) {
        if (capacity == null) {
            details.add(new ErrorDetail("capacity", "must not be null"));
        } else if (capacity < 0) {
            details.add(new ErrorDetail("capacity", "must be greater than or equal to 0"));
        }
    }
}
