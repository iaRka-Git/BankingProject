package bank.storage;

import bank.BankAccount;
import bank.CheckingAccount;
import bank.Currency;
import bank.User;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * JSON implementation of {@link StorageService} based on Jackson.
 *
 * <p>The document structure is intentionally decoupled from the domain classes (the record DTOs
 * at the bottom of this class), so that changes in the domain model do not silently change the
 * meaning of already saved files. A {@code version} field keeps track of the schema:</p>
 *
 * <pre>
 * {
 *   "version" : 1,
 *   "users" : [ {
 *     "userId" : "customer-001",
 *     "passwordHash" : "610e700a...",
 *     "accounts" : [ {
 *       "type" : "CHECKING",
 *       "accountNumber" : "UA000000000000000001",
 *       "ownerId" : "customer-001",
 *       "currency" : "EUR",
 *       "active" : true,
 *       "balance" : 149.50,
 *       "statement" : [ {
 *         "amount" : -50.50,
 *         "timestamp" : "2026-09-28T10:15:30",
 *         "comment" : "Rent"
 *       } ]
 *     } ]
 *   } ]
 * }
 * </pre>
 *
 * <p>Saving is atomic: the document is first written to a temporary file next to the target and
 * then moved over it, so an interrupted shutdown cannot leave a half written file behind.</p>
 */
public class JsonPersistence implements StorageService {

    // Schema version written by this class
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String ACCOUNT_SUFFIX = "Account";

    private final Path stateFile;
    private final Map<String, AccountFactory> accountFactories;
    private final ObjectMapper objectMapper;

    /**
     * Creates persistence for the given file, knowing only the built-in account type
     * ({@code CHECKING}).
     *
     * @param stateFile the JSON file the bank state is stored in
     */
    public JsonPersistence(Path stateFile) {
        this(stateFile, defaultAccountFactories());
    }

    /**
     * Creates persistence for the given file with additional or overriding account types.
     *
     * @param stateFile        the JSON file the bank state is stored in, missing parent
     *                         directories are created automatically
     * @param accountFactories factories keyed by the type name of the account, e.g.
     *                         {@code "CHECKING"} or {@code "SAVINGS"}
     */
    public JsonPersistence(Path stateFile, Map<String, AccountFactory> accountFactories) {
        this.stateFile = Objects.requireNonNull(stateFile, "State file path cannot be null")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(accountFactories, "Account factories cannot be null");
        if (accountFactories.isEmpty()) {
            throw new IllegalArgumentException("At least one account factory is required");
        }

        Map<String, AccountFactory> normalizedFactories = new LinkedHashMap<>();
        accountFactories.forEach((type, factory) -> normalizedFactories.put(
                Objects.requireNonNull(type, "Account type cannot be null").trim().toUpperCase(Locale.ROOT),
                Objects.requireNonNull(factory, "Account factory cannot be null")));
        this.accountFactories = Map.copyOf(normalizedFactories);

        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // The account types that are known without any configuration: {@code CHECKING}.
    public static Map<String, AccountFactory> defaultAccountFactories() {
        return Map.of(typeNameOf(CheckingAccount.class), CheckingAccount::new);
    }
    @Override
    public Map<String, User> loadUsers() {
        Map<String, User> users = new LinkedHashMap<>();
        if (!Files.exists(stateFile)) {
            return users;
        }

        StateDocument document;
        try {
            document = objectMapper.readValue(stateFile.toFile(), StateDocument.class);
        } catch (IOException e) {
            throw new StorageException("Cannot read bank state from '" + stateFile + "'", e);
        }
        if (document == null) {
            return users;
        }
        if (document.version() > CURRENT_SCHEMA_VERSION) {
            throw new StorageException("Bank state in '" + stateFile + "' uses schema version " + document.version()
                    + " but only version " + CURRENT_SCHEMA_VERSION + " is supported");
        }
        if (document.users() != null) {
            for (UserRecord record : document.users()) {
                User user = toUser(record);
                users.put(user.getUserId(), user);
            }
        }
        return users;
    }

    @Override
    public void saveUsers(Map<String, User> users) {
        Objects.requireNonNull(users, "Users cannot be null");

        List<UserRecord> records = users.values().stream()
                .filter(Objects::nonNull)
                .map(this::toUserRecord)
                .toList();
        StateDocument document = new StateDocument(CURRENT_SCHEMA_VERSION, records);

        Path directory = stateFile.getParent() != null ? stateFile.getParent() : Path.of(".");
        Path tempFile = null;
        try {
            Files.createDirectories(directory);
            tempFile = Files.createTempFile(directory, "bank-state-", ".json.tmp");
            objectMapper.writeValue(tempFile.toFile(), document);
            move(tempFile, stateFile);
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new StorageException("Cannot write bank state to '" + stateFile + "'", e);
        }
    }

    // --- START of mapping ---
    // --- Domain -> JSON ---

    private UserRecord toUserRecord(User user) {
        return new UserRecord(user.getUserId(), user.getPasswordHash(), user.getAccounts().stream()
                .map(this::toAccountRecord)
                .toList());
    }

    private AccountRecord toAccountRecord(BankAccount account) {
        return new AccountRecord(resolveType(account), account.getAccountNumber(), account.getOwnerId(),
                account.getCurrency().name(), account.isActive(), account.getBalance(), account.getStatement().stream()
                .map(JsonPersistence::toTransactionRecord)
                .toList());
    }

    private static TransactionRecord toTransactionRecord(BankAccount.Transaction transaction) {
        return new TransactionRecord(transaction.amount(), formatTimestamp(transaction.timestamp()), transaction.comment());
    }

    /**
     * Resolves the registered type name of an account, also accepting subclasses of a
     * registered type.
     *
     * @throws StorageException when neither the account type nor one of its supertypes is registered
     */
    private String resolveType(BankAccount account) {
        Class<?> type = account.getClass();
        while (type != null && BankAccount.class.isAssignableFrom(type)) {
            String name = typeNameOf(type);
            if (accountFactories.containsKey(name)) {
                return name;
            }
            type = type.getSuperclass();
        }
        throw new StorageException("Account " + account.getAccountNumber() + " of type '"
                + account.getClass().getName() + "' cannot be saved because no account factory is registered "
                + "(known types: " + accountFactories.keySet() + ")");
    }
    // --- JSON -> Domain ---

    private User toUser(UserRecord record) {
        if (record == null) {
            throw new StorageException("Bank state in '" + stateFile + "' contains an empty user record");
        }
        User user = new User(requireText(record.userId(), "userId"), requireText(record.passwordHash(), "passwordHash"));
        if (record.accounts() != null) {
            for (AccountRecord accountRecord : record.accounts()) {
                user.addAccount(toAccount(accountRecord));
            }
        }
        return user;
    }

    private BankAccount toAccount(AccountRecord record) {
        if (record == null) {
            throw new StorageException("Bank state in '" + stateFile + "' contains an empty account record");
        }

        String type = requireText(record.type(), "type").toUpperCase(Locale.ROOT);
        AccountFactory factory = accountFactories.get(type);
        if (factory == null) {
            throw new StorageException("Account type '" + type + "' from '" + stateFile
                    + "' is not supported (known types: " + accountFactories.keySet() + ")");
        }

        BankAccount account = factory.create(requireText(record.accountNumber(), "accountNumber"),
                requireText(record.ownerId(), "ownerId"), parseCurrency(record.currency(), record.accountNumber()));

        List<BankAccount.Transaction> statement = record.statement() == null
                ? List.of()
                : record.statement().stream().map(JsonPersistence::toTransaction).toList();

        account.restoreState(record.balance() == null ? BigDecimal.ZERO : record.balance(),
                record.active(), statement);
        return account;
    }

    private static BankAccount.Transaction toTransaction(TransactionRecord record) {
        if (record == null) {
            throw new StorageException("Transaction record is missing");
        }
        if (record.amount() == null) {
            throw new StorageException("Transaction amount is missing");
        }
        return new BankAccount.Transaction(record.amount(), parseTimestamp(record.timestamp()),
                record.comment() == null ? "" : record.comment());
    }
    private static Currency parseCurrency(String currency, String accountNumber) {
        if (currency == null || currency.isBlank()) {
            throw new StorageException("Currency of account '" + accountNumber + "' is missing");
        }
        try {
            return Currency.valueOf(currency.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new StorageException("Currency '" + currency + "' of account '" + accountNumber
                    + "' is not supported", e);
        }
    }

    // --- END of mapping ---

    // --- UTILS ---
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new StorageException("Required field '" + field + "' is missing or blank in the persisted state");
        }
        return value;
    }

