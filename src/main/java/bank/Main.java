package bank;

import bank.account.*;
import bank.auth.AuthService;
import bank.auth.AuthenticationException;
import bank.logging.BankLogging;
import bank.logging.SecurityAuditLogger;
import bank.logging.SecurityAuditLogger.Action;
import bank.logging.SecurityAuditLogger.Status;
import bank.storage.JsonPersistence;
import bank.storage.StorageException;
import bank.storage.StorageService;
import bank.user.User;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
    private static final DateTimeFormatter STATEMENT_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

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
            SYSTEM_LOGGER.log(Level.SEVERE, "Could not load the bank state: " + e.getMessage(), e);
            return;
        }
        LOGGER.info("Loaded " + users.size() + " user(s) from " + stateFile.toAbsolutePath());
        SYSTEM_LOGGER.info("Bank state loaded");

        // Resume account numbering after the highest stored number so newly opened
        // accounts never reuse an existing number, also across restarts.
        AccountNumberGenerator accountNumbers = new AccountNumberGenerator(users.values().stream()
                .flatMap(user -> user.getAccounts().stream())
                .map(BankAccount::getAccountNumber)
                .toList());

        AuthService authService = new AuthService(users);
        AtomicReference<String> sessionActor = new AtomicReference<>("system");
        if (users.isEmpty()) {
            boolean saved = mutateAndSave(users, storage, sessionActor,
                    () -> {
                        authService.register(DEMO_USER_ID, DEMO_PASSWORD);
                        sessionActor.set(DEMO_USER_ID);
                    },
                    Action.REGISTER, null, DEMO_USER_ID);
            if (saved) {
                LOGGER.info("No user was found, a demo user was created: "
                        + DEMO_USER_ID
                        + " (choose 'Register a new user' to create your own)");
            }
        }

        try (Scanner scanner = new Scanner(System.in)) {
            User user = entryMenu(authService, scanner, users, storage, sessionActor);
            if (user != null) {
                sessionActor.set(user.getUserId());
                LOGGER.info("Welcome, " + user.getUserId() + "!");
                showAccounts(user);
                runMenu(user, users, scanner, accountNumbers, storage, sessionActor);
            }
        }
    }

    /**
     * Menu shown before a session starts: the customer can log in, register a new user or exit.
     * A freshly registered customer is logged in right away.
     *
     * @return the authenticated user, or {@code null} when the customer chose to exit or the
     *         input ended
     */
    private static User entryMenu(AuthService authService, Scanner scanner, Map<String, User> users,
                                  StorageService storage, AtomicReference<String> sessionActor) {
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
                    User user = register(authService, scanner, users, storage, sessionActor);
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
    private static User register(AuthService authService, Scanner scanner, Map<String, User> users,
                                 StorageService storage, AtomicReference<String> sessionActor) {
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
            User[] registered = new User[1];
            boolean saved = mutateAndSave(users, storage, sessionActor,
                    () -> {
                        registered[0] = authService.register(userId, password);
                        sessionActor.set(registered[0].getUserId());
                    },
                    Action.REGISTER, null, userId);
            if (saved) {
                sessionActor.set(registered[0].getUserId());
                LOGGER.info("User '" + registered[0].getUserId() + "' was registered.");
                return registered[0];
            }
            return null;
        } catch (IllegalArgumentException | IllegalStateException e) {
            SecurityAuditLogger.record(userId, Action.REGISTER, null, Status.FAILURE);
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

    private static void runMenu(User user, Map<String, User> users, Scanner scanner,
                                AccountNumberGenerator accountNumbers, StorageService storage,
                                AtomicReference<String> sessionActor) {
        boolean running = true;
        while (running) {
            LOGGER.info("""

                    -------------- Main menu ---------------
                      1) Show my accounts
                      2) Open a new account
                      3) Block an account
                      4) Unblock an account
                      5) Deposit money
                      6) Withdraw money
                      7) Transfer money
                      8) View account statement
                      9) Exit
                    ----------------------------------------""");
            LOGGER.info("Choose an option: ");

            if (!scanner.hasNextLine()) {
                LOGGER.info("End of input reached.");
                return;
            }

            String option = scanner.nextLine().trim();
            switch (option) {
                case "1" -> showAccounts(user);
                case "2" -> openAccount(user, users, scanner, accountNumbers, storage, sessionActor);
                case "3" -> changeAccountState(user, users, scanner, false, storage, sessionActor);
                case "4" -> changeAccountState(user, users, scanner, true, storage, sessionActor);
                case "5" -> deposit(user, users, scanner, storage, sessionActor);
                case "6" -> withdraw(user, users, scanner, storage, sessionActor);
                case "7" -> transfer(user, users, scanner, storage, sessionActor);
                case "8" -> showStatement(user, scanner);
                case "9" -> running = false;
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
    private static void openAccount(User user, Map<String, User> users, Scanner scanner,
                                    AccountNumberGenerator accountNumbers, StorageService storage,
                                    AtomicReference<String> sessionActor) {
        String currencyInput = readValue(scanner, "Currency (EUR, USD, UAH): ");
        if (currencyInput == null) {
            LOGGER.info("End of input reached.");
            return;
        }
        String typeInput = readValue(scanner, """
                Account type:
                  1) Checking account
                  2) Credit account
                  3) Term deposit account
                Choose an option (1-3): """);
        if (typeInput == null) {
            LOGGER.info("End of input reached.");
            return;
        }

        String creditLimitInput = null;
        String interestRateInput = null;
        String maturityDateInput = null;
        String earlyWithdrawalInput = null;

        try {
            switch (typeInput) {
                case "2" -> {
                    creditLimitInput = readValue(scanner,
                            "Credit limit (maximum overdraft, must be non-negative, e.g. 1000.00): ");
                    if (creditLimitInput == null) {
                        LOGGER.info("End of input reached.");
                        return;
                    }
                    interestRateInput = readValue(scanner,
                            "Annual interest rate in percent (e.g. 12 for 12% per year): ");
                    if (interestRateInput == null) {
                        LOGGER.info("End of input reached.");
                        return;
                    }
                }
                case "3" -> {
                    maturityDateInput = readValue(scanner,
                            "Maturity date (YYYY-MM-DD, today or later): ");
                    if (maturityDateInput == null) {
                        LOGGER.info("End of input reached.");
                        return;
                    }
                    interestRateInput = readValue(scanner,
                            "Annual interest rate as a decimal fraction (e.g. 0.05 for 5% per year): ");
                    if (interestRateInput == null) {
                        LOGGER.info("End of input reached.");
                        return;
                    }
                    earlyWithdrawalInput = readValue(scanner,
                            "Allow early withdrawal before maturity? (y/n): ");
                    if (earlyWithdrawalInput == null) {
                        LOGGER.info("End of input reached.");
                        return;
                    }
                }
                case "1" -> {
                }
                default -> throw new IllegalArgumentException(
                        "Unknown account type '" + typeInput + "', choose 1, 2 or 3");
            }

            Currency currency = parseCurrency(currencyInput);
            BigDecimal creditLimit = creditLimitInput == null ? null : parseNonNegative(creditLimitInput, "Credit limit");
            BigDecimal interestRate = interestRateInput == null ? null : parseNonNegative(interestRateInput, "Interest rate");
            LocalDate maturityDate = maturityDateInput == null ? null : parseMaturityDate(maturityDateInput);
            boolean earlyWithdrawal = earlyWithdrawalInput != null && parseEarlyWithdrawalFlag(earlyWithdrawalInput);

            String accountNumber = accountNumbers.next();
            BankAccount account = switch (typeInput) {
                case "1" -> new CheckingAccount(accountNumber, user.getUserId(), currency);
                case "2" -> new CreditAccount(accountNumber, user.getUserId(), currency, creditLimit, interestRate);
                case "3" -> new DepositAccount(accountNumber, user.getUserId(), currency,
                        maturityDate, earlyWithdrawal, interestRate);
                default -> throw new IllegalStateException("Unknown account type '" + typeInput + "'");
            };
            boolean saved = mutateAndSave(users, storage, sessionActor,
                    () -> user.addAccount(account),
                    Action.OPEN_ACCOUNT, accountNumber);
            if (saved) {
                LOGGER.info("Account " + accountNumber + " (" + JsonPersistence.typeNameOf(account.getClass())
                        + ") was added to " + user.getUserId() + ".");
            }
        } catch (IllegalArgumentException e) {
            SecurityAuditLogger.record(user.getUserId(), Action.OPEN_ACCOUNT, null, Status.FAILURE);
            LOGGER.warning("Could not open the account: " + e.getMessage());
        }
    }

    private static void changeAccountState(User user, Map<String, User> users, Scanner scanner,
                                           boolean active, StorageService storage,
                                           AtomicReference<String> sessionActor) {
        Action action = active ? Action.UNBLOCK_ACCOUNT : Action.BLOCK_ACCOUNT;
        String accountNumber = readValue(scanner, "Account number: ");
        if (accountNumber == null) {
            LOGGER.info("End of input reached.");
            return;
        }

        try {
            boolean saved = mutateAndSave(users, storage, sessionActor,
                    () -> {
                        if (active) {
                            user.unblockAccount(accountNumber);
                        } else {
                            user.blockAccount(accountNumber);
                        }
                    }, action, accountNumber);
            if (saved && active) {
                LOGGER.info("Account " + accountNumber + " was unblocked.");
            } else if (saved) {
                LOGGER.info("Account " + accountNumber + " was blocked.");
            }
        } catch (IllegalArgumentException e) {
            SecurityAuditLogger.record(user.getUserId(), action, accountNumber, Status.FAILURE);
            LOGGER.warning("Could not change the account state: " + e.getMessage());
        }
    }

    private static void deposit(User user, Map<String, User> users, Scanner scanner,
                                StorageService storage, AtomicReference<String> sessionActor) {
        applyAccountAmount(user, scanner, Action.DEPOSIT, "Deposit failed",
                (account, amount) -> account.deposit(amount, "Console deposit"),
                users, storage, sessionActor);
    }

    private static void withdraw(User user, Map<String, User> users, Scanner scanner,
                                 StorageService storage, AtomicReference<String> sessionActor) {
        applyAccountAmount(user, scanner, Action.WITHDRAW, "Withdrawal failed",
                (account, amount) -> account.withdraw(amount, "Console withdrawal"),
                users, storage, sessionActor);
    }

    private static void applyAccountAmount(User user, Scanner scanner, Action action, String failureLabel,
                                           BiConsumer<BankAccount, BigDecimal> operation,
                                           Map<String, User> users, StorageService storage,
                                           AtomicReference<String> sessionActor) {
        String accountNumber = readValue(scanner, "Account number: ");
        String amount = readValue(scanner, "Amount: ");
        if (accountNumber == null || amount == null) {
            LOGGER.info("End of input reached.");
            return;
        }

        Optional<BankAccount> account = user.findAccount(accountNumber);
        if (account.isEmpty()) {
            SecurityAuditLogger.record(user.getUserId(), action, accountNumber, Status.FAILURE);
            LOGGER.warning("User '" + user.getUserId() + "' has no account with number '" + accountNumber + "'.");
            return;
        }

        try {
            BigDecimal parsedAmount = new BigDecimal(amount);
            BankAccount target = account.get();
            boolean saved = mutateAndSave(users, storage, sessionActor,
                    () -> operation.accept(target, parsedAmount),
                    action, accountNumber);
            if (saved) {
                LOGGER.info("New balance of " + accountNumber + ": "
                        + target.getBalance() + " " + target.getCurrency());
            }
        } catch (NumberFormatException e) {
            SecurityAuditLogger.record(user.getUserId(), action, accountNumber, Status.FAILURE);
            LOGGER.warning("'" + amount + "' is not a valid amount.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            SecurityAuditLogger.record(user.getUserId(), action, accountNumber, Status.FAILURE);
            LOGGER.warning(failureLabel + ": " + e.getMessage());
        }
    }

    private static boolean mutateAndSave(Map<String, User> users, StorageService storage,
                                         AtomicReference<String> sessionActor, Runnable mutation,
                                         Action action, String target) {
        return mutateAndSave(users, storage, sessionActor, mutation, action, target, null);
    }

    private static boolean mutateAndSave(Map<String, User> users, StorageService storage,
                                         AtomicReference<String> sessionActor, Runnable mutation,
                                         Action action, String target, String failureActor) {
        Map<String, Boolean> existingUsers = new LinkedHashMap<>();
        Map<String, List<String>> existingAccounts = new LinkedHashMap<>();
        List<AccountSnapshot> accountSnapshots = new ArrayList<>();
        String previousActor = sessionActor.get();

        for (Map.Entry<String, User> entry : users.entrySet()) {
            existingUsers.put(entry.getKey(), true);
            List<String> accountNumbers = new ArrayList<>();
            for (BankAccount account : entry.getValue().getAccounts()) {
                accountNumbers.add(account.getAccountNumber());
                accountSnapshots.add(new AccountSnapshot(account,
                        account.getBalance(), account.isActive(), account.getStatement()));
            }
            existingAccounts.put(entry.getKey(), accountNumbers);
        }

        try {
            mutation.run();
            storage.saveUsers(users);
            SecurityAuditLogger.record(sessionActor.get(), action, target, Status.SUCCESS);
            SecurityAuditLogger.record(sessionActor.get(), Action.SAVE_STATE, null, Status.SUCCESS);
            SYSTEM_LOGGER.info("Bank state saved");
            return true;
        } catch (StorageException e) {
            rollback(users, existingUsers, existingAccounts, accountSnapshots);
            sessionActor.set(previousActor);
            String auditActor = failureActor != null ? failureActor : previousActor;
            SecurityAuditLogger.record(auditActor, action, target, Status.FAILURE);
            SecurityAuditLogger.record(auditActor, Action.SAVE_STATE, null, Status.FAILURE);
            LOGGER.severe("Could not save the bank state");
            SYSTEM_LOGGER.log(Level.SEVERE, "Could not save the bank state", e);
            return false;
        } catch (RuntimeException e) {
            rollback(users, existingUsers, existingAccounts, accountSnapshots);
            throw e;
        }
    }

    private static void rollback(Map<String, User> users, Map<String, Boolean> existingUsers,
                                 Map<String, List<String>> existingAccounts,
                                 List<AccountSnapshot> accountSnapshots) {
        for (User user : users.values()) {
            List<String> originalNumbers = existingAccounts.getOrDefault(user.getUserId(), List.of());
            List<BankAccount> currentAccounts = new ArrayList<>(user.getAccounts());
            for (BankAccount account : currentAccounts) {
                if (!originalNumbers.contains(account.getAccountNumber())) {
                    user.removeAccount(account);
                }
            }
        }
        for (Map.Entry<String, User> entry : new ArrayList<>(users.entrySet())) {
            if (!existingUsers.containsKey(entry.getKey())) {
                users.remove(entry.getKey());
            }
        }
        for (AccountSnapshot snapshot : accountSnapshots) {
            snapshot.account.restoreState(snapshot.balance, snapshot.active, snapshot.transactions);
        }
    }

    private record AccountSnapshot(BankAccount account, BigDecimal balance, boolean active,
                                   List<BankAccount.Transaction> transactions) {
    }

    private static void transfer(User user, Map<String, User> users, Scanner scanner,
                                 StorageService storage, AtomicReference<String> sessionActor) {
        String sourceAccountNumber = readValue(scanner, "Source account number: ");
        String recipientAccountNumber = readValue(scanner, "Recipient account number: ");
        String amount = readValue(scanner, "Amount: ");
        if (sourceAccountNumber == null || recipientAccountNumber == null || amount == null) {
            LOGGER.info("End of input reached.");
            return;
        }

        String auditTarget = sourceAccountNumber + "->" + recipientAccountNumber;
        Optional<BankAccount> sourceAccount = user.findAccount(sourceAccountNumber);
        if (sourceAccount.isEmpty()) {
            SecurityAuditLogger.record(user.getUserId(), Action.TRANSFER, auditTarget, Status.FAILURE);
            LOGGER.warning("User '" + user.getUserId() + "' has no account with number '"
                    + sourceAccountNumber + "'.");
            return;
        }

        Optional<BankAccount> recipientAccount = findAccount(users, recipientAccountNumber);
        if (recipientAccount.isEmpty()) {
            SecurityAuditLogger.record(user.getUserId(), Action.TRANSFER, auditTarget, Status.FAILURE);
            LOGGER.warning("Recipient account '" + recipientAccountNumber + "' was not found.");
            return;
        }

        try {
            BigDecimal transferAmount = new BigDecimal(amount);
            BankAccount source = sourceAccount.get();
            BankAccount recipient = recipientAccount.get();
            boolean saved = mutateAndSave(users, storage, sessionActor,
                    () -> source.transfer(recipient, transferAmount, null),
                    Action.TRANSFER, auditTarget);
            if (saved) {
                LOGGER.info("Transferred " + transferAmount + " " + source.getCurrency()
                        + " from " + sourceAccountNumber + " to " + recipientAccountNumber
                        + ". New balance of " + sourceAccountNumber + ": "
                        + source.getBalance() + " " + source.getCurrency());
            }
        } catch (NumberFormatException e) {
            SecurityAuditLogger.record(user.getUserId(), Action.TRANSFER, auditTarget, Status.FAILURE);
            LOGGER.warning("'" + amount + "' is not a valid amount.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            SecurityAuditLogger.record(user.getUserId(), Action.TRANSFER, auditTarget, Status.FAILURE);
            LOGGER.warning("Transfer failed: " + e.getMessage());
        }
    }

    private static Optional<BankAccount> findAccount(Map<String, User> users, String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return Optional.empty();
        }
        return users.values().stream()
                .map(owner -> owner.findAccount(accountNumber))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static void showStatement(User user, Scanner scanner) {
        String accountNumber = readValue(scanner, "Account number: ");
        if (accountNumber == null) {
            LOGGER.info("End of input reached.");
            return;
        }

        Optional<BankAccount> account = user.findAccount(accountNumber);
        if (account.isEmpty()) {
            LOGGER.warning("User '" + user.getUserId() + "' has no account with number '" + accountNumber + "'.");
            return;
        }

        BankAccount target = account.get();
        List<BankAccount.Transaction> statement = target.getStatement();
        if (statement.isEmpty()) {
            LOGGER.info("Account " + target.getAccountNumber()
                    + " (" + target.getBalance() + " " + target.getCurrency() + ") has no transactions yet.");
            return;
        }

        LOGGER.info("\nStatement for " + target.getAccountNumber()
                + " (balance: " + target.getBalance() + " " + target.getCurrency() + "):");
        for (BankAccount.Transaction transaction : statement) {
            String timestamp = transaction.timestamp() == null
                    ? "unknown"
                    : STATEMENT_TIMESTAMP_FORMAT.format(transaction.timestamp());
            String comment = transaction.comment() == null ? "" : transaction.comment();
            LOGGER.info(String.format("  %s | %s %s | %s",
                    timestamp,
                    formatSignedAmount(transaction.amount()),
                    target.getCurrency(),
                    comment));
        }
    }

    private static String formatSignedAmount(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return amount.signum() > 0 ? "+" + amount.toPlainString() : amount.toPlainString();
    }

    private static Currency parseCurrency(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Currency cannot be blank, use one of EUR, USD, UAH");
        }
        try {
            return Currency.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Currency '" + value + "' is not supported, use one of EUR, USD, UAH");
        }
    }

    private static BigDecimal parseNonNegative(String input, String fieldName) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(input);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + input + "' is not a valid " + fieldName.toLowerCase(Locale.ROOT));
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative");
        }
        return value;
    }

    private static LocalDate parseMaturityDate(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Maturity date cannot be blank");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(input);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + input + "' is not a valid date, use YYYY-MM-DD");
        }
        if (date.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Maturity date must be today or later");
        }
        return date;
    }

    private static boolean parseEarlyWithdrawalFlag(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Early withdrawal flag cannot be blank, use y/n");
        }
        return switch (input.trim().toLowerCase(Locale.ROOT)) {
            case "y", "yes", "true" -> true;
            case "n", "no", "false" -> false;
            default -> throw new IllegalArgumentException("Expected 'y' or 'n', got '" + input + "'");
        };
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
