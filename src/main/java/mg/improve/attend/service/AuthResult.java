package mg.improve.attend.service;

import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.security.IssuedToken;

/**
 * Internal result of a successful registration/login: the public user object plus the raw
 * tokens the controller must set as cookies. Kept out of the DTO packages because it carries
 * security types that must never be serialized into a response body.
 */
public record AuthResult(UserResponse user, IssuedToken accessToken, IssuedToken refreshToken) {
}
