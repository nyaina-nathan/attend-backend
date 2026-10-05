package mg.improve.attend.service;

import mg.improve.attend.security.IssuedToken;

/**
 * Tokens produced by a refresh. {@code refreshToken} is {@code null} when the existing refresh
 * token was reused rather than rotated, in which case the client keeps its current cookie.
 */
public record SessionTokens(IssuedToken accessToken, IssuedToken refreshToken) {
}
