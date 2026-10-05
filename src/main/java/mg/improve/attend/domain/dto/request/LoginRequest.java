package mg.improve.attend.domain.dto.request;

/**
 * Body of {@code POST /api/auth/login}. A plain data carrier: input validation lives in
 * {@code LoginRequestValidator}.
 */
public record LoginRequest(String email, String password) {
}
