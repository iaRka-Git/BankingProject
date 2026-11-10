package bank.account;
import bank.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Base abstract class for all bank account types.
 */
public abstract class BankAccount {

    /**
     * Immutable record representing a single transaction.
     */
    public record Transaction(
            BigDecimal amount,
            LocalDateTime timestamp,
            String comment
    ) {}

    private final String accountNumber;
    private final String ownerId;
    private final Currency currency;

    private boolean isActive;

    // Protected so subclasses can modify it during specific operations
    protected BigDecimal balance;

    private final List<Transaction> transactions;

    public BankAccount(String accountNumber, String ownerId, Currency currency) {
        this.accountNumber = Objects.requireNonNull(accountNumber, "Account number cannot be null");
        this.ownerId = Objects.requireNonNull(ownerId, "Owner ID cannot be null");
        this.currency = Objects.requireNonNull(currency, "Currency cannot be null");

        this.isActive = true;
        this.balance = BigDecimal.ZERO;
        this.transactions = new ArrayList<>();
    }

    // Getters
    public String getAccountNumber() { return accountNumber; }
    public String getOwnerId() { return ownerId; }
    public Currency getCurrency() { return currency; }

    // Synchronized getters
    public synchronized boolean isActive() {
        return isActive;
    }

    public synchronized BigDecimal getBalance() {
        return balance;
    }

    /**
     * Returns an unmodifiable deep copy of view of the transaction history.
     */
    public synchronized List<Transaction> getStatement() {
        return Collections.unmodifiableList(new ArrayList<>(transactions));
    }

    public synchronized void setActive(boolean active) {
        this.isActive = active;
    }

    /**
     * Restores a previously persisted state: balance, active flag and transaction history.
     *
     * <p>This method exists for the persistence layer only, so that accounts can be restored
     * after a restart with exactly the state they had before. Regular operations must go
     * through {@link #deposit(BigDecimal, String)}, {@link #withdraw(BigDecimal, String)} or
     * {@link #transfer(BankAccount, BigDecimal, String)}; no business rules are checked here
     * because the data has already been validated when it was saved.</p>
     *
     * @param balance      the persisted balance, must not be {@code null}
     * @param isActive     the persisted active flag
     * @param transactions the persisted transaction history, may be {@code null} or empty
     */
    public void restoreState(BigDecimal balance, boolean isActive, List<Transaction> transactions) {
        this.balance = Objects.requireNonNull(balance, "Balance cannot be null");
        this.isActive = isActive;
        this.transactions.clear();
        if (transactions != null) {
            this.transactions.addAll(transactions);
        }
    }

    // Internal synchronized method to log transactions. Called by subclasses.
    protected synchronized void recordTransaction(BigDecimal amount, String comment) {
        transactions.add(new Transaction(amount, LocalDateTime.now(), comment));
    }

    /**
     * Common deposit logic for all account types.
     */
    public synchronized void deposit(BigDecimal amount, String comment) {
        if (!isActive) {
            throw new IllegalStateException("Account is blocked or inactive.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive.");
        }

        this.balance = this.balance.add(amount);

        String txComment = (comment != null && !comment.isBlank()) ? comment : "Account deposit";
        recordTransaction(amount, txComment);
    }

    public void deposit(BigDecimal amount) {
        deposit(amount, "Account deposit");
    }

    /**
     * Specific withdrawal rules must be implemented by subclasses.
     */
    public abstract void withdraw(BigDecimal amount, String comment);

    public void withdraw(BigDecimal amount) {
        withdraw(amount, "Account withdrawal");
    }

    /**
     * Transfers funds to another account.
     */
    public void transfer(BankAccount targetAccount, BigDecimal amount, String comment) {
        if (targetAccount == null) {
            throw new IllegalArgumentException("Target account cannot be null.");
        }
        if (this == targetAccount) {
            throw new IllegalArgumentException("Cannot transfer money to the same account.");
        }
        if (this.currency != targetAccount.getCurrency()) {
            throw new IllegalArgumentException("Cross-currency transfers are not supported yet.");
        }

        // The locking order is determined by the account number: the lower one comes first
        BankAccount firstLock = this.accountNumber.compareTo(targetAccount.getAccountNumber()) < 0 ? this : targetAccount;
        BankAccount secondLock = this.accountNumber.compareTo(targetAccount.getAccountNumber()) < 0 ? targetAccount : this;

        synchronized (firstLock) {
            synchronized (secondLock) {
                String withdrawComment = (comment != null && !comment.isBlank())
                        ? comment
                        : "Transfer to " + targetAccount.getAccountNumber();
                this.withdraw(amount, withdrawComment);

                String depositComment = "Transfer from " + this.accountNumber;
                targetAccount.deposit(amount, depositComment);
            }
        }
    }
}
