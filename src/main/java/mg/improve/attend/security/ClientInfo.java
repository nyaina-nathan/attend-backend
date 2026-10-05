package mg.improve.attend.security;

/**
 * Client metadata captured from the request for audit purposes. Both values are optional.
 */
public record ClientInfo(String userAgent, String ipAddress) {
}
