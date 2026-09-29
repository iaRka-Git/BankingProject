package bank.account;

import bank.BankAccount;
import bank.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A term/savings deposit account.
 *
 * <p>Funds are locked until the maturity date. Early withdrawal is allowed only
 * when {@code allowEarlyWithdrawal} is {@code true} and is subject to a penalty.
 * Interest can be accrued periodically on the current balance.</p>
 */
public class DepositAccount extends BankAccount {

    private static final BigDecimal EARLY_WITHDRAWAL_PENALTY_RATE = new BigDecimal("0.01");
    private static final String WITHDRAWAL_COMMENT = "Account withdrawal";
    private static final String EARLY_WITHDRAWAL_COMMENT = "Early withdrawal (penalty applied)";
    private static final String INTEREST_ACCRUAL_COMMENT = "Interest accrual for deposit";

    private final LocalDate maturityDate;
    private final boolean allowEarlyWithdrawal;
    private final BigDecimal interestRate;

    /**
     * Creates a new deposit account.
     *
     * @param accountNumber         unique account number
     * @param ownerId               identifier of the account owner
     * @param currency              account currency
     * @param maturityDate          date until which the funds are locked
     * @param allowEarlyWithdrawal  whether withdrawals before maturity are allowed
     * @param interestRate          interest rate used to calculate earnings (e.g. 0.05 for 5%)
     */
    public DepositAccount(String accountNumber,
                          String ownerId,
                          Currency currency,
                          LocalDate maturityDate,
                          boolean allowEarlyWithdrawal,
                          BigDecimal interestRate) {
        super(accountNumber, ownerId, currency);

        this.maturityDate = Objects.requireNonNull(maturityDate, "Maturity date cannot be null");
        this.allowEarlyWithdrawal = allowEarlyWithdrawal;
        this.interestRate = Objects.requireNonNull(interestRate, "Interest rate cannot be null");

        if (interestRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Interest rate cannot be negative");
        }
    }


    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public boolean isEarlyWithdrawalAllowed() {
        return allowEarlyWithdrawal;
    }


    public BigDecimal getInterestRate() {
        return interestRate;
    }

    /**
     * Withdraws funds respecting the maturity date and early withdrawal rules.
     */
    @Override
    public void withdraw(BigDecimal amount, String comment) {
        ensureCanWithdraw(amount);

        if (LocalDate.now().isBefore(maturityDate)) {
            withdrawEarly(amount, comment);
        } else {
            withdrawStandard(amount, comment);
        }
    }

    /**
     * Accrues interest on the current balance and records the transaction.
     */
    public void accrueInterest() {
        if (!isActive()) {
            throw new IllegalStateException("Account is blocked or inactive.");
        }

        BigDecimal interest = balance.multiply(interestRate).setScale(2, RoundingMode.HALF_UP);
        if (interest.compareTo(BigDecimal.ZERO) > 0) {
            balance = balance.add(interest).setScale(2, RoundingMode.HALF_UP);
            recordTransaction(interest, INTEREST_ACCRUAL_COMMENT);
        }
    }

    /** Validates account state, amount and balance before withdrawal. */
    private void ensureCanWithdraw(BigDecimal amount) {
        if (!isActive()) {
            throw new IllegalStateException("Account is blocked or inactive.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive.");
        }
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds.");
        }
    }

    /** Processes a standard withdrawal after the maturity date. */
    private void withdrawStandard(BigDecimal amount, String comment) {
        balance = balance.subtract(amount).setScale(2, RoundingMode.HALF_UP);
        String txComment = (comment != null && !comment.isBlank()) ? comment : WITHDRAWAL_COMMENT;
        recordTransaction(amount.negate(), txComment);
    }

    /** Processes an early withdrawal before the maturity date with a penalty. */
    private void withdrawEarly(BigDecimal amount, String comment) {
        if (!allowEarlyWithdrawal) {
            throw new IllegalStateException("Withdrawals are locked until maturity date");
        }

        BigDecimal penalty = amount.multiply(EARLY_WITHDRAWAL_PENALTY_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDeduction = amount.add(penalty);

        if (balance.compareTo(totalDeduction) < 0) {
            throw new IllegalStateException("Insufficient funds to cover withdrawal and penalty.");
        }

        balance = balance.subtract(totalDeduction).setScale(2, RoundingMode.HALF_UP);
        String txComment = (comment != null && !comment.isBlank()) ? comment : EARLY_WITHDRAWAL_COMMENT;
        recordTransaction(totalDeduction.negate(), txComment);
    }
}
