package bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A bank account that may be overdrawn up to a configured credit limit and is
 * charged periodic interest on the borrowed (negative) balance.
 *
 * <p>The sign convention follows {@link BankAccount}: a deposit records a
 * positive amount and increases the balance, a withdrawal records a negative
 * amount and decreases it. An interest charge is money leaving the account, so
 * it is recorded as a negative transaction.
 *
 * <p>Interest is expressed as an annual percentage and {@link
 * #chargeCreditInterest()} applies one month's worth per call. The method is
 * intended to run once per billing period; it does not track elapsed time and
 * calling it twice in the same period charges on the already-reduced balance.
 *
 * <p>Like the rest of the model, this class is not thread-safe.
 */
public class CreditAccount extends BankAccount {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** annualRate / 100 / 12 == annualRate / 1200, applied in a single division. */
    private static final BigDecimal MONTHLY_RATE_DIVISOR = BigDecimal.valueOf(1200);

    private static final String DEFAULT_WITHDRAWAL_COMMENT = "Credit funds withdrawal";
    private static final String INTEREST_COMMENT = "Interest charge on utilized credit";

    private final BigDecimal creditLimit;
    private final BigDecimal interestRate;

    /**
     * @param accountNumber unique account number, must not be null
     * @param ownerId       identifier of the owning user, must not be null
     * @param currency      account currency, must not be null
     * @param creditLimit   maximum amount the balance may go negative by; non-null and non-negative.
     *                      A limit of zero yields an account that cannot be overdrawn.
     * @param interestRate  annual percentage charged on the negative balance; non-null and non-negative
     * @throws NullPointerException     if any reference argument is null
     * @throws IllegalArgumentException if {@code creditLimit} or {@code interestRate} is negative
     */
    public CreditAccount(String accountNumber,
                         String ownerId,
                         Currency currency,
                         BigDecimal creditLimit,
                         BigDecimal interestRate) {
        super(accountNumber, ownerId, currency);
        this.creditLimit = requireNonNegative(creditLimit, "Credit limit cannot be negative");
        this.interestRate = requireNonNegative(interestRate, "Interest rate cannot be negative");
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    /**
     * The lowest balance this account may reach: {@code -creditLimit}.
     */
    private BigDecimal floor() {
        return creditLimit.negate();
    }

    /**
     * Withdraws funds, allowing the balance to fall to {@code -creditLimit}.
     *
     * @param amount  positive amount to withdraw
     * @param comment transaction comment; when null or blank a default is used
     * @throws IllegalStateException    if the account is inactive, or if the withdrawal would
     *                                  take the balance below {@code -creditLimit}
     * @throws IllegalArgumentException if {@code amount} is null or not positive
     */
    @Override
    public void withdraw(BigDecimal amount, String comment) {
        if (!isActive()) {
            throw new IllegalStateException("Account is blocked or inactive.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive.");
        }

        BigDecimal projected = this.balance.subtract(amount);
        if (projected.compareTo(floor()) < 0) {
            throw new IllegalStateException("Credit limit exceeded.");
        }

        this.balance = projected;
        String txComment = (comment != null && !comment.isBlank()) ? comment : DEFAULT_WITHDRAWAL_COMMENT;
        recordTransaction(amount.negate(), txComment);
    }

    /**
     * Charges one month's interest on the negative balance.
     *
     * <p>The fee is {@code round(abs(balance) * interestRate / 100 / 12)} at
     * scale 2, {@code HALF_UP}. It is deducted from the balance and recorded as
     * a negative transaction. An interest charge is intentionally <em>not</em>
     * refused for taking the balance below {@code -creditLimit}; refusing it
     * would let an account escape accrued fees. What the limit blocks is further
     * withdrawals, not the posting of fees.
     *
     * @return the positive fee charged, or {@link BigDecimal#ZERO} when the
     *         balance is not negative or the fee rounds to zero
     */
    public BigDecimal chargeCreditInterest() {
        if (this.balance.signum() >= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal fee = this.balance.abs()
                .multiply(interestRate)
                .divide(MONTHLY_RATE_DIVISOR, MONEY_SCALE, MONEY_ROUNDING);

        if (fee.signum() == 0) {
            return BigDecimal.ZERO;
        }

        this.balance = this.balance.subtract(fee);
        recordTransaction(fee.negate(), INTEREST_COMMENT);
        return fee;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String message) {
        Objects.requireNonNull(value, message);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
