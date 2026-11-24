package bank.account;

import bank.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
    public synchronized void withdraw(BigDecimal amount, String comment) {
        if (!isActive()) {
            throw new IllegalStateException("Account is blocked or inactive.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Withdrawal amount cannot have more than 2 decimal places: " + amount);
        }

        amount = amount.setScale(2, RoundingMode.DOWN);
        if (balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds: balance is " + getBalance()
                    + " " + getCurrency() + ", requested " + amount + ".");
        }

        this.balance = this.balance.subtract(amount).setScale(6, RoundingMode.DOWN);

        String txComment = (comment != null && !comment.isBlank()) ? comment : "Account withdrawal";
        recordTransaction(amount.negate(), txComment);
    }
}