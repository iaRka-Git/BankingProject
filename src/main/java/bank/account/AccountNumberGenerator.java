package bank.account;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Issues sequential account numbers in the format UA followed by 18 digits.
 */
public final class AccountNumberGenerator {

    private static final String PREFIX = "UA";
    private static final int NUMBER_LENGTH = 18;
    private static final long MAX_NUMBER = 999_999_999_999_999_999L;
    private static final String FORMAT = PREFIX + "%0" + NUMBER_LENGTH + "d";
    private static final Pattern GENERATED_NUMBER = Pattern.compile(PREFIX + "[0-9]{" + NUMBER_LENGTH + "}");

    private final AtomicLong counter;

    public AccountNumberGenerator() {
        this.counter = new AtomicLong();
    }

    /**
     * Resumes numbering after the highest stored number in the generated format.
     * Legacy numbers in other formats cannot collide with the generated sequence.
     */
    public AccountNumberGenerator(Iterable<String> existingNumbers) {
        long highestNumber = 0;
        for (String number : existingNumbers) {
            if (GENERATED_NUMBER.matcher(number).matches()) {
                highestNumber = Math.max(highestNumber, Long.parseLong(number.substring(PREFIX.length())));
            }
        }
        this.counter = new AtomicLong(highestNumber);
    }

    /**
     * Returns the next number. Calls on the same generator are thread-safe.
     *
     * @throws IllegalStateException if all numbers in the sequence have been issued
     */
    public String next() {
        long value = counter.updateAndGet(current -> {
            if (current == MAX_NUMBER) {
                throw new IllegalStateException("Account number range exhausted");
            }
            return current + 1;
        });
        return String.format(Locale.ROOT, FORMAT, value);
    }
}
