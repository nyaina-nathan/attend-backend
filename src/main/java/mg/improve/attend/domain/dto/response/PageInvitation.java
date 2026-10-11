package mg.improve.attend.domain.dto.response;

import java.util.List;

/**
 * A page of {@link InvitationResponse}. Flattened page metadata plus {@code content}, matching
 * the documented {@code PageInvitation} schema.
 */
public record PageInvitation(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<InvitationResponse> content) {
}
