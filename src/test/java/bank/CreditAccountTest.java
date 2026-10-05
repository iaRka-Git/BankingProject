package bank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditAccountTest {

    private CreditAccount account;

    @BeforeEach
    void setUp() {
        account = new CreditAccount("UA1", "owner-1", Currency.UAH,
                new BigDecimal("5000.00"), new BigDecimal("12"));
    }

    @Test
    void withdrawWithinCreditLimitOverdrawsSuccessfully() {
        account.withdraw(new BigDecimal("3000.00"), null);

        assertEquals(new BigDecimal("-3000.00"), account.getBalance());
    }

    @Test
    void withdrawExactlyToCreditLimitSucceeds() {
        account.withdraw(new BigDecimal("5000.00"), "edge");

        assertEquals(new BigDecimal("-5000.00"), account.getBalance());
    }

    @Test
    void withdrawBeyondCreditLimitIsRejectedAndBalanceUnchanged() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> account.withdraw(new BigDecimal("5000.01"), "too much"));

        assertTrue(ex.getMessage().contains("Credit limit exceeded"));
        assertEquals(BigDecimal.ZERO, account.getBalance());
    }

    @Test
    void withdrawRejectsNullAndNonPositiveAmounts() {
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(null, "null"));
        assertThrows(IllegalArgumentException.class,
                () -> account.withdraw(BigDecimal.ZERO, "zero"));
        assertThrows(IllegalArgumentException.class,
                () -> account.withdraw(new BigDecimal("-1"), "negative"));
    }

    @Test
    void withdrawOnInactiveAccountIsRejected() {
        account.setActive(false);

        assertThrows(IllegalStateException.class,
                () -> account.withdraw(new BigDecimal("10"), "blocked"));
    }

    @Test
    void withdrawWithNullCommentUsesDefault() {
        account.withdraw(new BigDecimal("100"), null);

        List<BankAccount.Transaction> statement = account.getStatement();
        assertEquals(1, statement.size());
        assertEquals("Credit funds withdrawal", statement.get(0).comment());
        assertEquals(new BigDecimal("-100"), statement.get(0).amount());
    }

    @Test
    void chargeCreditInterestOnNegativeBalanceDeductsMonthlyFee() {
        account.withdraw(new BigDecimal("1000.00"), "spend");

        // 1000 * 12 / 100 / 12 = 10.00
        BigDecimal fee = account.chargeCreditInterest();

        assertEquals(new BigDecimal("10.00"), fee);
        assertEquals(new BigDecimal("-1010.00"), account.getBalance());
    }

    @Test
    void chargeCreditInterestRoundsHalfUpAtScaleTwo() {
        account.withdraw(new BigDecimal("333.33"), "spend");

        // 333.33 * 12 / 1200 = 3.3333 -> 3.33
        BigDecimal fee = account.chargeCreditInterest();

        assertEquals(new BigDecimal("3.33"), fee);
    }

    @Test
    void chargeCreditInterestDoesNotTerminateOnNonTerminatingRate() {
        CreditAccount oddRate = new CreditAccount("UA2", "owner-1", Currency.UAH,
                new BigDecimal("1000"), new BigDecimal("7"));
        oddRate.withdraw(new BigDecimal("100"), "spend");

        // 100 * 7 / 1200 = 0.58333... must not throw ArithmeticException
        BigDecimal fee = oddRate.chargeCreditInterest();

        assertEquals(new BigDecimal("0.58"), fee);
    }

    @Test
    void chargeCreditInterestOnNonNegativeBalanceIsNoOp() {
        account.deposit(new BigDecimal("500"), "salary");

        BigDecimal fee = account.chargeCreditInterest();

        assertEquals(BigDecimal.ZERO, fee);
        assertEquals(new BigDecimal("500"), account.getBalance());
        assertEquals(1, account.getStatement().size());
    }

    @Test
    void chargeCreditInterestWithZeroRateIsNoOp() {
        CreditAccount zeroRate = new CreditAccount("UA3", "owner-1", Currency.UAH,
                new BigDecimal("1000"), BigDecimal.ZERO);
        zeroRate.withdraw(new BigDecimal("500"), "spend");

        BigDecimal fee = zeroRate.chargeCreditInterest();

        assertEquals(BigDecimal.ZERO, fee);
        assertEquals(new BigDecimal("-500"), zeroRate.getBalance());
        assertEquals(1, zeroRate.getStatement().size());
    }

    @Test
    void interestChargeMayPushBalanceBelowCreditLimit() {
        account.withdraw(new BigDecimal("4999.00"), "spend");

        // fee = 4999 * 12 / 1200 = 49.99 -> balance -5048.99, below -5000 floor
        BigDecimal fee = account.chargeCreditInterest();

        assertEquals(new BigDecimal("49.99"), fee);
        assertEquals(new BigDecimal("-5048.99"), account.getBalance());
    }

    @Test
    void withdrawIsRefusedWhenAlreadyBelowLimitFromInterest() {
        account.withdraw(new BigDecimal("4999.00"), "spend");
        account.chargeCreditInterest();

        assertThrows(IllegalStateException.class,
                () -> account.withdraw(BigDecimal.ONE, "blocked by accrued interest"));
    }

    @Test
    void interestTransactionIsRecordedAsNegativeWithComment() {
        account.withdraw(new BigDecimal("1200"), "spend");
        account.chargeCreditInterest();

        List<BankAccount.Transaction> statement = account.getStatement();
        BankAccount.Transaction interest = statement.get(statement.size() - 1);

        assertEquals("Interest charge on utilized credit", interest.comment());
        assertEquals(new BigDecimal("-12.00"), interest.amount());
    }

    @Test
    void depositRepaysOverdrawnBalance() {
        account.withdraw(new BigDecimal("1000"), "spend");
        account.deposit(new BigDecimal("1500"), "salary");

        assertEquals(new BigDecimal("500"), account.getBalance());
    }

    @Test
    void zeroCreditLimitBehavesAsNonOverdraftAccount() {
        CreditAccount noCredit = new CreditAccount("UA4", "owner-1", Currency.UAH,
                BigDecimal.ZERO, new BigDecimal("12"));

        assertThrows(IllegalStateException.class,
                () -> noCredit.withdraw(BigDecimal.ONE, "no credit"));
        assertEquals(BigDecimal.ZERO, noCredit.getBalance());
    }

    @Test
    void constructorRejectsNegativeCreditLimitAndRate() {
        assertThrows(IllegalArgumentException.class, () -> new CreditAccount(
                "UA5", "owner-1", Currency.UAH, new BigDecimal("-1"), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new CreditAccount(
                "UA6", "owner-1", Currency.UAH, BigDecimal.ONE, new BigDecimal("-0.5")));
    }

    @Test
    void constructorRejectsNullCreditLimitAndRate() {
        assertThrows(NullPointerException.class, () -> new CreditAccount(
                "UA7", "owner-1", Currency.UAH, null, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> new CreditAccount(
                "UA8", "owner-1", Currency.UAH, BigDecimal.ONE, null));
    }
}
