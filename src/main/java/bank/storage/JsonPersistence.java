package bank.storage;

import bank.BankAccount;
import bank.CheckingAccount;
import bank.CreditAccount;
import bank.Currency;
import bank.User;
import bank.account.DepositAccount;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
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

    private static final DateTimeFormatter MATURITY_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

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
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // The account types that are known without any configuration:
    // {@code CHECKING}, {@code CREDIT} and {@code DEPOSIT}.
    public static Map<String, AccountFactory> defaultAccountFactories() {
        return Map.of(
                typeNameOf(CheckingAccount.class), CheckingAccount::new,
                typeNameOf(CreditAccount.class), (number, owner, currency) ->
                        new CreditAccount(number, owner, currency, BigDecimal.ZERO, BigDecimal.ZERO),
                typeNameOf(DepositAccount.class), (number, owner, currency) ->
                        new DepositAccount(number, owner, currency, LocalDate.now(), false, BigDecimal.ZERO)
        );
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
            Map<String, String> accountOwners = new HashMap<>();
            for (UserRecord record : document.users()) {
                User user = toUser(record, accountOwners);
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
                account.getCurrency().name(), account.isActive(), account.getBalance(),
                statementRecords(account),
                creditLimitOf(account), interestRateOf(account), maturityDateOf(account),
                account instanceof DepositAccount deposit ? deposit.isEarlyWithdrawalAllowed() : null);
    }

    private static List<TransactionRecord> statementRecords(BankAccount account) {
        return account.getStatement().stream()
                .map(JsonPersistence::toTransactionRecord)
                .toList();
    }

    private static BigDecimal creditLimitOf(BankAccount account) {
        return account instanceof CreditAccount credit ? credit.getCreditLimit() : null;
    }

    private static BigDecimal interestRateOf(BankAccount account) {
        if (account instanceof CreditAccount credit) {
            return credit.getInterestRate();
        }
        if (account instanceof DepositAccount deposit) {
            return deposit.getInterestRate();
        }
        return null;
    }

    private static String maturityDateOf(BankAccount account) {
        if (account instanceof DepositAccount deposit) {
            return deposit.getMaturityDate().format(MATURITY_DATE_FORMAT);
        }
        return null;
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

    private User toUser(UserRecord record, Map<String, String> accountOwners) {
        if (record == null) {
            throw new StorageException("Bank state in '" + stateFile + "' contains an empty user record");
        }
        User user = new User(requireText(record.userId(), "userId"), requireText(record.passwordHash(), "passwordHash"));
        if (record.accounts() != null) {
            for (AccountRecord accountRecord : record.accounts()) {
                BankAccount account = toAccount(accountRecord);
                String number = account.getAccountNumber();
                String previousOwner = accountOwners.putIfAbsent(number, user.getUserId());
                if (previousOwner != null) {
                    throw new StorageException("Duplicate account number '" + number + "' in '" + stateFile
                            + "' (user records '" + previousOwner + "' and '" + user.getUserId() + "')");
                }
                user.addAccount(account);
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

        String accountNumber = requireText(record.accountNumber(), "accountNumber");
        BankAccount account = factory.create(accountNumber,
                requireText(record.ownerId(), "ownerId"), parseCurrency(record.currency(), accountNumber));

        restoreTypeSpecifics(account, record);

        List<BankAccount.Transaction> statement = record.statement() == null
                ? List.of()
                : record.statement().stream().map(JsonPersistence::toTransaction).toList();

        account.restoreState(record.balance() == null ? BigDecimal.ZERO : record.balance(),
                record.active(), statement);
        return account;
    }

    /**
     * Reinstates the type-specific terms persisted for credit and deposit accounts.
     * Checking accounts have no type-specific terms, so nothing happens for them.
     *
     * @throws StorageException when a required term is missing or invalid
     */
    private static void restoreTypeSpecifics(BankAccount account, AccountRecord record) {
        String accountNumber = record.accountNumber();
        try {
            if (account instanceof CreditAccount credit) {
                credit.restoreCreditTerms(
                        requireDecimal(record.creditLimit(), "creditLimit", accountNumber),
                        requireDecimal(record.interestRate(), "interestRate", accountNumber));
            } else if (account instanceof DepositAccount deposit) {
                deposit.restoreDepositTerms(
                        parseMaturityDate(record.maturityDate(), accountNumber),
                        parseEarlyWithdrawalFlag(record.allowEarlyWithdrawal(), accountNumber),
                        requireDecimal(record.interestRate(), "interestRate", accountNumber));
            }
        } catch (IllegalArgumentException e) {
            throw new StorageException("Invalid persisted terms for account '" + accountNumber + "': " + e.getMessage(), e);
        }
    }

    private static BigDecimal requireDecimal(BigDecimal value, String field, String accountNumber) {
        if (value == null) {
            throw new StorageException("Required field '" + field + "' is missing or blank in the persisted state "
                    + "of account '" + accountNumber + "'");
        }
        return value;
    }

    private static LocalDate parseMaturityDate(String value, String accountNumber) {
        if (value == null || value.isBlank()) {
            throw new StorageException("Required field 'maturityDate' is missing or blank in the persisted state "
                    + "of account '" + accountNumber + "'");
        }
        try {
            return LocalDate.parse(value, MATURITY_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new StorageException("Maturity date '" + value + "' of account '" + accountNumber
                    + "' is not a valid ISO-8601 date", e);
        }
    }

    private static boolean parseEarlyWithdrawalFlag(Boolean value, String accountNumber) {
        if (value == null) {
            throw new StorageException("Required field 'allowEarlyWithdrawal' is missing in the persisted state "
                    + "of account '" + accountNumber + "'");
        }
        return value;
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
                                 List<TransactionRecord> statement,
                                 BigDecimal creditLimit,
                                 BigDecimal interestRate,
                                 String maturityDate,
                                 Boolean allowEarlyWithdrawal) {}

    private record TransactionRecord(BigDecimal amount, String timestamp, String comment) {}
}