package mg.improve.attend.domain.dto.response;

/**
 * Rule that produced an {@link AttentionItem}. Clients should ignore values they do not know
 * so new rules can be added without breaking them.
 */
public enum AttentionType {
    NO_TIERS,
    LOW_STOCK,
    POSTPONED,
    PENDING_ORDERS
}
