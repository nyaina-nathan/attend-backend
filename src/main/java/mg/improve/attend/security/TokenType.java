package mg.improve.attend.security;

/**
 * The kind of JWT, stored in the {@code type} claim so an access token can never be
 * replayed where a refresh token is expected, and vice versa.
 */
public enum TokenType {

    ACCESS("access"),
    REFRESH("refresh");

    private final String value;

    TokenType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TokenType from(String value) {
        for (TokenType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown token type: " + value);
    }
}
