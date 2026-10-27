package bank;

import bank.logging.BankLogging;
import bank.logging.SecurityAuditLogger;
import bank.logging.SecurityAuditLogger.Action;
import bank.logging.SecurityAuditLogger.Status;
import bank.storage.JsonPersistence;
import bank.storage.StorageException;
import bank.storage.StorageService;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

// example how it currently works, change main once real logic implemented
public class Main {

    private static final Logger LOGGER = BankLogging.getConsoleLogger(Main.class);
    private static final Logger SYSTEM_LOGGER = BankLogging.getLogger(Main.class);

    private static final Path DEFAULT_STATE_FILE = Path.of("data", "bank-state.json");
    private static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final String DEMO_USER_ID = "customer-001";
    private static final String DEMO_PASSWORD = "secret123";

    public static void main(String[] args) {
        Path stateFile = (args.length > 0 && !args[0].isBlank()) ? Path.of(args[0]) : DEFAULT_STATE_FILE;
        StorageService storage = new JsonPersistence(stateFile);

        LOGGER.info("""
                ========================================
                            Banking console
                ========================================""");

        Map<String, User> users;
        try {
            users = new LinkedHashMap<>(storage.loadUsers());
        } catch (StorageException e) {
            SYSTEM_LOGGER.log(Level.SEVERE, "Could not load the bank state", e);
            return;
        }
        LOGGER.info("Loaded " + users.size() + " user(s) from " + stateFile.toAbsolutePath());
        SYSTEM_LOGGER.info("Bank state loaded");

        AuthService authService = new AuthService(users);
        if (users.isEmpty()) {
            authService.register(DEMO_USER_ID, DEMO_PASSWORD);
            LOGGER.info("No user was found, a demo user was created: "
                    + DEMO_USER_ID
                    + " (choose 'Register a new user' to create your own)");
        }

        AtomicBoolean stateSaved =   new AtomicBoolean(false);
        AtomicReference<String> sessionActor = new AtomicReference<>("system");
        Runnable saveState = () -> {
            if (stateSaved.compareAndSet(false, true)) {
                try {
                    storage.saveUsers(users);
                    SecurityAuditLogger.record(sessionActor.get(), Action.SAVE_STATE, null, Status.SUCCESS);
                    LOGGER.info("Bank state saved to " + stateFile.toAbsolutePath());
                    SYSTEM_LOGGER.info("Bank state saved");
                } catch (StorageException e) {
                    SecurityAuditLogger.record(sessionActor.get(), Action.SAVE_STATE, null, Status.FAILURE);
                    SYSTEM_LOGGER.log(Level.SEVERE, "Could not save the bank state", e);
                }
            }
        };
        // Also persist the state when the JVM is stopped with Ctrl+C or by the IDE
        Runtime.getRuntime().addShutdownHook(new Thread(saveState, "bank-state-saver"));

        try (Scanner scanner = new Scanner(System.in)) {
            User user = entryMenu(authService, scanner);
            if (user != null) {
                sessionActor.set(user.getUserId());
                LOGGER.info("Welcome, " + user.getUserId() + "!");
                showAccounts(user);
                runMenu(user, scanner);
            }
        }
        saveState.run();
    }

    /**
     * Menu shown before a session starts: the customer can log in, register a new user or exit.
     * A freshly registered customer is logged in right away.
     *
     * @return the authenticated user, or {@code null} when the customer chose to exit or the
     *         input ended
     */
    private static User entryMenu(AuthService authService, Scanner scanner) {
        while (true) {
            LOGGER.info("""

                    -------------- Sign in -----------------
                      1) Log in
                      2) Register a new user
                      3) Exit
                    ----------------------------------------""");
            LOGGER.info("Choose an option: ");

            if (!scanner.hasNextLine()) {
                LOGGER.info("End of input reached.");
                return null;
            }

            switch (scanner.nextLine().trim()) {
                case "1" -> {
                    User user = login(authService, scanner);
                    if (user != null) {
                        return user;
                    }
                }
                case "2" -> {
                    User user = register(authService, scanner);
                    if (user != null) {
                        return user;
                    }
                }
                case "3" -> {
                    return null;
                }
                default -> LOGGER.warning("Unknown option, please try again.");
            }
        }
    }

