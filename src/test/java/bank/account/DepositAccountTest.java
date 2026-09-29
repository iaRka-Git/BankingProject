package bank.account;

import bank.BankAccount;
import bank.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DepositAccountTest {

    private static final String ACCOUNT_NUMBER = "UA000000000000000001";
    private static final String OWNER_ID = "user-123";
    private static final Currency CURRENCY = Currency.UAH;
    private static final BigDecimal INTEREST_RATE = new BigDecimal("0.05");

    @Test
    void shouldExtendBankAccount() {
        DepositAccount account = createActiveDeposit(LocalDate.now().plusDays(30), false);

        assertInstanceOf(BankAccount.class, account);
        assertEquals(ACCOUNT_NUMBER, account.getAccountNumber());
        assertEquals(OWNER_ID, account.getOwnerId());
        assertEquals(CURRENCY, account.getCurrency());
    }

    @Test
    void shouldAllowWithdrawalAfterMaturityDate() {
        DepositAccount account = createActiveDeposit(LocalDate.now().minusDays(1), false);
        account.deposit(new BigDecimal("1000.00"));

        account.withdraw(new BigDecimal("200.00"), "Post-maturity withdrawal");

        assertEquals(new BigDecimal("800.00"), account.getBalance());
        assertEquals(2, account.getStatement().size());
        assertEquals("Post-maturity withdrawal", account.getStatement().get(1).comment());
    }

    @Test
    void shouldRejectEarlyWithdrawalWhenNotAllowed() {
        DepositAccount account = createActiveDeposit(LocalDate.now().plusDays(30), false);
        account.deposit(new BigDecimal("1000.00"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> account.withdraw(new BigDecimal("100.00"))
        );

        assertEquals("Withdrawals are locked until maturity date", exception.getMessage());
        assertEquals(new BigDecimal("1000.00"), account.getBalance());
    }

    @Test
    void shouldAllowEarlyWithdrawalWithPenaltyWhenAllowed() {
        DepositAccount account = createActiveDeposit(LocalDate.now().plusDays(30), true);
        account.deposit(new BigDecimal("1000.00"));

        account.withdraw(new BigDecimal("100.00"), "Urgent early withdrawal");

        BigDecimal expectedBalance = new BigDecimal("899.00"); // 1000 - 100 - 1% penalty
        assertEquals(expectedBalance, account.getBalance());
        assertEquals(2, account.getStatement().size());
        assertEquals("Urgent early withdrawal", account.getStatement().get(1).comment());
    }

    @Test
    void shouldRejectEarlyWithdrawalIfFundsCannotCoverPenalty() {
        DepositAccount account = createActiveDeposit(LocalDate.now().plusDays(30), true);
        account.deposit(new BigDecimal("100.00"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> account.withdraw(new BigDecimal("100.00"))
        );

        assertEquals("Insufficient funds to cover withdrawal and penalty.", exception.getMessage());
    }

    @Test
    void shouldRejectWithdrawalWhenBalanceInsufficient() {
        DepositAccount account = createActiveDeposit(LocalDate.now().minusDays(1), false);
        account.deposit(new BigDecimal("100.00"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> account.withdraw(new BigDecimal("200.00"))
        );

        assertEquals("Insufficient funds.", exception.getMessage());
    }

    @Test
    void shouldRejectNonPositiveWithdrawalAmount() {
        DepositAccount account = createActiveDeposit(LocalDate.now().minusDays(1), false);
        account.deposit(new BigDecimal("100.00"));

        assertThrows(IllegalArgumentException.class, () -> account.withdraw(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(new BigDecimal("-10.00")));
    }

    @Test
    void shouldAccrueInterestAndRecordTransaction() {
        DepositAccount account = createActiveDeposit(LocalDate.now().plusDays(30), false);
        account.deposit(new BigDecimal("1000.00"));

        account.accrueInterest();

        BigDecimal expectedInterest = new BigDecimal("50.00");
        BigDecimal expectedBalance = new BigDecimal("1050.00");
        assertEquals(expectedBalance, account.getBalance());
        assertEquals(2, account.getStatement().size());

        BankAccount.Transaction interestTx = account.getStatement().get(1);
        assertEquals(expectedInterest, interestTx.amount());
        assertEquals("Interest accrual for deposit", interestTx.comment());
    }

    @Test
    void shouldUseDistinctCommentsForDifferentOperations() {
        DepositAccount account = createActiveDeposit(LocalDate.now().plusDays(30), true);
        account.deposit(new BigDecimal("1000.00"));

        account.accrueInterest();
        account.withdraw(new BigDecimal("100.00"), "Early need");

        assertEquals(3, account.getStatement().size());
        assertEquals("Account deposit", account.getStatement().get(0).comment());
        assertEquals("Interest accrual for deposit", account.getStatement().get(1).comment());
        assertEquals("Early need", account.getStatement().get(2).comment());
    }

    private DepositAccount createActiveDeposit(LocalDate maturityDate, boolean allowEarlyWithdrawal) {
        return new DepositAccount(ACCOUNT_NUMBER, OWNER_ID, CURRENCY, maturityDate, allowEarlyWithdrawal, INTEREST_RATE);
    }
}
