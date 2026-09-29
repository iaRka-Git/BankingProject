package bank.storage;

import bank.AuthService;
import bank.BankAccount;
import bank.CheckingAccount;
import bank.Currency;
import bank.SavingsAccount;
import bank.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPersistenceTest {

    private static final String USER_ID = "customer-001";
    private static final String PASSWORD = "s3cret-Pass";
    private static final String CHECKING_NUMBER = "UA000000000000000001";
    private static final String BLOCKED_NUMBER = "UA000000000000000002";

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnEmptyMapWhenNothingWasEverSaved() {
        StorageService storage = new JsonPersistence(tempDir.resolve("bank-state.json"));

        Map<String, User> loaded = storage.loadUsers();

        assertTrue(loaded.isEmpty());
    }

    @Test
    void shouldCreateStateFile() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");
        StorageService storage = new JsonPersistence(stateFile);

        storage.saveUsers(Map.of());

        assertTrue(Files.exists(stateFile));
    }

    @Test
    void shouldCreateMissingParentDirectories() {
        Path stateFile = tempDir.resolve("data").resolve("nested").resolve("bank-state.json");
        StorageService storage = new JsonPersistence(stateFile);

        storage.saveUsers(usersWithAccount());

        assertTrue(Files.exists(stateFile));
    }

    @Test
    void shouldNotWritePlainTextPassword() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");
        StorageService storage = new JsonPersistence(stateFile);

        storage.saveUsers(usersWithAccount());

        String json = Files.readString(stateFile);
        assertNotEquals(PASSWORD, json);
        assertFalse(json.contains(PASSWORD), "The state file must not contain the plain text password");
        assertTrue(json.contains(AuthService.hashPassword(PASSWORD)));
    }

    @Test
    void shouldRestoreUserAndAccountStateAfterRestart() {
        Path stateFile = tempDir.resolve("bank-state.json");

        // Session 1: create the user, work with the accounts, then the application exits
        StorageService firstRun = new JsonPersistence(stateFile);
        Map<String, User> users = usersWithAccount();
        User user = users.get(USER_ID);
        BankAccount checking = user.findAccount(CHECKING_NUMBER).orElseThrow();
        checking.deposit(new BigDecimal("250.00"), "Salary");
        checking.withdraw(new BigDecimal("100.50"), "Rent");
        firstRun.saveUsers(users);

        // Session 2: a fresh storage instance reads the state back
        StorageService secondRun = new JsonPersistence(stateFile);
        Map<String, User> restored = secondRun.loadUsers();

        User restoredUser = restored.get(USER_ID);
        assertEquals(1, restored.size());
        assertEquals(USER_ID, restoredUser.getUserId());
        assertEquals(AuthService.hashPassword(PASSWORD), restoredUser.getPasswordHash());
        assertEquals(2, restoredUser.getAccountCount());

        BankAccount restoredChecking = restoredUser.findAccount(CHECKING_NUMBER).orElseThrow();
        assertInstanceOf(CheckingAccount.class, restoredChecking);
        assertEquals(Currency.EUR, restoredChecking.getCurrency());
        assertEquals(USER_ID, restoredChecking.getOwnerId());
        assertEquals(0, new BigDecimal("149.50").compareTo(restoredChecking.getBalance()));
        assertTrue(restoredChecking.isActive());
        assertEquals(2, restoredChecking.getStatement().size());
        assertEquals("Salary", restoredChecking.getStatement().get(0).comment());
        assertEquals(0, new BigDecimal("-100.50").compareTo(restoredChecking.getStatement().get(1).amount()));
    }
    @Test
    void shouldRestoreBlockedFlagAfterRestart() {
        Path stateFile = tempDir.resolve("bank-state.json");

        StorageService firstRun = new JsonPersistence(stateFile);
        Map<String, User> users = usersWithAccount();
        users.get(USER_ID).blockAccount(BLOCKED_NUMBER);
        firstRun.saveUsers(users);

        StorageService secondRun = new JsonPersistence(stateFile);
        User restoredUser = secondRun.loadUsers().get(USER_ID);

        assertFalse(restoredUser.findAccount(BLOCKED_NUMBER).orElseThrow().isActive());
        assertTrue(restoredUser.findAccount(CHECKING_NUMBER).orElseThrow().isActive());
    }

    @Test
    void shouldKeepCredentialsUsableAfterRestart() {
        Path stateFile = tempDir.resolve("bank-state.json");

        StorageService firstRun = new JsonPersistence(stateFile);
        firstRun.saveUsers(usersWithAccount());

        AuthService authService = new AuthService(new JsonPersistence(stateFile).loadUsers());

        User user = authService.authenticate(USER_ID, PASSWORD);
        assertEquals(USER_ID, user.getUserId());
    }

    @Test
    void shouldOverwritePreviousState() {
        Path stateFile = tempDir.resolve("bank-state.json");
        StorageService storage = new JsonPersistence(stateFile);
        storage.saveUsers(usersWithAccount());

        User second = new User("customer-002", AuthService.hashPassword("other"));
        Map<String, User> onlySecondUser = new LinkedHashMap<>();
        onlySecondUser.put(second.getUserId(), second);
        storage.saveUsers(onlySecondUser);

        Map<String, User> loaded = new JsonPersistence(stateFile).loadUsers();

        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey("customer-002"));
    }

    @Test
    void shouldSaveAndRestoreMultipleUsers() {
        Path stateFile = tempDir.resolve("bank-state.json");
        Map<String, User> users = usersWithAccount();
        User second = new User("customer-002", AuthService.hashPassword("other-password"));
        second.addAccount(new CheckingAccount("UA000000000000000009", "customer-002", Currency.USD));
        users.put(second.getUserId(), second);

        new JsonPersistence(stateFile).saveUsers(users);
        Map<String, User> loaded = new JsonPersistence(stateFile).loadUsers();

        assertEquals(2, loaded.size());
        assertEquals(1, loaded.get("customer-002").getAccountCount());
        assertEquals(Currency.USD, loaded.get("customer-002").getAccounts().get(0).getCurrency());
    }

    @Test
    void shouldRestoreAdditionalRegisteredAccountType() {
        Path stateFile = tempDir.resolve("bank-state.json");
        Map<String, AccountFactory> factories = new HashMap<>(JsonPersistence.defaultAccountFactories());
        factories.put("SAVINGS", SavingsAccount::new);
        StorageService storage = new JsonPersistence(stateFile, factories);

        User user = new User(USER_ID, AuthService.hashPassword(PASSWORD));
        user.addAccount(new SavingsAccount("UA000000000000000003", USER_ID, Currency.UAH));
        user.getAccounts().get(0).deposit(new BigDecimal("1000"), "Savings");
        storage.saveUsers(Map.of(USER_ID, user));

        Map<String, User> loaded = new JsonPersistence(stateFile, factories).loadUsers();
        BankAccount restored = loaded.get(USER_ID).getAccounts().get(0);

        assertInstanceOf(SavingsAccount.class, restored);
        assertEquals(Currency.UAH, restored.getCurrency());
        assertEquals(0, new BigDecimal("1000").compareTo(restored.getBalance()));
    }

    @Test
    void shouldFailWithClearMessageForUnknownAccountType() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");
        write(stateFile, """
                {
                  "version" : 1,
                  "users" : [ {
                    "userId" : "customer-001",
                    "passwordHash" : "%s",
                    "accounts" : [ {
                      "type" : "CRYPTO",
                      "accountNumber" : "UA000000000000000001",
                      "ownerId" : "customer-001",
                      "currency" : "EUR",
                      "active" : true,
                      "balance" : 0,
                      "statement" : [ ]
                    } ]
                  } ]
                }
                """.formatted(AuthService.hashPassword(PASSWORD)));

        StorageException exception = assertThrows(StorageException.class,
                () -> new JsonPersistence(stateFile).loadUsers());

        assertTrue(exception.getMessage().contains("CRYPTO"), exception.getMessage());
    }
    @Test
    void shouldFailWithClearMessageForNewerSchemaVersion() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");
        write(stateFile, """
                { "version" : 99, "users" : [ ] }
                """);

        StorageException exception = assertThrows(StorageException.class,
                () -> new JsonPersistence(stateFile).loadUsers());

        assertTrue(exception.getMessage().contains("99"), exception.getMessage());
    }

    @Test
    void shouldFailWithClearMessageForMissingRequiredField() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");
        write(stateFile, """
                { "version" : 1, "users" : [ { "passwordHash" : "abc" } ] }
                """);

        StorageException exception = assertThrows(StorageException.class,
                () -> new JsonPersistence(stateFile).loadUsers());

        assertTrue(exception.getMessage().contains("userId"), exception.getMessage());
    }

    @Test
    void shouldFailForCorruptedFile() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");
        write(stateFile, "{ this is not json");

        assertThrows(StorageException.class, () -> new JsonPersistence(stateFile).loadUsers());
    }

    @Test
    void shouldFailForUnsupportedCurrency() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");
        write(stateFile, """
                {
                  "version" : 1,
                  "users" : [ {
                    "userId" : "customer-001",
                    "passwordHash" : "%s",
                    "accounts" : [ {
                      "type" : "CHECKING",
                      "accountNumber" : "UA000000000000000001",
                      "ownerId" : "customer-001",
                      "currency" : "XYZ",
                      "active" : true,
                      "balance" : 0,
                      "statement" : [ ]
                    } ]
                  } ]
                }
                """.formatted(AuthService.hashPassword(PASSWORD)));

        StorageException exception = assertThrows(StorageException.class,
                () -> new JsonPersistence(stateFile).loadUsers());

        assertTrue(exception.getMessage().contains("XYZ"), exception.getMessage());
    }

    @Test
    void shouldRejectNullArguments() {
        JsonPersistence storage = new JsonPersistence(tempDir.resolve("bank-state.json"));

        assertThrows(NullPointerException.class, () -> storage.saveUsers(null));
        assertThrows(NullPointerException.class, () -> new JsonPersistence(null));
        assertThrows(IllegalArgumentException.class, () -> new JsonPersistence(tempDir.resolve("s.json"), Map.of()));
    }

    @Test
    void shouldNotLeaveTemporaryFilesBehind() throws IOException {
        Path stateFile = tempDir.resolve("bank-state.json");

        new JsonPersistence(stateFile).saveUsers(usersWithAccount());

        try (var files = Files.list(tempDir)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertEquals(List.of("bank-state.json"), names, "Unexpected files in the state directory: " + names);
        }
    }

    @Test
    void shouldKeepTransactionTimestampsAfterRestart() {
        Path stateFile = tempDir.resolve("bank-state.json");
        StorageService storage = new JsonPersistence(stateFile);
        User user = new User(USER_ID, AuthService.hashPassword(PASSWORD));
        CheckingAccount account = new CheckingAccount(CHECKING_NUMBER, USER_ID, Currency.EUR);
        account.deposit(new BigDecimal("10"), "Deposit");
        user.addAccount(account);
        storage.saveUsers(Map.of(USER_ID, user));

        BankAccount.Transaction expected = account.getStatement().get(0);
        BankAccount.Transaction restored = new JsonPersistence(stateFile)
                .loadUsers().get(USER_ID).getAccounts().get(0).getStatement().get(0);

        assertEquals(expected.timestamp(), restored.timestamp());
        assertEquals(expected.amount(), restored.amount());
        assertEquals(expected.comment(), restored.comment());
    }

    /**
     * Builds a user with one active and one blocked checking account.
     */
    private static Map<String, User> usersWithAccount() {
        User user = new User(USER_ID, AuthService.hashPassword(PASSWORD));
        user.addAccount(new CheckingAccount(CHECKING_NUMBER, USER_ID, Currency.EUR));
        user.addAccount(new CheckingAccount(BLOCKED_NUMBER, USER_ID, Currency.UAH));
        user.blockAccount(BLOCKED_NUMBER);

        Map<String, User> users = new LinkedHashMap<>();
        users.put(user.getUserId(), user);
        return users;
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content);
    }
}