    /**
     * Reads the data of a new customer, creates the user and logs the customer in.
     *
     * @return the newly registered user, or {@code null} when the input was invalid, the user id
     *         was already taken or the input ended
     */
    private static User register(AuthService authService, Scanner scanner) {
        String userId = readValue(scanner, "New user ID: ");
        if (userId == null) {
            LOGGER.info("End of input reached.");
            return null;
        }
        String password = readValue(scanner, "Password: ");
        String passwordConfirmation = readValue(scanner, "Repeat password: ");
        if (password == null || passwordConfirmation == null) {
            LOGGER.info("End of input reached.");
            return null;
        }
        if (userId.isBlank()) {
            LOGGER.warning("User ID cannot be blank.");
            return null;
        }
        if (password.isBlank()) {
            LOGGER.warning("Password cannot be blank.");
            return null;
        }
        if (!password.equals(passwordConfirmation)) {
            LOGGER.warning("Passwords do not match, nothing was registered.");
            return null;
        }

        try {
            User user = authService.register(userId, password);
            LOGGER.info("User '" + user.getUserId() + "' was registered.");
            return user;
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOGGER.warning("Could not register the user: " + e.getMessage());
            return null;
        }
    }

    private static User login(AuthService authService, Scanner scanner) {
        for (int attempt = 1; attempt <= MAX_LOGIN_ATTEMPTS; attempt++) {
            try {
                return authService.promptLogin(scanner);
            } catch (AuthenticationException e) {
                LOGGER.warning("Login failed: " + e.getMessage());
                if (!scanner.hasNextLine()) {
                    LOGGER.info("End of input reached.");
                    return null;
                }
                LOGGER.info("Attempts left: " + (MAX_LOGIN_ATTEMPTS - attempt));
            }
        }
        return null;
    }

    private static void runMenu(User user, Scanner scanner) {
        boolean running = true;
        while (running) {
            LOGGER.info("""

                    -------------- Main menu ---------------
                      1) Show my accounts
                      2) Open a new checking account
                      3) Block an account
                      4) Unblock an account
                      5) Deposit money
                      6) Exit
                    ----------------------------------------""");
            LOGGER.info("Choose an option: ");

            if (!scanner.hasNextLine()) {
                LOGGER.info("End of input reached.");
                return;
            }

            String option = scanner.nextLine().trim();
            switch (option) {
                case "1" -> showAccounts(user);
                case "2" -> openAccount(user, scanner);
                case "3" -> changeAccountState(user, scanner, false);
                case "4" -> changeAccountState(user, scanner, true);
                case "5" -> deposit(user, scanner);
                case "6" -> running = false;
                default -> LOGGER.warning("Unknown option '" + option + "', please try again.");
            }
        }
    }

