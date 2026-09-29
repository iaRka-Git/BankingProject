package bank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private static final String USER_ID = "customer-001";
    private static final String PASSWORD = "s3cret-Pass";

    /**
     * SHA-256 of "secret", verified with the {@code sha256sum} command line tool.
     */
    private static final String SHA256_OF_SECRET =
            "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b";

    /**
     * SHA-256 of {@link #PASSWORD}, verified with the {@code sha256sum} command line tool.
     */
    private static final String SHA256_OF_TEST_PASSWORD =
            "610e700a9a03a853e24e41068053019614e41381ae204caf20255bd20fd09628";

    private Map<String, User> users;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        users = new LinkedHashMap<>();
        authService = new AuthService(users);
        authService.register(USER_ID, PASSWORD);
    }

    @Test
    void shouldHashPasswordWithSha256() {
        assertEquals(SHA256_OF_SECRET, AuthService.hashPassword("secret"));
        assertEquals(SHA256_OF_TEST_PASSWORD, AuthService.hashPassword(PASSWORD));
    }

    @Test
    void shouldReturnSixtyFourCharacterLowercaseHexHash() {
        String hash = AuthService.hashPassword(PASSWORD);

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "Unexpected hash format: " + hash);
    }

    @Test
    void shouldHashSamePasswordDeterministically() {
        assertEquals(AuthService.hashPassword(PASSWORD), AuthService.hashPassword(PASSWORD));
    }

    @Test
    void shouldHashDifferentPasswordsDifferently() {
        assertNotEquals(AuthService.hashPassword(PASSWORD), AuthService.hashPassword(PASSWORD + "-other"));
    }

    @Test
    void shouldRejectNullAndBlankPasswordsWhenHashing() {
        assertThrows(IllegalArgumentException.class, () -> AuthService.hashPassword(null));
        assertThrows(IllegalArgumentException.class, () -> AuthService.hashPassword(""));
        assertThrows(IllegalArgumentException.class, () -> AuthService.hashPassword("   "));
    }

    @Test
    void shouldVerifyCorrectPassword() {
        assertTrue(AuthService.verifyPassword(PASSWORD, AuthService.hashPassword(PASSWORD)));
    }

    @Test
    void shouldRejectWrongPassword() {
        String hash = AuthService.hashPassword(PASSWORD);

        assertFalse(AuthService.verifyPassword("wrong-password", hash));
        assertFalse(AuthService.verifyPassword(PASSWORD.toUpperCase(), hash), "Verification must be case sensitive");
        assertFalse(AuthService.verifyPassword(PASSWORD + " ", hash), "Verification must not trim the password");
    }

    @Test
    void shouldNotVerifyNullOrBlankInput() {
        String hash = AuthService.hashPassword(PASSWORD);

        assertFalse(AuthService.verifyPassword(null, hash));
        assertFalse(AuthService.verifyPassword("", hash));
        assertFalse(AuthService.verifyPassword(" ", hash));
        assertFalse(AuthService.verifyPassword(PASSWORD, null));
        assertFalse(AuthService.verifyPassword(PASSWORD, ""));
    }

    @Test
    void shouldStoreOnlyPasswordHash() {
        User user = users.get(USER_ID);

        assertNotEquals(PASSWORD, user.getPasswordHash());
        assertEquals(AuthService.hashPassword(PASSWORD), user.getPasswordHash());
    }

    @Test
    void shouldRejectDuplicateRegistration() {
        assertThrows(IllegalStateException.class, () -> authService.register(USER_ID, "another-password"));
        assertEquals(1, users.size());
    }

    @Test
    void shouldRejectBlankRegistrationData() {
        assertThrows(IllegalArgumentException.class, () -> authService.register(null, PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> authService.register("  ", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> authService.register("customer-002", null));
        assertThrows(IllegalArgumentException.class, () -> authService.register("customer-002", "  "));
    }
    @Test
    void shouldAuthenticateRegisteredUser() {
        User user = authService.authenticate(USER_ID, PASSWORD);

        assertSame(users.get(USER_ID), user);
        assertEquals(USER_ID, user.getUserId());
    }

    @Test
    void shouldAuthenticateIgnoringWhitespaceAroundUserId() {
        assertEquals(USER_ID, authService.authenticate("  " + USER_ID + "  ", PASSWORD).getUserId());
    }

    @Test
    void shouldThrowDescriptiveErrorForUnknownUser() {
        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> authService.authenticate("unknown-user", PASSWORD));

        assertTrue(exception.getMessage().contains("unknown-user"), exception.getMessage());
    }

    @Test
    void shouldThrowDescriptiveErrorForWrongPassword() {
        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> authService.authenticate(USER_ID, "not-my-password"));

        assertTrue(exception.getMessage().contains("Invalid password"), exception.getMessage());
    }

    @Test
    void shouldRejectNullAndBlankCredentials() {
        assertThrows(AuthenticationException.class, () -> authService.authenticate(null, PASSWORD));
        assertThrows(AuthenticationException.class, () -> authService.authenticate("  ", PASSWORD));
        assertThrows(AuthenticationException.class, () -> authService.authenticate(USER_ID, null));
        assertThrows(AuthenticationException.class, () -> authService.authenticate(USER_ID, " "));
    }

    @Test
    void shouldAuthenticateCredentialsEnteredInTheConsole() {
        Scanner scanner = scannerFor(USER_ID + "\n" + PASSWORD + "\n");

        User user = authService.promptLogin(scanner);

        assertEquals(USER_ID, user.getUserId());
    }

    @Test
    void shouldFailConsoleLoginWhenInputEnds() {
        assertThrows(AuthenticationException.class, () -> authService.promptLogin(scannerFor("")));
    }

    @Test
    void shouldFailConsoleLoginWhenOnlyUserIdWasEntered() {
        assertThrows(AuthenticationException.class, () -> authService.promptLogin(scannerFor(USER_ID + "\n")));
    }

    @Test
    void shouldFailConsoleLoginWithWrongPassword() {
        assertThrows(AuthenticationException.class,
                () -> authService.promptLogin(scannerFor(USER_ID + "\nwrong-password\n")));
    }

    private static Scanner scannerFor(String input) {
        return new Scanner(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }
}