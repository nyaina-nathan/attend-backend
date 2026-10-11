package mg.improve.attend.domain.dto.response;

import java.util.List;

/**
 * A page of {@link UserResponse}, returned by {@code GET /users}. Flattened page metadata plus
 * {@code content}, matching the documented {@code PageUserResponse} schema.
 */
public record PageUserResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<UserResponse> content) {
}
