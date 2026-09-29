package bank;

import java.math.BigDecimal;

/**
 * Test fixture representing a second account type. It shows how additional account types are
 * registered in the persistence layer, see
 * {@link bank.storage.JsonPersistence#JsonPersistence(java.nio.file.Path, java.util.Map)}.
 */
public class SavingsAccount extends BankAccount {

    public SavingsAccount(String accountNumber, String ownerId, Currency currency) {
        super(accountNumber, ownerId, currency);
    }

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
        recordTransaction(amount.negate(), comment == null ? "Savings withdrawal" : comment);
    }
}