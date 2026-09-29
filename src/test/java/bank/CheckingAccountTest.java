package bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckingAccountTest {

    private static final String ACCOUNT_NUMBER = "UA000000000000000001";
    private static final String OWNER_ID = "customer-001";

    private final CheckingAccount account = new CheckingAccount(ACCOUNT_NUMBER, OWNER_ID, Currency.EUR);

    @Test
    void shouldStartWithZeroBalanceAndEmptyStatement() {
        assertEquals(BigDecimal.ZERO, account.getBalance());
        assertTrue(account.getStatement().isEmpty());
        assertTrue(account.isActive());
        assertEquals(ACCOUNT_NUMBER, account.getAccountNumber());
        assertEquals(OWNER_ID, account.getOwnerId());
        assertEquals(Currency.EUR, account.getCurrency());
    }

    @Test
    void shouldDepositMoney() {
        account.deposit(new BigDecimal("150.50"), "Salary");

        assertEquals(0, new BigDecimal("150.50").compareTo(account.getBalance()));
        assertEquals(1, account.getStatement().size());
    }

    @Test
    void shouldWithdrawMoney() {
        account.deposit(new BigDecimal("200"), "Salary");

        account.withdraw(new BigDecimal("50.50"), "Rent");

        assertEquals(0, new BigDecimal("149.50").compareTo(account.getBalance()));
    }

    @Test
    void shouldRejectWithdrawAboveBalance() {
        account.deposit(new BigDecimal("10"), "Salary");

        assertThrows(IllegalArgumentException.class, () -> account.withdraw(new BigDecimal("10.01"), "Too much"));
        assertEquals(0, BigDecimal.TEN.compareTo(account.getBalance()));
    }

    @Test
    void shouldRejectWithdrawOnBlockedAccount() {
        account.deposit(new BigDecimal("10"), "Salary");
        account.setActive(false);

        assertThrows(IllegalStateException.class, () -> account.withdraw(new BigDecimal("5"), "Rent"));
    }

    @Test
    void shouldRejectNonPositiveAmounts() {
        assertThrows(IllegalArgumentException.class, () -> account.deposit(BigDecimal.ZERO, "Nothing"));
        assertThrows(IllegalArgumentException.class, () -> account.deposit(new BigDecimal("-5"), "Negative"));
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(BigDecimal.ZERO, "Nothing"));
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(new BigDecimal("-5"), "Negative"));
    }

    @Test
    void shouldRestorePersistedState() {
        account.deposit(new BigDecimal("80"), "Salary");
        account.setActive(false);

        account.restoreState(new BigDecimal("12.34"), true, List.of(
                new BankAccount.Transaction(new BigDecimal("80"), LocalDateTime.parse("2026-09-28T10:15:30"),
                        "Salary")));

        assertEquals(0, new BigDecimal("12.34").compareTo(account.getBalance()));
        assertTrue(account.isActive());
        assertEquals(1, account.getStatement().size());
        assertEquals("Salary", account.getStatement().get(0).comment());
    }

    @Test
    void shouldReplaceStateOnRestore() {
        account.deposit(new BigDecimal("80"), "Salary");

        account.restoreState(new BigDecimal("5"), true, null);

        assertEquals(0, new BigDecimal("5").compareTo(account.getBalance()));
        assertTrue(account.getStatement().isEmpty());
    }

    @Test
    void shouldRejectNullBalanceOnRestore() {
        assertThrows(NullPointerException.class, () -> account.restoreState(null, true, null));
    }
}