    private static String formatTimestamp(LocalDateTime timestamp) {
        return timestamp == null ? null : TIMESTAMP_FORMAT.format(timestamp);
    }

    private static LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw new StorageException("Transaction timestamp is missing");
        }
        try {
            return LocalDateTime.parse(timestamp, TIMESTAMP_FORMAT);
        } catch (DateTimeParseException e) {
            throw new StorageException("Transaction timestamp '" + timestamp + "' is not a valid ISO-8601 date-time", e);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // nothing else can be done here, the temporary file is left for the OS to clean up
        }
    }

    /**
     * Derives the persisted type name of an account class from its simple class name:
     * {@code CheckingAccount} becomes {@code CHECKING} and {@code SavingsAccount} becomes
     * {@code SAVINGS}. The conventional {@code Account} suffix is dropped, everything else is
     * upper cased. Save and load always use this same function, so a type name can never be
     * written under one spelling and looked up under another.
     */
    public static String typeNameOf(Class<?> accountType) {
        String simpleName = accountType.getSimpleName();
        if (simpleName.length() > ACCOUNT_SUFFIX.length() && simpleName.endsWith(ACCOUNT_SUFFIX)) {
            simpleName = simpleName.substring(0, simpleName.length() - ACCOUNT_SUFFIX.length());
        }
        return simpleName.toUpperCase(Locale.ROOT);
    }

    // --- JSON schema ---

    // Root of the persisted document
    private record StateDocument(int version, List<UserRecord> users) {}

    private record UserRecord(String userId, String passwordHash, List<AccountRecord> accounts) {}

    private record AccountRecord(String type,
                                 String accountNumber,
                                 String ownerId,
                                 String currency,
                                 boolean active,
                                 BigDecimal balance,
                                 List<TransactionRecord> statement) {}

    private record TransactionRecord(BigDecimal amount, String timestamp, String comment) {}
}