    private static void showAccounts(User user) {
        if (user.getAccountCount() == 0) {
            LOGGER.info("User '" + user.getUserId() + "' has no accounts yet.");
            return;
        }
        LOGGER.info("\nAccounts of " + user.getUserId() + ":");
        for (BankAccount account : user.getAccounts()) {
            LOGGER.info(String.format("  %s | %s | %s %s | %s | %d transaction(s)",
                    account.getAccountNumber(),
                    JsonPersistence.typeNameOf(account.getClass()),
                    account.getBalance(),
                    account.getCurrency(),
                    account.isActive() ? "ACTIVE" : "BLOCKED",
                    account.getStatement().size()));
        }
    }
    private static void openAccount(User user, Scanner scanner) {
        String accountNumber = readValue(scanner, "Account number: ");
        String currency = readValue(scanner, "Currency (EUR, USD, UAH): ");
        if (accountNumber == null || currency == null) {
            LOGGER.info("End of input reached.");
            return;
        }
        if (accountNumber.isBlank()) {
            SecurityAuditLogger.record(user.getUserId(), Action.OPEN_ACCOUNT, null, Status.FAILURE);
            LOGGER.warning("Account number cannot be blank.");
            return;
        }

        try {
            BankAccount account = new CheckingAccount(accountNumber, user.getUserId(), parseCurrency(currency));
            user.addAccount(account);
            SecurityAuditLogger.record(user.getUserId(), Action.OPEN_ACCOUNT, accountNumber, Status.SUCCESS);
            LOGGER.info("Account " + account.getAccountNumber() + " was added to " + user.getUserId() + ".");
        } catch (IllegalArgumentException e) {
            SecurityAuditLogger.record(user.getUserId(), Action.OPEN_ACCOUNT, accountNumber, Status.FAILURE);
            LOGGER.warning("Could not open the account: " + e.getMessage());
        }
    }

    private static void changeAccountState(User user, Scanner scanner, boolean active) {
        Action action = active ? Action.UNBLOCK_ACCOUNT : Action.BLOCK_ACCOUNT;
        String accountNumber = readValue(scanner, "Account number: ");
        if (accountNumber == null) {
            LOGGER.info("End of input reached.");
            return;
        }

        try {
            if (active) {
                user.unblockAccount(accountNumber);
                SecurityAuditLogger.record(user.getUserId(), action, accountNumber, Status.SUCCESS);
                LOGGER.info("Account " + accountNumber + " was unblocked.");
            } else {
                user.blockAccount(accountNumber);
                SecurityAuditLogger.record(user.getUserId(), action, accountNumber, Status.SUCCESS);
                LOGGER.info("Account " + accountNumber + " was blocked.");
            }
        } catch (IllegalArgumentException e) {
            SecurityAuditLogger.record(user.getUserId(), action, accountNumber, Status.FAILURE);
            LOGGER.warning("Could not change the account state: " + e.getMessage());
        }
    }

    private static void deposit(User user, Scanner scanner) {
        String accountNumber = readValue(scanner, "Account number: ");
        String amount = readValue(scanner, "Amount: ");
        if (accountNumber == null || amount == null) {
            LOGGER.info("End of input reached.");
            return;
        }

        Optional<BankAccount> account = user.findAccount(accountNumber);
        if (account.isEmpty()) {
            SecurityAuditLogger.record(user.getUserId(), Action.DEPOSIT, accountNumber, Status.FAILURE);
            LOGGER.warning("User '" + user.getUserId() + "' has no account with number '" + accountNumber + "'.");
            return;
        }

        try {
            account.get().deposit(new BigDecimal(amount), "Console deposit");
            SecurityAuditLogger.record(user.getUserId(), Action.DEPOSIT, accountNumber, Status.SUCCESS);
            LOGGER.info("New balance of " + accountNumber + ": "
                    + account.get().getBalance() + " " + account.get().getCurrency());
        } catch (NumberFormatException e) {
            SecurityAuditLogger.record(user.getUserId(), Action.DEPOSIT, accountNumber, Status.FAILURE);
            LOGGER.warning("'" + amount + "' is not a valid amount.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            SecurityAuditLogger.record(user.getUserId(), Action.DEPOSIT, accountNumber, Status.FAILURE);
            LOGGER.warning("Deposit failed: " + e.getMessage());
        }
    }

    private static Currency parseCurrency(String value) {
        try {
            return Currency.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Currency '" + value + "' is not supported, use one of EUR, USD, UAH");
        }
    }

    /**
     * Logs the prompt and reads one line of console input.
     *
     * @return the trimmed line, or {@code null} when the input stream is exhausted
     */
    private static String readValue(Scanner scanner, String prompt) {
        LOGGER.info(prompt);
        if (!scanner.hasNextLine()) {
            return null;
        }
        return scanner.nextLine().trim();
    }
}
