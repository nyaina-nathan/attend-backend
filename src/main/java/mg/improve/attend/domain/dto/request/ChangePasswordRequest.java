package mg.improve.attend.domain.dto.request;

/**
 * Body of {@code POST /api/users/me/password}. A plain data carrier: validation lives in
 * {@code UserValidator}.
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
