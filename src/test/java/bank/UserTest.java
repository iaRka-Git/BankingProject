package bank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTest {

    private static final String USER_ID = "customer-001";
    private static final String OTHER_USER_ID = "customer-002";
    private static final String PASSWORD_HASH = "a".repeat(64);
    private static final String ACCOUNT_NUMBER = "UA000000000000000001";

    private User user;

    @BeforeEach
    void setUp() {
        user = new User(USER_ID, PASSWORD_HASH);
    }

    @Test
    void shouldCreateUserWithoutAccounts() {
        assertEquals(USER_ID, user.getUserId());
        assertEquals(PASSWORD_HASH, user.getPasswordHash());
        assertTrue(user.getAccounts().isEmpty());
        assertEquals(0, user.getAccountCount());
    }

    @Test
    void shouldRejectNullAndBlankIdentityFields() {
        assertThrows(NullPointerException.class, () -> new User(null, PASSWORD_HASH));
        assertThrows(IllegalArgumentException.class, () -> new User("  ", PASSWORD_HASH));
        assertThrows(NullPointerException.class, () -> new User(USER_ID, null));
        assertThrows(IllegalArgumentException.class, () -> new User(USER_ID, "  "));
    }

    @Test
    void shouldAddAccount() {
        BankAccount account = account(ACCOUNT_NUMBER);

        user.addAccount(account);

        assertEquals(List.of(account), user.getAccounts());
        assertEquals(1, user.getAccountCount());
        assertTrue(user.findAccount(ACCOUNT_NUMBER).isPresent());
    }

    @Test
    void shouldNotFindUnknownOrBlankAccount() {
        user.addAccount(account(ACCOUNT_NUMBER));

        assertTrue(user.findAccount("UA000000000000000009").isEmpty());
        assertTrue(user.findAccount(null).isEmpty());
        assertTrue(user.findAccount("  ").isEmpty());
    }

    @Test
    void shouldRejectNullAccount() {
        assertThrows(NullPointerException.class, () -> user.addAccount(null));
    }

    @Test
    void shouldRejectDuplicateAccountNumber() {
        user.addAccount(account(ACCOUNT_NUMBER));

        assertThrows(IllegalArgumentException.class, () -> user.addAccount(account(ACCOUNT_NUMBER)));
        assertEquals(1, user.getAccountCount());
    }

    @Test
    void shouldRejectAccountOfAnotherUser() {
        BankAccount foreignAccount = new CheckingAccount("UA000000000000000002", OTHER_USER_ID, Currency.USD);

        assertThrows(IllegalArgumentException.class, () -> user.addAccount(foreignAccount));
        assertTrue(user.getAccounts().isEmpty());
    }

    @Test
    void shouldBlockAccount() {
        BankAccount account = account(ACCOUNT_NUMBER);
        user.addAccount(account);

        user.blockAccount(ACCOUNT_NUMBER);

        assertFalse(account.isActive());
        assertFalse(user.findAccount(ACCOUNT_NUMBER).orElseThrow().isActive());
    }

    @Test
    void shouldUnblockAccount() {
        BankAccount account = account(ACCOUNT_NUMBER);
        user.addAccount(account);
        user.blockAccount(ACCOUNT_NUMBER);

        user.unblockAccount(ACCOUNT_NUMBER);

        assertTrue(account.isActive());
    }

    @Test
    void shouldRejectBlockingUnknownAccount() {
        user.addAccount(account(ACCOUNT_NUMBER));

        assertThrows(IllegalArgumentException.class, () -> user.blockAccount("UA000000000000000009"));
        assertThrows(IllegalArgumentException.class, () -> user.unblockAccount(null));
        assertThrows(IllegalArgumentException.class, () -> user.unblockAccount("   "));
    }

    @Test
    void shouldExposeUnmodifiableAccountList() {
        assertThrows(UnsupportedOperationException.class,
                () -> user.getAccounts().add(account("UA000000000000000003")));
    }

    @Test
    void shouldBeEqualByUserId() {
        assertEquals(new User(USER_ID, "b".repeat(64)), user);
        assertEquals(new User(USER_ID, "b".repeat(64)).hashCode(), user.hashCode());
        assertNotEquals(new User(OTHER_USER_ID, PASSWORD_HASH), user);
        assertNotEquals(USER_ID, user);
    }

    @Test
    void shouldNotLeakPasswordHashInToString() {
        assertFalse(user.toString().contains(PASSWORD_HASH), user.toString());
    }

    private static BankAccount account(String accountNumber) {
        return new CheckingAccount(accountNumber, USER_ID, Currency.EUR);
    }
}