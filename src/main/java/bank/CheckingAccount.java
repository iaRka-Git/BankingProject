package bank;

import java.math.BigDecimal;

/**
 * Standard checking account.
 *
 * <p>It is the account type used by the console application and the type registered in
 * {@link bank.storage.JsonPersistence} out of the box.</p>
 */
public class CheckingAccount extends BankAccount {

    public CheckingAccount(String accountNumber, String ownerId, Currency currency) {
        super(accountNumber, ownerId, currency);
    }

    /**
     * Withdraws money when the account is active and holds enough funds.
     *
     * @param amount  the amount to withdraw, must be positive
     * @param comment optional description stored in the statement
     * @throws IllegalStateException    when the account is blocked
     * @throws IllegalArgumentException when the amount is not positive or exceeds the balance
     */
    @Override
    public void withdraw(BigDecimal amount, String comment) {
        if (!isActive()) {
            throw new IllegalStateException("Account is blocked or inactive.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive.");
        }
        if (balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds: balance is " + balance
                    + " " + getCurrency() + ", requested " + amount + ".");
        }

        this.balance = this.balance.subtract(amount);

        String txComment = (comment != null && !comment.isBlank()) ? comment : "Account withdrawal";
        recordTransaction(amount.negate(), txComment);
    }
}