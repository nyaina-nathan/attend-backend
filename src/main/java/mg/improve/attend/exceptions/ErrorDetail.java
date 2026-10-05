package mg.improve.attend.exceptions;

/**
 * A single field-level validation failure carried in {@link ApiError#details()}.
 */
public record ErrorDetail(String field, String message) {
}
