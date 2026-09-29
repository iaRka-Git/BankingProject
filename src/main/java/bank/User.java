package bank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Customer
 *
 * <p>A user is identified by a unique {@code userId}, owns a collection of
 * {@link BankAccount bank accounts} and stores only the SHA-256 hash of the password
 * (see {@link AuthService#hashPassword(String)}) - never the password itself.</p>
 */
// not divided by layers yet: controller/layer/repository. User functionality defined here
public class User {

    private final String userId;

    // SHA-256 hash of the customer password
    private final String passwordHash;

    private final List<BankAccount> accounts;

    public User(String userId, String passwordHash) {
        this.userId = Objects.requireNonNull(userId, "User ID cannot be null");
        if (userId.isBlank()) {throw new IllegalArgumentException("User ID cannot be blank");}
        this.passwordHash = Objects.requireNonNull(passwordHash, "Password hash cannot be null");
        if (passwordHash.isBlank()) {throw new IllegalArgumentException("Password hash cannot be blank");}

        this.accounts = new ArrayList<>();
    }

    // --- Getters ---

    public String getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    // Returns an unmodifiable view of the accounts owned by this user.
    public List<BankAccount> getAccounts() {
        return Collections.unmodifiableList(accounts);
    }

    public int getAccountCount() {
        return accounts.size();
    }

    /**
     * Finds one of the user's accounts by its account number.
     *
     * @param accountNumber the account number to look for
     * @return the matching account or {@link Optional#empty()} when the user has no such account
     */
    public Optional<BankAccount> findAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return Optional.empty();
        }
        return accounts.stream()
                .filter(account -> accountNumber.equals(account.getAccountNumber()))
                .findFirst();
    }

    /**
     * Associates a new bank account with this user.
     *
     * @param account the account to add, it has to be owned by this user
     * @throws IllegalArgumentException when the account belongs to another user or is already assigned
     */
    public void addAccount(BankAccount account) {
        Objects.requireNonNull(account, "Account cannot be null");
        if (!userId.equals(account.getOwnerId())) {
            throw new IllegalArgumentException("Account " + account.getAccountNumber()
                    + " belongs to user '" + account.getOwnerId() + "' and cannot be assigned to '" + userId + "'");
        }
        if (findAccount(account.getAccountNumber()).isPresent()) {
            throw new IllegalArgumentException("Account " + account.getAccountNumber()
                    + " is already assigned to user '" + userId + "'");
        }
        accounts.add(account);
    }

    /**
     * Blocks one of the user's accounts by setting its {@code isActive} flag to {@code false}.
     *
     * @param accountNumber number of the account to block
     * @throws IllegalArgumentException when the user has no account with the given number
     */
    public void blockAccount(String accountNumber) {
        setAccountActive(accountNumber, false);
    }

    /**
     * Unblocks one of the user's accounts by setting its {@code isActive} flag back to {@code true}.
     *
     * @param accountNumber number of the account to unblock
     * @throws IllegalArgumentException when the user has no account with the given number
     */
    public void unblockAccount(String accountNumber) {
        setAccountActive(accountNumber, true);
    }

    private void setAccountActive(String accountNumber, boolean active) {
        BankAccount account = findAccount(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User '" + userId + "' has no account with number '" + accountNumber + "'"));
        account.setActive(active);
    }

     // Two users are equal when they have the same user id.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return userId.equals(user.userId);
    }

    @Override
    public int hashCode() {
        return userId.hashCode();
    }

    @Override
    public String toString() {
        return "User{userId='" + userId + "', accounts=" + accounts.size() + "}";
    }
}