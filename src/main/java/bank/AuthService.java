package bank;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

/**
 * Handles password hashing and console based authentication.
 *
 * <p>Passwords are hashed with SHA-256 and stored as a lowercase hexadecimal string, which is
 * exactly the value kept in {@link User#getPasswordHash()}. Verification hashes the supplied
 * password again and compares both hashes with a constant time comparison
 * ({@link MessageDigest#isEqual(byte[], byte[])}) so that the comparison does not leak
 * information through its running time.</p>
 */
public class AuthService {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final Map<String, User> users;

    /**
     * @param users the user store keyed by user id; the map is used by reference, so users
     *              registered through {@link #register(String, String)} are visible immediately
     */
    public AuthService(Map<String, User> users) {
        this.users = Objects.requireNonNull(users, "User store cannot be null");
    }

    /**
     * Hashes a plain text password with SHA-256.
     *
     * @param rawPassword the plain text password
     * @return the 64 character lowercase hexadecimal SHA-256 hash
     * @throws IllegalArgumentException when the password is null or blank
     */
    public static String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be null or blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available in this JVM", e);
        }
    }

    /**
     * Verifies a plain text password against a stored hash.
     *
     * @param rawPassword  the password entered by the user
     * @param passwordHash the stored hash, usually {@link User#getPasswordHash()}
     * @return {@code true} when the password matches the hash
     */
    public static boolean verifyPassword(String rawPassword, String passwordHash) {
        if (rawPassword == null || rawPassword.isBlank() || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        byte[] expected = passwordHash.getBytes(StandardCharsets.UTF_8);
        byte[] actual = hashPassword(rawPassword).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Authenticates a user by user id and password.
     *
     * @param userId     the user id entered by the customer
     * @param rawPassword the password entered by the customer
     * @return the authenticated user, ready to be used as session context
     * @throws AuthenticationException when the input is null/blank, the user is unknown or the
     *                                 password does not match the stored hash
     */
    public User authenticate(String userId, String rawPassword) {
        if (userId == null || userId.isBlank()) {
            throw new AuthenticationException("User ID cannot be null or blank");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new AuthenticationException("Password cannot be null or blank");
        }

        String normalizedId = userId.trim();
        User user = users.get(normalizedId);
        if (user == null) {
            throw new AuthenticationException("No user found with ID '" + normalizedId + "'");
        }
        if (!verifyPassword(rawPassword, user.getPasswordHash())) {
            throw new AuthenticationException("Invalid password for user '" + user.getUserId() + "'");
        }
        return user;
    }

    /**
     * Hashes the password and stores a new user in the user store.
     *
     * @param userId     the user id of the new customer
     * @param rawPassword the plain text password, it is hashed and never stored as is
     * @return the created user
     * @throws IllegalArgumentException when the user id or the password is null/blank
     * @throws IllegalStateException    when the user id is already taken
     */
    public User register(String userId, String rawPassword) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID cannot be null or blank");
        }
        String normalizedId = userId.trim();
        if (users.containsKey(normalizedId)) {
            throw new IllegalStateException("User '" + normalizedId + "' already exists");
        }

        User user = new User(normalizedId, hashPassword(rawPassword));
        users.put(normalizedId, user);
        return user;
    }

    /**
     * Reads the user id and the password from the console and authenticates the credentials.
     *
     * @param scanner the scanner connected to the console input
     * @return the authenticated user
     * @throws AuthenticationException when the input is invalid, ends unexpectedly or is rejected
     */
    public User promptLogin(Scanner scanner) {
        Objects.requireNonNull(scanner, "Scanner cannot be null");

        String userId = prompt(scanner, "User ID: ");
        String password = prompt(scanner, "Password: ");
        return authenticate(userId, password);
    }

    private static String prompt(Scanner scanner, String label) {
        System.out.print(label);
        if (!scanner.hasNextLine()) {
            throw new AuthenticationException("Input ended while '" + label.trim() + "' was expected");
        }
        return scanner.nextLine();
    }
}