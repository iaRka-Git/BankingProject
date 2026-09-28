package bank;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Supported account currencies.
 */


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

    // --- Getters ---
    public String getAccountNumber() { return accountNumber; }
    public String getOwnerId() { return ownerId; }
    public Currency getCurrency() { return currency; }
    public boolean isActive() { return isActive; }
    public BigDecimal getBalance() { return balance; }

    /**
     * Returns an unmodifiable view of the transaction history.
     */
    public List<Transaction> getStatement() {
        return Collections.unmodifiableList(transactions);
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    /**
     * Internal method to log transactions. Called by subclasses.
     */
    protected void recordTransaction(BigDecimal amount, String comment) {
        transactions.add(new Transaction(amount, LocalDateTime.now(), comment));
    }

    /**
     * Common deposit logic for all account types.
     */
    public void deposit(BigDecimal amount, String comment) {
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
        if (this.currency != targetAccount.getCurrency()) {
            throw new IllegalArgumentException("Cross-currency transfers are not supported yet.");
        }

        // 1. Withdraw from this account
        String withdrawComment = (comment != null && !comment.isBlank())
                ? comment
                : "Transfer to " + targetAccount.getAccountNumber();
        this.withdraw(amount, withdrawComment);

        // 2. Deposit to target account
        String depositComment = "Transfer from " + this.accountNumber;
        targetAccount.deposit(amount, depositComment);
    }
}
