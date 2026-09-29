package bank;

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

// example how it currently works, change main once real logic implemented
public class Main {

    private static final Path DEFAULT_STATE_FILE = Path.of("data", "bank-state.json");
    private static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final String DEMO_USER_ID = "customer-001";
    private static final String DEMO_PASSWORD = "secret123";

    public static void main(String[] args) {
        Path stateFile = (args.length > 0 && !args[0].isBlank()) ? Path.of(args[0]) : DEFAULT_STATE_FILE;
        StorageService storage = new JsonPersistence(stateFile);

        System.out.println("Banking console");
        System.out.println("---------------");

        Map<String, User> users;
        try {
            users = new LinkedHashMap<>(storage.loadUsers());
        } catch (StorageException e) {
            System.err.println("Could not load the bank state: " + e.getMessage());
            return;
        }
        System.out.println("Loaded " + users.size() + " user(s) from " + stateFile.toAbsolutePath());

        AuthService authService = new AuthService(users);
        if (users.isEmpty()) {
            authService.register(DEMO_USER_ID, DEMO_PASSWORD);
            System.out.println("No user was found, a demo user was created: "
                    + DEMO_USER_ID + " / " + DEMO_PASSWORD);
        }

        AtomicBoolean stateSaved = new AtomicBoolean(false);
        Runnable saveState = () -> {
            if (stateSaved.compareAndSet(false, true)) {
                try {
                    storage.saveUsers(users);
                    System.out.println("Bank state saved to " + stateFile.toAbsolutePath());
                } catch (StorageException e) {
                    System.err.println("Could not save the bank state: " + e.getMessage());
                }
            }
        };
        // Also persist the state when the JVM is stopped with Ctrl+C or by the IDE
        Runtime.getRuntime().addShutdownHook(new Thread(saveState, "bank-state-saver"));

        try (Scanner scanner = new Scanner(System.in)) {
            User user = login(authService, scanner);
            if (user == null) {
                System.out.println("Authentication failed. Goodbye!");
            } else {
                System.out.println("Welcome, " + user.getUserId() + "!");
                showAccounts(user);
                runMenu(user, scanner);
            }
        }
        saveState.run();
    }
    private static User login(AuthService authService, Scanner scanner) {
        for (int attempt = 1; attempt <= MAX_LOGIN_ATTEMPTS; attempt++) {
            try {
                return authService.promptLogin(scanner);
            } catch (AuthenticationException e) {
                System.out.println("Login failed: " + e.getMessage());
                if (!scanner.hasNextLine()) {
                    System.out.println("End of input reached.");
                    return null;
                }
                System.out.println("Attempts left: " + (MAX_LOGIN_ATTEMPTS - attempt));
            }
        }
        return null;
    }

    private static void runMenu(User user, Scanner scanner) {
        boolean running = true;
        while (running) {
            System.out.println();
            System.out.println("1) Show my accounts");
            System.out.println("2) Open a new checking account");
            System.out.println("3) Block an account");
            System.out.println("4) Unblock an account");
            System.out.println("5) Deposit money");
            System.out.println("6) Exit");
            System.out.print("Choose an option: ");

            if (!scanner.hasNextLine()) {
                System.out.println();
                System.out.println("End of input reached.");
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
                default -> System.out.println("Unknown option '" + option + "', please try again.");
            }
        }
    }

    private static void showAccounts(User user) {
        if (user.getAccountCount() == 0) {
            System.out.println("User '" + user.getUserId() + "' has no accounts yet.");
            return;
        }
        System.out.println("Accounts of " + user.getUserId() + ":");
        for (BankAccount account : user.getAccounts()) {
            System.out.printf("  %s | %s | %s %s | %s | %d transaction(s)%n",
                    account.getAccountNumber(),
                    JsonPersistence.typeNameOf(account.getClass()),
                    account.getBalance(),
                    account.getCurrency(),
                    account.isActive() ? "ACTIVE" : "BLOCKED",
                    account.getStatement().size());
        }
    }
    private static void openAccount(User user, Scanner scanner) {
        String accountNumber = readValue(scanner, "Account number: ");
        String currency = readValue(scanner, "Currency (EUR, USD, UAH): ");
        if (accountNumber == null || currency == null) {
            System.out.println("End of input reached.");
            return;
        }
        if (accountNumber.isBlank()) {
            System.out.println("Account number cannot be blank.");
            return;
        }

        try {
            BankAccount account = new CheckingAccount(accountNumber, user.getUserId(), parseCurrency(currency));
            user.addAccount(account);
            System.out.println("Account " + account.getAccountNumber() + " was added to " + user.getUserId() + ".");
        } catch (IllegalArgumentException e) {
            System.out.println("Could not open the account: " + e.getMessage());
        }
    }

    private static void changeAccountState(User user, Scanner scanner, boolean active) {
        String accountNumber = readValue(scanner, "Account number: ");
        if (accountNumber == null) {
            System.out.println("End of input reached.");
            return;
        }

        try {
            if (active) {
                user.unblockAccount(accountNumber);
                System.out.println("Account " + accountNumber + " was unblocked.");
            } else {
                user.blockAccount(accountNumber);
                System.out.println("Account " + accountNumber + " was blocked.");
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Could not change the account state: " + e.getMessage());
        }
    }

    private static void deposit(User user, Scanner scanner) {
        String accountNumber = readValue(scanner, "Account number: ");
        String amount = readValue(scanner, "Amount: ");
        if (accountNumber == null || amount == null) {
            System.out.println("End of input reached.");
            return;
        }

        Optional<BankAccount> account = user.findAccount(accountNumber);
        if (account.isEmpty()) {
            System.out.println("User '" + user.getUserId() + "' has no account with number '" + accountNumber + "'.");
            return;
        }

        try {
            account.get().deposit(new BigDecimal(amount), "Console deposit");
            System.out.println("New balance of " + accountNumber + ": "
                    + account.get().getBalance() + " " + account.get().getCurrency());
        } catch (NumberFormatException e) {
            System.out.println("'" + amount + "' is not a valid amount.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.out.println("Deposit failed: " + e.getMessage());
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
     * Prints the prompt and reads one line of console input.
     *
     * @return the trimmed line, or {@code null} when the input stream is exhausted
     */
    private static String readValue(Scanner scanner, String prompt) {
        System.out.print(prompt);
        if (!scanner.hasNextLine()) {
            return null;
        }
        return scanner.nextLine().trim();
    }
}
