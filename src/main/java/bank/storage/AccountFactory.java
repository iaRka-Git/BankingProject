package bank.storage;

import bank.BankAccount;
import bank.Currency;

/**
 * Creates a concrete {@link BankAccount} instance while the persisted state is being restored.
 *
 * <p>{@link BankAccount} is abstract, so every account type that has to survive a restart needs
 * a factory. Factories are registered under the type name of the account, which is derived from
 * its simple class name by dropping the conventional {@code Account} suffix and upper casing the
 * rest: {@code CheckingAccount} is stored as {@code CHECKING}, {@code SavingsAccount} as
 * {@code SAVINGS}. That name is also the {@code type} field written to JSON.</p>
 */
@FunctionalInterface
public interface AccountFactory {

    /**
     * Creates an empty account that is ready to receive the persisted state.
     *
     * @param accountNumber the persisted account number
     * @param ownerId       the persisted owner id
     * @param currency      the persisted currency
     * @return a new account instance
     */
    BankAccount create(String accountNumber, String ownerId, Currency currency);
}