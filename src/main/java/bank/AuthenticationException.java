package bank;

/**
 * Thrown when a user cannot be authenticated, for example because the user id is unknown,
 * the supplied password is wrong or the console input ended unexpectedly.
 */
// TODO: maybe add global handler exception later
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}