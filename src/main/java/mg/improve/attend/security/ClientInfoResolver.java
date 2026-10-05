package mg.improve.attend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The single place coupled to the servlet API for reading client metadata, keeping the
 * service layer free of {@code HttpServletRequest} handling details and the controller thin.
 * Values are truncated to the columns that store them.
 */
@Component
public class ClientInfoResolver {

    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final int USER_AGENT_MAX = 255;
    private static final int IP_MAX = 45;

    public ClientInfo resolve(HttpServletRequest request) {
        return new ClientInfo(
                truncate(request.getHeader(USER_AGENT_HEADER), USER_AGENT_MAX),
                truncate(request.getRemoteAddr(), IP_MAX));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
