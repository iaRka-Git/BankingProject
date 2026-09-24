package bank.account;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe generator of unique account numbers.
 *
 * <p>Numbers are 20-character strings in the format {@code UA} + 18 zero-padded digits,
 * for example {@code UA000000000000000001}.
 */
public final class AccountNumberGenerator {

    private static final String PREFIX = "UA";
    private static final int NUMBER_LENGTH = 18;
    private static final long MAX_NUMBER = 999_999_999_999_999_999L;
    private static final String FORMAT = PREFIX + "%0" + NUMBER_LENGTH + "d";

    private final AtomicLong counter = new AtomicLong(0L);

    /**
     * Returns the next unique account number.
     *
     * @return a unique 20-character account number
     * @throws IllegalStateException if the number range is exhausted
     */
    public String next() {
        long value = counter.incrementAndGet();
        if (value > MAX_NUMBER) {
            throw new IllegalStateException("Account number range exhausted");
        }
        return String.format(Locale.ROOT, FORMAT, value);
    }
}
