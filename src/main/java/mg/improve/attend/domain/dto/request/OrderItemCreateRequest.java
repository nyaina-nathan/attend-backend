package mg.improve.attend.domain.dto.request;

import java.util.UUID;

/**
 * One line of an {@link OrderCreateRequest}: a tier and how many tickets of it to buy.
 */
public record OrderItemCreateRequest(
        UUID tierId,
        Integer quantity) {
}
