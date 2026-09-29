package bank.storage;

import bank.User;

import java.util.Map;

/**
 * Persists and restores the state of the bank: all users together with their accounts,
 * balances, active flags and transaction histories.
 */
public interface StorageService {

    /**
     * Loads all persisted users.
     *
     * @return a mutable map of users keyed by user id, empty when nothing was saved yet
     * @throws StorageException when the persisted state cannot be read
     */
    Map<String, User> loadUsers();

    /**
     * Saves all users with their accounts, balances, active flags and transaction histories.
     *
     * @param users the users to persist, keyed by user id
     * @throws StorageException when the state cannot be written
     */
    void saveUsers(Map<String, User> users);
}